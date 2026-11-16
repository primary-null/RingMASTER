package local.ringleader.core.integrations

import java.time.Instant
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A link's credential while the app runs: the non-secret identity fields (user name, token id) and one secret. It lives in
 * memory only; [ServiceVault] is the one way it reaches the OS password safe. Building a request header or a login body
 * makes one String of the secret per request, as every HTTP client needs; nothing else copies it.
 */
class ServiceCredential(identity: Map<String, String>, secret: CharArray) : AutoCloseable {
    val identity: Map<String, String> = identity.toMap()
    private val chars = secret.copyOf()
    @Volatile private var wiped = false
    val isWiped get() = wiped

    /** The live array: read it, never keep it. */
    internal fun secret(): CharArray { check(!wiped) { "The credential was wiped." }; return chars }
    fun copy() = ServiceCredential(identity, secret())
    override fun close() { chars.fill(Char(0)); wiped = true }
    override fun toString() = "ServiceCredential(hidden)"
}

/**
 * The named auth schemes. A template only chooses one and fills its declared fields; it never scripts one. The credential
 * goes into exactly one request header (or, for a session login, the declared JSON body of the one login POST), never into a
 * URL.
 */
object ServiceAuth {
    const val MAX_SECRET = 4096
    private const val MAX_IDENTITY = 200

    /** Null when [credential] can be used with [auth]; otherwise what is missing or cannot be sent. Never quotes the secret. */
    fun problem(auth: AuthSpec, credential: ServiceCredential?): ServiceProblem? {
        if (!auth.needsCredential) return null
        if (credential == null || credential.isWiped) return ServiceProblem(ProblemKind.CredentialNeeded, "Enter the ${auth.ask}.")
        val secret = credential.secret()
        if (secret.isEmpty()) return ServiceProblem(ProblemKind.CredentialNeeded, "Enter the ${auth.ask}.")
        val inHeader = auth.scheme == AuthScheme.HeaderKey || auth.scheme == AuthScheme.HeaderTemplate
        if (secret.size > MAX_SECRET || secret.any { it.isISOControl() } || (inHeader && secret.any { it !in ' '..'~' }))
            return ServiceProblem(ProblemKind.CredentialRefused, "The ${auth.ask} holds a character that cannot be sent. Copy it again.")
        for (field in auth.fields.filter { !it.secret }) {
            if (auth.scheme == AuthScheme.Basic && auth.fixedUser != null) continue
            val value = credential.identity[field.id]
            if (value.isNullOrEmpty()) return ServiceProblem(ProblemKind.CredentialNeeded, "Enter the ${field.label.lowercase()}.")
            val plain = value.length <= MAX_IDENTITY && value.none { it.isISOControl() } &&
                (auth.scheme != AuthScheme.HeaderTemplate || value.all { it in ' '..'~' }) && (auth.scheme != AuthScheme.Basic || ':' !in value)
            if (!plain) return ServiceProblem(ProblemKind.CredentialRefused, "The ${field.label.lowercase()} holds a character that cannot be sent.")
        }
        return null
    }

    /** The one header that carries the credential, for the schemes that send it with every request. Null for `none` and sessions. */
    fun header(auth: AuthSpec, credential: ServiceCredential): Pair<String, String>? = when (auth.scheme) {
        AuthScheme.None, AuthScheme.SessionPassword -> null
        AuthScheme.HeaderKey -> auth.header!! to auth.prefix + String(credential.secret())
        AuthScheme.HeaderTemplate -> {
            var value = auth.format!!
            for (field in auth.fields.filter { !it.secret }) value = value.replace("{${field.id}}", credential.identity[field.id].orEmpty())
            auth.header!! to value.replace("{secret}", String(credential.secret()))
        }
        AuthScheme.Basic -> {
            val user = auth.fixedUser ?: auth.fields.firstOrNull { !it.secret }?.let { credential.identity[it.id] }.orEmpty()
            val bytes = (user + ":" + String(credential.secret())).toByteArray(Charsets.UTF_8)
            try { "Authorization" to "Basic " + Base64.getEncoder().encodeToString(bytes) } finally { bytes.fill(0) }
        }
    }

    /** The declared body of the one login POST: JSON keys from the template, values only from the credential's fields. */
    fun loginBody(auth: AuthSpec, credential: ServiceCredential): ByteArray {
        val login = auth.login!!
        val secretId = auth.secretField?.id
        return JsonObject(login.body.mapValues { (_, fieldId) ->
            JsonPrimitive(if (fieldId == secretId) String(credential.secret()) else credential.identity[fieldId].orEmpty())
        }).toString().toByteArray(Charsets.UTF_8)
    }
}

/**
 * One link's session with a `session-password` service (Pi-hole v6). The token lives here, in memory, and nowhere else.
 *
 * - One login at a time: a second caller waits for the first and then uses its token.
 * - A login is sent once. A lost answer is "not reachable"; the next attempt waits [LoginSpec.minIntervalSeconds].
 * - A refused login is latched: nothing more is sent until the owner gives a new credential (a new session object).
 * - A 429 waits for `Retry-After` (capped at an hour by the wire) or the minimum interval.
 * - [logout] sends the template's one DELETE and forgets the token whatever the answer.
 */
