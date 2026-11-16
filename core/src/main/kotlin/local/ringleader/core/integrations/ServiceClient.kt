package local.ringleader.core.integrations

import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import local.ringleader.core.Trace
import local.ringleader.core.TraceSource
import local.ringleader.core.serverJson

/** Sends one request. In the app this is [ServiceWire.exchange]; tests give a scripted one. */
fun interface ServiceSender {
    fun send(origin: ServiceOrigin, trust: WireTrust, request: WireRequest, limits: WireLimits): WireAnswer
}

fun ServiceWire.asSender() = ServiceSender { origin, trust, request, limits -> exchange(origin, trust, request, limits) }

/** One fetch's outcome: an answer, or a problem; [status] when the service answered at all. */
data class Fetched(val response: ResponseValue?, val status: Int?, val problem: ServiceProblem?, val unencrypted: UnencryptedMark?, val at: Instant)

/** The probe's verdict: which variant answers, or why none does. */
sealed interface ProbeVerdict {
    data class Found(val variant: TemplateVariant) : ProbeVerdict
    data class NoVariant(val problem: ServiceProblem) : ProbeVerdict
}

/**
 * One link's requests, one at a time (a lock: never two in flight for a link). Builds each request only from the template:
 * the declared path, declared query names, values from the template, the link's checked parameters or time macros, all
 * percent-encoded; adds exactly the auth scheme's header; maps the answer to a [Fetched] by status code and exception class.
 * Traces method, the template's declared path (never the expanded one, never a query value) and status.
 */