class ServiceSession(
    private val auth: AuthSpec,
    private val clock: () -> Instant,
    private val send: (WireRequest) -> WireAnswer,
) {
    private val login = requireNotNull(auth.login) { "Not a session scheme." }
    private val lock = ReentrantLock()
    private var token: String? = null
    private var base: Instant = Instant.EPOCH
    private var validity = 0L
    private var refused: ServiceProblem? = null
    private var notBefore: Instant? = null

    /** Login POSTs sent so far. */
    var logins = 0
        private set

    sealed interface Grant {
        data class Header(val name: String, val value: String) : Grant
        data class Denied(val problem: ServiceProblem) : Grant
    }

    val signedIn: Boolean get() = lock.withLock { token != null }
    val latched: ServiceProblem? get() = lock.withLock { refused }

    /** The session header for the next request, signing in first when there is no live session. */
    fun header(credential: ServiceCredential?): Grant = lock.withLock {
        val now = clock()
        token?.let { live ->
            if (validity <= 0 || now.isBefore(base.plusSeconds(validity - EXPIRY_MARGIN))) {
                if (login.extendsOnUse) base = now
                return Grant.Header(login.sendAs, live)
            }
            token = null
        }
        refused?.let { return Grant.Denied(it) }
        ServiceAuth.problem(auth, credential)?.let { return Grant.Denied(it) }
        notBefore?.let { wait ->
            if (now.isBefore(wait)) return Grant.Denied(ServiceProblem(ProblemKind.RateLimited,
                "Signing in again waits a moment, so the service is not flooded with sign-ins.", retryAfterSeconds = java.time.Duration.between(now, wait).seconds + 1))
        }
        notBefore = now.plusSeconds(login.minIntervalSeconds)
        logins++
        val answer = try {
            send(WireRequest(WireMethod.POST, login.path, body = ServiceAuth.loginBody(auth, credential!!), carriesCredential = true))
        } catch (e: ServiceWireException) {
            return Grant.Denied(problemOf(e))
        }
        when (answer.status) {
            200 -> {}
            401, 403 -> return refuse(auth.errors["401"] ?: "The service refused the ${auth.ask}.", answer.status)
            429 -> {
                val wait = maxOf(answer.retryAfterSeconds ?: 0, login.minIntervalSeconds)
                notBefore = now.plusSeconds(wait)
                return Grant.Denied(ServiceProblem(ProblemKind.RateLimited, auth.errors["429"] ?: "The service allows no more sign-ins for now.", 429, wait))
            }
            in 300..399 -> return Grant.Denied(ServiceProblem(ProblemKind.Redirected, "The service sent the sign-in somewhere else. Enter the address it really uses.", answer.status))
            in 500..599 -> return Grant.Denied(ServiceProblem(ProblemKind.ServerError, "The service failed while signing in (HTTP ${answer.status}).", answer.status))
            else -> return Grant.Denied(ServiceProblem(ProblemKind.ApiChanged, "The service answered the sign-in with HTTP ${answer.status}.", answer.status))
        }
        val json = ServiceClient.structured(answer.text) ?: run {
            return Grant.Denied(ServiceProblem(ProblemKind.ApiChanged, "The service answered the sign-in, but not with JSON.", 200))
        }
        val found = (TemplateExpr.resolve(json, login.token) as? XValue.Text)?.value
        if (found.isNullOrEmpty()) {
            val needsCode = login.totp?.let { (TemplateExpr.resolve(json, it) as? XValue.Bool)?.value } == true
            return refuse(if (needsCode) auth.errors["totp"] ?: "This sign-in needs a two-factor code, which RingMASTER does not send."
                else auth.errors["401"] ?: "The service refused the ${auth.ask}.", 200)
        }
        if (found.length > MAX_TOKEN || found.any { it !in '!'..'~' })
            return Grant.Denied(ServiceProblem(ProblemKind.ApiChanged, "The service's session token is not in a form RingMASTER can send.", 200))
        token = found
        base = now
        validity = login.validity?.let { (TemplateExpr.resolve(json, it) as? XValue.Num)?.value?.toLong() }?.coerceIn(0, MAX_VALIDITY) ?: 0
        Grant.Header(login.sendAs, found)
    }

    private fun refuse(message: String, status: Int): Grant.Denied {
        val problem = ServiceProblem(ProblemKind.CredentialRefused, message, status)
        refused = problem
        return Grant.Denied(problem)
    }

    /** A request sent with [value] was answered 401: that session is gone. */
    fun rejected(value: String) = lock.withLock { if (token == value) token = null }

    /** Ends the session at the service. True when a DELETE was answered 2xx; the token is forgotten either way. */
    fun logout(): Boolean = lock.withLock {
        val live = token ?: return false
        token = null
        val path = auth.logoutPath ?: return false
        try { send(WireRequest(WireMethod.DELETE, path, headers = listOf(login.sendAs to live), carriesCredential = true)).status in 200..299 }
        catch (_: ServiceWireException) { false }
    }

    private companion object {
        const val EXPIRY_MARGIN = 5L
        const val MAX_TOKEN = 512
        const val MAX_VALIDITY = 7L * 24 * 3600
    }
}