class ServiceClient(
    private val template: ServiceTemplate,
    private val variant: TemplateVariant,
    private val origin: ServiceOrigin,
    private val trust: WireTrust,
    private val credential: ServiceCredential?,
    private val sender: ServiceSender,
    private val clock: () -> Instant,
    linkId: String,
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val linkTag = shortHash(linkId)
    private val session: ServiceSession? = if (variant.auth.scheme == AuthScheme.SessionPassword) ServiceSession(variant.auth, clock) { r -> send(WireRequest(r.method, origin.basePath + r.path, r.headers, r.body, r.carriesCredential), r.path) } else null

    val sessionSignedIn get() = session?.signedIn == true
    val loginsSent get() = session?.logins ?: 0

    fun fetch(requestId: String, params: Map<String, List<String>>): Fetched = lock.withLock {
        val spec = variant.requests[requestId] ?: return problem(ProblemKind.RequestRefused, "The template has no request $requestId.")
        val path = try { build(spec.path, spec.query, params) } catch (e: SettingMissing) { return problem(ProblemKind.SettingNeeded, e.message!!) }
        val limits = WireLimits(maxBytes = spec.maxBytes)
        ServiceAuth.problem(variant.auth, credential)?.let { return Fetched(null, null, it, null, clock()) }
        var retried = false
        while (true) {
            val headers = mutableListOf<Pair<String, String>>()
            var sessionValue: String? = null
            when {
                session != null -> when (val g = session.header(credential)) {
                    is ServiceSession.Grant.Header -> { headers += g.name to g.value; sessionValue = g.value }
                    is ServiceSession.Grant.Denied -> return Fetched(null, null, g.problem, null, clock())
                }
                credential != null -> ServiceAuth.header(variant.auth, credential)?.let { headers += it }
            }
            val request = WireRequest(WireMethod.GET, path, headers, carriesCredential = headers.isNotEmpty(),
                accept = if (spec.kind == ResponseKind.Json) "application/json" else "text/plain, */*", expectJson = spec.kind == ResponseKind.Json)
            val answer = try { send(request, spec.path, limits) } catch (e: ServiceWireException) { return Fetched(null, null, problemOf(e), null, clock()) }
            if (answer.status == 401 && session != null && sessionValue != null && !retried) {
                // The session ended at the service (expired, restarted, another client took the seat): one fresh login, one retry.
                session.rejected(sessionValue); retried = true; continue
            }
            return interpret(spec, answer)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /** Runs the variants' probes in order, without a credential. The first that answers as declared wins. */
    fun probeVariant(): ProbeVerdict = lock.withLock { probe(template, origin, trust, sender, clock, linkTag) }

    /** Drops the session at the service (the template's one DELETE). */
    fun logout(): Boolean = lock.withLock { session?.logout() ?: false }

    override fun close() { logout() }

    private fun interpret(spec: RequestSpec, answer: WireAnswer): Fetched {
        val now = clock()
        val s = answer.status
        fun p(kind: ProblemKind, text: String) = Fetched(null, s, ServiceProblem(kind, spec.errors[s.toString()] ?: text, s, answer.retryAfterSeconds), answer.unencrypted, now)
        return when (s) {
            in 200..299 -> when (spec.kind) {
                ResponseKind.Text -> Fetched(ResponseValue.Text(answer.text, readable(answer), now), s, null, answer.unencrypted, now)
                ResponseKind.Json -> structured(answer.text)?.let { Fetched(ResponseValue.Json(it, readable(answer), now), s, null, answer.unencrypted, now) }
                    ?: p(ProblemKind.NotThisService, "${template.name} answered, but not with JSON.")
            }
            in 300..399 -> p(ProblemKind.Redirected, "The service sent the request elsewhere${answer.redirect?.path?.let { " (to $it)" } ?: ""}. Enter the address it really uses.")
            401 -> p(ProblemKind.CredentialRefused, variant.auth.errors["401"] ?: "${template.name} refused the ${variant.auth.ask.ifEmpty { "request" }}.")
            403 -> p(ProblemKind.Forbidden, variant.auth.errors["403"] ?: "The credential works, but may not read this.")
            404 -> p(ProblemKind.ApiChanged, "${template.name} has no ${spec.path} here. The service's API may have changed.")
            429 -> p(ProblemKind.RateLimited, variant.auth.errors["429"] ?: "${template.name} asked RingMASTER to wait.")
            in 500..599 -> p(ProblemKind.ServerError, "${template.name} failed to answer (HTTP $s).")
            else -> p(ProblemKind.ApiChanged, "${template.name} answered HTTP $s.")
        }
    }

    private fun structured(text: String) = Companion.structured(text)

    private fun readable(answer: WireAnswer) = answer.headers.filterKeys { it in ServiceWire.READABLE_HEADERS }

    private fun send(request: WireRequest, declaredPath: String, limits: WireLimits = WireLimits()): WireAnswer =
        traced(request.method, declaredPath, template.id, linkTag) { sender.send(origin, trust, request, limits) }

    private fun problem(kind: ProblemKind, text: String) = Fetched(null, null, ServiceProblem(kind, text), null, clock())

    private fun build(path: String, query: Map<String, String>, params: Map<String, List<String>>): String =
        buildPath(origin.basePath, path, query, params, variant.params, clock())

    class SettingMissing(message: String) : Exception(message)

    companion object {
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

        /** A JSON object or array; the parser also accepts a bare word such as `<html>`, which no API answers with. */
        internal fun structured(text: String): kotlinx.serialization.json.JsonElement? =
            runCatching { serverJson(text) }.getOrNull()?.takeIf { it is kotlinx.serialization.json.JsonObject || it is kotlinx.serialization.json.JsonArray }

        fun shortHash(id: String): String = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(4).joinToString("") { "%02x".format(it) }

        internal fun traced(method: WireMethod, declaredPath: String, templateId: String, linkTag: String, block: () -> WireAnswer): WireAnswer {
            val span = Trace.begin(TraceSource.Services, "${method.name} $declaredPath", mapOf("template" to templateId, "link" to linkTag))
            try { return block().also { span.end(status = it.status, bytesIn = it.body.size.toLong()) } }
            catch (t: Throwable) { span.end(error = t); throw t }
        }

        /** The probe of each variant in order; no credential is sent. */
        fun probe(template: ServiceTemplate, origin: ServiceOrigin, trust: WireTrust, sender: ServiceSender, clock: () -> Instant, linkTag: String): ProbeVerdict {
            var lastStatus: Int? = null
            for (variant in template.variants) {
                val p = variant.probe
                val path = buildPath(origin.basePath, p.path, p.query, emptyMap(), emptyList(), clock())
                val answer = try {
                    traced(WireMethod.GET, p.path, template.id, linkTag) {
                        sender.send(origin, trust, WireRequest(WireMethod.GET, path, expectJson = p.kind == ResponseKind.Json), WireLimits(maxBytes = 64 * 1024))
                    }
                } catch (e: ServiceWireException) { return ProbeVerdict.NoVariant(problemOf(e)) }
                lastStatus = answer.status
                if (answer.status !in p.accept) continue
                // `expect` must hold on a 2xx answer, and on any other accepted answer that is JSON (Pi-hole's 401 still names its
                // session). An accepted refusal that is not JSON (Home Assistant's "401: Unauthorized") is confirmed by the first read.
                if (p.expect != null) {
                    val value: ResponseValue? = if (p.kind == ResponseKind.Text) (if (answer.status in 200..299) ResponseValue.Text(answer.text) else null)
                        else structured(answer.text)?.let { ResponseValue.Json(it) }
                    if (value == null && answer.status in 200..299) continue
                    if (value != null) {
                        val ok = TemplateExpr.eval(p.expect.node, ExprContext(mapOf("probe" to value), now = clock()))
                        if ((ok as? XValue.Bool)?.value != true) continue
                    }
                }
                return ProbeVerdict.Found(variant)
            }
            return ProbeVerdict.NoVariant(ServiceProblem(ProblemKind.NotThisService, "This answers, but it is not ${template.name}" +
                (lastStatus?.let { " (HTTP $it to its probe)" } ?: "") + ".", lastStatus))
        }

        /** Base path + declared path with placeholders filled; declared query with each value percent-encoded. */
        internal fun buildPath(base: String, path: String, query: Map<String, String>, params: Map<String, List<String>>, specs: List<ParamSpec>, now: Instant): String {
            fun fill(token: String): String {
                val name = token.removePrefix("{").removeSuffix("}")
                return when {
                    name.startsWith("param:") -> {
                        val id = name.removePrefix("param:")
                        val spec = specs.firstOrNull { it.id == id } ?: throw SettingMissing("The setting '$id' is not part of this template.")
                        val values = params[id].orEmpty()
                        if (values.isEmpty()) throw SettingMissing("Choose ${spec.label.lowercase()} first.")
                        if (values.size > spec.max || values.any { !spec.pattern.matches(it) }) throw SettingMissing("${spec.label} holds a value this template does not accept.")
                        values.joinToString(",")
                    }
                    name == "now" -> now.epochSecond.toString()
                    name.startsWith("now-") -> (now.epochSecond - seconds(name.removePrefix("now-"))).toString()
                    name == "iso:now" -> ISO.format(now.truncatedTo(ChronoUnit.SECONDS))
                    name.startsWith("iso:now-") -> ISO.format(now.minusSeconds(seconds(name.removePrefix("iso:now-"))).truncatedTo(ChronoUnit.SECONDS))
                    else -> token
                }
            }
            val segments = path.split('/').joinToString("/") { seg -> if (seg.startsWith("{") && seg.endsWith("}")) encode(fill(seg), path = true) else seg }
            val q = query.entries.joinToString("&") { (k, v) -> encode(k, false) + "=" + encode(if (v.startsWith("{")) fill(v) else v, false) }
            return base + segments + if (q.isEmpty()) "" else "?$q"
        }

        private fun seconds(text: String): Long {
            val n = text.dropLast(1).toLong()
            return when (text.last()) { 's' -> n; 'm' -> n * 60; 'h' -> n * 3600; else -> n * 86_400 }
        }

        private fun encode(value: String, path: Boolean): String {
            val e = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
            return if (path) e.replace("%3A", ":").replace("%2C", ",") else e
        }
    }
}
