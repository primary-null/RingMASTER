package local.ringleader.core.integrations

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import local.ringleader.core.GuestHints
import local.ringleader.core.SecretVault
import local.ringleader.core.VaultStatus
import local.ringleader.core.createOwnerOnlyDirectories
import local.ringleader.core.guessServices
import local.ringleader.core.helperScriptSlug
import local.ringleader.core.profileFile
import local.ringleader.core.writeOwnerOnlyText

/** The guest a link belongs to: the same identity as a pin (kind + vmid + PDM scope); the node is not stored. */
data class GuestRef(val kind: String, val vmid: Int, val scope: String = "")

/** How a link is trusted. Never "trust anything". */
sealed interface LinkTrust {
    data object NormalTls : LinkTrust
    data class Pinned(val sha256: String) : LinkTrust
    /** Plain http; [ack] is set once the owner acknowledged sending the credential over it. */
    data class PlainHttp(val ack: PlainHttpAck?) : LinkTrust

    fun wire(): WireTrust = when (this) { NormalTls -> WireTrust.NormalTls; is Pinned -> WireTrust.Pinned(sha256); is PlainHttp -> WireTrust.PlainHttp(ack) }
}

/**
 * A saved link. Non-secret only: no credential, no query string, no user-info. [identity] holds the credential's non-secret
 * fields (a user name), as a profile holds its token id. [remembered] says the secret is in the OS password safe.
 */
data class ServiceLink(
    val id: String,
    val templateId: String,
    val variantId: String,
    val name: String,
    val origin: ServiceOrigin,
    val guest: GuestRef?,
    val identity: Map<String, String>,
    val params: Map<String, List<String>>,
    val trust: LinkTrust,
    val remembered: Boolean,
    /** The per-link kill switch: false means paused. */
    val polling: Boolean,
) {
    /** What a credential is bound to: changing any of these forgets a remembered secret and drops the session. */
    fun sameCredentialScope(other: ServiceLink) = templateId == other.templateId && origin == other.origin && trust.scopeKey() == other.trust.scopeKey()
}

private fun LinkTrust.scopeKey(): String = when (this) {
    LinkTrust.NormalTls -> "normal"
    is LinkTrust.Pinned -> "pin:" + sha256.lowercase()
    is LinkTrust.PlainHttp -> "plain:" + (ack?.endpoint ?: "")
}

/** What the link dialog hands in. [variantId] comes from a passed test. */
data class LinkDraft(
    val templateId: String,
    val variantId: String?,
    val name: String,
    val origin: String,
    val guest: GuestRef?,
    val trust: LinkTrust,
    val identity: Map<String, String> = emptyMap(),
    val params: Map<String, List<String>> = emptyMap(),
)

/**
 * `services.json` beside `pins.json`: the links and the ids whose secret is remembered, nothing else. Written atomically and
 * owner-only. A missing file is empty. A corrupt file, an entry this version cannot read, or a newer version blocks saving and
 * is left untouched ("written by a newer RingMASTER" is said once in [blockedReason]).
 */
class ServiceLinkStore(private val file: Path? = defaultFile()) {
    private var memory: List<ServiceLink> = emptyList()
    var blockedReason: String? = null
        private set

    fun load(): List<ServiceLink> {
        val path = file ?: return memory
        blockedReason = null
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        if (Files.isSymbolicLink(path)) return block("services.json is a link to another file and is left untouched.")
        val bytes = try { Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_BYTES + 1) } } catch (_: Exception) { return block(UNREADABLE) }
        if (bytes.size > MAX_BYTES) return block(UNREADABLE)
        val root = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return block(UNREADABLE)
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: return block(UNREADABLE)
        if (version > VERSION) return block("services.json was written by a newer RingMASTER and is left untouched.")
        if (version != VERSION) return block(UNREADABLE)
        val remembered = (root["remembered"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
        val links = (root["links"] as? JsonArray ?: return block(UNREADABLE)).map { decode(it as? JsonObject, remembered) ?: return block(UNREADABLE) }
        return links
    }

    private fun block(reason: String): List<ServiceLink> { blockedReason = reason; return emptyList() }

    /** Writes [links]; refused (with the reason) while the file is blocked. */
    fun save(links: List<ServiceLink>) {
        blockedReason?.let { throw IllegalStateException(it) }
        val path = file ?: run { memory = links.toList(); return }
        if (Files.isSymbolicLink(path)) throw IllegalStateException("services.json is a link to another file and is left untouched.")
        val directory = path.parent ?: throw IllegalStateException("services.json has no folder.")
        createOwnerOnlyDirectories(directory)
        val temp = Files.createTempFile(directory, ".services-", ".tmp")
        try {
            writeOwnerOnlyText(temp, encode(links))
            FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            throw IllegalStateException("Linked services could not be saved safely.")
        } finally { runCatching { Files.deleteIfExists(temp) } }
    }

    companion object {
        const val VERSION = 1
        private const val MAX_BYTES = 1024 * 1024
        private const val UNREADABLE = "services.json could not be read and is left untouched."
        fun defaultFile(): Path = profileFile().resolveSibling("services.json")

        internal fun encode(links: List<ServiceLink>): String = buildJsonObject {
            put("version", VERSION)
            put("links", buildJsonArray {
                for (l in links) add(buildJsonObject {
                    put("id", l.id); put("template", l.templateId); put("variant", l.variantId); put("name", l.name); put("origin", l.origin.canonical)
                    l.guest?.let { g -> put("guest", buildJsonObject { put("kind", g.kind); put("vmid", g.vmid); put("scope", g.scope) }) }
                    put("identity", JsonObject(l.identity.mapValues { JsonPrimitive(it.value) }))
                    put("params", JsonObject(l.params.mapValues { JsonArray(it.value.map(::JsonPrimitive)) }))
                    put("trust", when (val t = l.trust) {
                        LinkTrust.NormalTls -> buildJsonObject { put("kind", "normal") }
                        is LinkTrust.Pinned -> buildJsonObject { put("kind", "pin"); put("sha256", t.sha256) }
                        is LinkTrust.PlainHttp -> buildJsonObject {
                            put("kind", "plain")
                            t.ack?.let { a -> put("ack", buildJsonObject { put("endpoint", a.endpoint); put("at", a.acknowledgedAt.toString()); put("credential", a.credential); put("power", a.power) }) }
                        }
                    })
                    put("polling", l.polling)
                })
            })
            put("remembered", JsonArray(links.filter { it.remembered }.map { JsonPrimitive(it.id) }))
        }.toString()

        private fun decode(o: JsonObject?, remembered: Set<String>): ServiceLink? {
            o ?: return null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = s("id")?.takeIf { ID.matches(it) } ?: return null
            val origin = s("origin")?.let { ServiceOrigin.parse(it).getOrNull() } ?: return null
            val guest = (o["guest"] as? JsonObject)?.let { g ->
                GuestRef((g["kind"] as? JsonPrimitive)?.content ?: return null, (g["vmid"] as? JsonPrimitive)?.intOrNull ?: return null, (g["scope"] as? JsonPrimitive)?.content ?: "")
            }
            val identity = (o["identity"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content ?: return null }.orEmpty()
            val params = (o["params"] as? JsonObject)?.mapValues { (it.value as? JsonArray)?.map { v -> (v as? JsonPrimitive)?.content ?: return null } ?: return null }.orEmpty()
            val t = o["trust"] as? JsonObject ?: return null
            val trust = when ((t["kind"] as? JsonPrimitive)?.content) {
                "normal" -> LinkTrust.NormalTls
                "pin" -> LinkTrust.Pinned((t["sha256"] as? JsonPrimitive)?.content?.takeIf { PIN.matches(it) } ?: return null)
                "plain" -> LinkTrust.PlainHttp((t["ack"] as? JsonObject)?.let { a ->
                    PlainHttpAck((a["endpoint"] as? JsonPrimitive)?.content ?: return null,
                        runCatching { Instant.parse((a["at"] as JsonPrimitive).content) }.getOrNull() ?: return null,
                        (a["credential"] as? JsonPrimitive)?.content.orEmpty(), (a["power"] as? JsonPrimitive)?.content.orEmpty())
                })
                else -> return null
            }
            if (origin.secure == (trust is LinkTrust.PlainHttp)) return null
            return ServiceLink(id, s("template") ?: return null, s("variant") ?: return null, s("name") ?: return null, origin, guest, identity, params, trust,
                id in remembered, (o["polling"] as? JsonPrimitive)?.booleanOrNull ?: true)
        }

        private val ID = Regex("[A-Za-z0-9-]{1,64}")
        private val PIN = Regex("[0-9a-fA-F]{64}")
    }
}

/** The OS password safe for links: the `service:<link id>` namespace, which cannot collide with a profile id. */
class ServiceVault(private val inner: SecretVault) {
    val status: VaultStatus get() = runCatching { inner.status() }.getOrElse { VaultStatus.Unavailable("The password safe could not be used.") }
    fun key(linkId: String) = "service:$linkId"
    fun store(link: ServiceLink, template: ServiceTemplate, credential: ServiceCredential) =
        inner.store(key(link.id), template.variant(link.variantId)?.auth?.ask ?: template.name, "RingMASTER service \u00b7 ${template.name} \u00b7 ${link.name}", credential.secret())
    fun read(linkId: String): CharArray? = inner.read(key(linkId))
    fun forget(linkId: String) = inner.forget(key(linkId))
}

/** Something the scan or the probe found answering on a guest. */
data class FoundEndpoint(val port: Int, val secure: Boolean, val catalogueId: String? = null)

/** A suggested link for a guest, with what linking it would mean. */
data class LinkProposal(
    val templateId: String, val templateName: String, val origin: String, val guest: GuestRef?, val reason: String, val score: Int,
    val ask: String, val power: String, val fullPower: Boolean, val readOnly: String?,
    /** The credential would travel over plain http: the acknowledgement step applies. */
    val plainHttp: Boolean,
)

/** The outcome of Test: either a first panel, or the precise reason with its class. */
data class LinkTest(val ok: Boolean, val variantId: String?, val problem: ServiceProblem?, val state: ServicePanelState?, val unencrypted: UnencryptedMark?)

/**
 * The linked services of this computer: propose, test, save, unlink, list, and the live panels. Credentials stay in memory
 * unless the owner asks to remember one; then it goes to the OS password safe only after the link's first successful read,
 * and it is forgotten when the link's address, template or trust changes. Nothing secret is ever written to `services.json`.
 * In browser-hosted mode pass a `ForgetOnlyVault`: Remember is then unavailable and secrets stay in memory.
 */
class ServiceLinks(
    private val store: ServiceLinkStore,
    vault: SecretVault,
    private val sender: ServiceSender,
    private val scope: CoroutineScope,
    private val templates: (String) -> ServiceTemplate? = ServiceTemplates::byId,
    private val catalogue: List<ServiceTemplate> = emptyList(),
    private val clock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Origins of saved Proxmox connections: a link there is refused, so a Proxmox credential is never involved. */
    private val proxmoxOrigins: () -> Set<String> = { emptySet() },
) {
    private val vault = ServiceVault(vault)
    private val lock = Any()
    private var links: List<ServiceLink> = store.load()
    private val credentials = HashMap<String, ServiceCredential>()
    private val pendingRemember = HashSet<String>()
    private val runners = HashMap<String, ServiceRunner>()

    val blockedReason: String? get() = store.blockedReason
    val vaultStatus: VaultStatus get() = vault.status

    fun list(): List<ServiceLink> = synchronized(lock) { links.toList() }
    fun link(id: String) = synchronized(lock) { links.firstOrNull { it.id == id } }

    /** Suggestions for a guest from its Helper-Scripts notes, name and tags, and from what answered on it. */
    fun propose(hints: GuestHints, guest: GuestRef?, hosts: List<String>, found: List<FoundEndpoint> = emptyList()): List<LinkProposal> {
        val host = hosts.firstOrNull()?.let { ServiceOrigin.canonicalHost(it) } ?: return emptyList()
        val all = catalogue.ifEmpty { ServiceTemplates.all }
        val slug = helperScriptSlug(hints.description)
        val scored = LinkedHashMap<String, Pair<Int, String>>()
        for (g in guessServices(hints, limit = 8)) for (t in all.filter { g.service.id in it.detect.catalogue }) scored.merge(t.id, g.score to g.why) { a, b -> if (a.first >= b.first) a else b }
        if (slug != null) for (t in all.filter { slug in it.detect.helperScripts }) scored.merge(t.id, 100 to "The notes link to its helper script.") { a, b -> if (a.first >= b.first) a else b }
        for (f in found) for (t in all.filter { f.catalogueId != null && f.catalogueId in it.detect.catalogue })
            scored.merge(t.id, 95 to "It answered on port ${f.port}.") { a, b -> if (a.first >= b.first) a else b }
        return scored.mapNotNull { (id, why) ->
            val t = all.firstOrNull { it.id == id } ?: return@mapNotNull null
            val readable = t.variants.firstOrNull { it.unsupported == null } ?: return@mapNotNull null
            // An https endpoint that answered comes first; otherwise the template's first port.
            val port = t.detect.ports.firstOrNull { p -> p.secure && found.any { it.port == p.port && it.secure } }
                ?: t.detect.ports.firstOrNull { p -> found.any { it.port == p.port && it.secure == p.secure } } ?: t.detect.ports.first()
            val origin = ServiceOrigin.of(if (port.secure) "https" else "http", host, port.port, port.path.trimEnd('/'))
            LinkProposal(t.id, t.name, origin.canonical, guest, why.second, why.first, readable.auth.ask, readable.auth.power, readable.auth.fullPower,
                readable.auth.readOnly, !port.secure && readable.auth.needsCredential)
        }.sortedByDescending { it.score }
    }

    /** One probe and one read of each required request. Blocking: call it off the UI thread. Never stores anything. */
    fun test(draft: LinkDraft, credential: ServiceCredential?): LinkTest {
        fun fail(kind: ProblemKind, message: String, variant: String? = null) = LinkTest(false, variant, ServiceProblem(kind, message), null, null)
        val template = templates(draft.templateId) ?: return fail(ProblemKind.RequestRefused, "There is no template ${draft.templateId}.")
        val origin = ServiceOrigin.parse(draft.origin).getOrElse { return fail(ProblemKind.RequestRefused, it.message ?: ServiceOrigin.BAD_ADDRESS) }
        if (proxmoxOrigins().any { o -> ServiceOrigin.parse(o).getOrNull()?.sameEndpoint(origin) == true })
            return fail(ProblemKind.ProxmoxAddress, "This is a Proxmox address. Linked services use their own credentials.")
        if (origin.secure == (draft.trust is LinkTrust.PlainHttp)) return fail(ProblemKind.RequestRefused, "The trust does not match the address's scheme.")
        val wire = draft.trust.wire()
        val variant = when (val v = ServiceClient.probe(template, origin, wire, sender, clock, ServiceClient.shortHash("test"))) {
            is ProbeVerdict.NoVariant -> return LinkTest(false, null, v.problem, null, null)
            is ProbeVerdict.Found -> v.variant
        }
        variant.unsupported?.let { return fail(ProblemKind.UnsupportedVersion, it, variant.id) }
        ServiceAuth.problem(variant.auth, credential)?.let { return LinkTest(false, variant.id, it, null, null) }
        val client = ServiceClient(template, variant, origin, wire, credential?.copy(), sender, clock, "test")
        try {
            val snapshots = HashMap<String, RequestSnapshot>()
            var mark: UnencryptedMark? = null
            for (id in variant.panelRequests.filter { variant.requests[it]?.optional == false }.sorted()) {
                val f = client.fetch(id, draft.params)
                if (f.unencrypted != null) mark = f.unencrypted
                if (f.problem != null) return LinkTest(false, variant.id, f.problem, null, mark)
                snapshots[id] = RequestSnapshot(f.response, f.at, f.at, f.status)
            }
            for (id in variant.panelRequests.filter { variant.requests[it]?.optional == true }) {
                val f = client.fetch(id, draft.params)
                snapshots[id] = RequestSnapshot(f.response, if (f.problem == null) f.at else null, f.at, f.status, f.problem)
            }
            val state = ServicePanel.evaluate(template, variant, PanelInput(snapshots, clock(), draft.params, unencrypted = mark)).state
            if (state.status == PanelStatus.ApiChanged) return LinkTest(false, variant.id, state.problem ?: ServicePanel.apiChanged(template, variant), state, mark)
            return LinkTest(true, variant.id, null, state, mark)
        } finally { client.close() } // a test never keeps a session seat
    }

    /** Saves a new link. With [remember], the secret goes to the password safe after the first successful read. */
    fun save(draft: LinkDraft, credential: ServiceCredential?, remember: Boolean): Result<ServiceLink> = runCatching {
        val link = build(UUID.randomUUID().toString(), draft, polling = true)
        synchronized(lock) {
            store.save(links + link)
            links = links + link
            credential?.let { credentials[link.id] = it.copy() }
            if (remember && credential != null && vault.status is VaultStatus.Available) pendingRemember += link.id
        }
        link
    }

    /** Changes a link. A changed address, template or trust forgets the remembered secret and drops the session first. */
    suspend fun update(id: String, draft: LinkDraft, credential: ServiceCredential?, remember: Boolean): Result<ServiceLink> = runCatching {
        val old = link(id) ?: throw IllegalArgumentException("This link is no longer there.")
        val next = build(id, draft, old.polling)
        val rescoped = !old.sameCredentialScope(next)
        stopRunner(id)
        // A new secret or a new scope: the old remembered entry goes.
        val keep = old.remembered && !rescoped && credential == null
        if (old.remembered && !keep) runCatching { vault.forget(id) }
        val saved = next.copy(remembered = keep)
        synchronized(lock) {
            store.save(links.map { if (it.id == id) saved else it })
            links = links.map { if (it.id == id) saved else it }
            if (rescoped) credentials.remove(id)?.close()
            credential?.let { credentials.put(id, it.copy())?.close() }
            pendingRemember.remove(id)
            if (remember && credential != null && vault.status is VaultStatus.Available) pendingRemember += id
        }
        saved
    }

    /** Removes the link: its session is logged out, its remembered secret forgotten, its schedule stopped. */
    suspend fun unlink(id: String): Result<Unit> = runCatching {
        stopRunner(id)
        synchronized(lock) {
            store.save(links.filterNot { it.id == id })
            links = links.filterNot { it.id == id }
            credentials.remove(id)?.close()
            pendingRemember.remove(id)
        }
        runCatching { vault.forget(id) }
    }

    /** Gives a link its secret for this run (and, with [remember], for later runs after the next successful read). */
    fun enterCredential(id: String, credential: ServiceCredential, remember: Boolean) {
        synchronized(lock) {
            credentials.put(id, credential.copy())?.close()
            if (remember && vault.status is VaultStatus.Available) pendingRemember += id
            runners.remove(id)
        }?.let { r -> scope.launch(io) { r.stop() } }
    }

    /** The live panel; the runner starts on first use and polls only while [setInterest] says something shows it. */
    fun panel(id: String): StateFlow<ServicePanelState>? = runner(id)?.state

    fun setInterest(id: String, interest: Interest) { runner(id)?.setInterest(interest) }

    /** The per-link kill switch, kept in services.json. */
    fun setPolling(id: String, on: Boolean): Result<Unit> = runCatching {
        synchronized(lock) {
            val l = links.firstOrNull { it.id == id } ?: return@runCatching
            val next = links.map { if (it.id == id) l.copy(polling = on) else it }
            store.save(next); links = next
        }
        runners[id]?.let { if (on) it.resume() else it.pause() }
    }

    /** Closes every session (app quit). */
    suspend fun closeAll() { synchronized(lock) { runners.keys.toList() }.forEach { stopRunner(it) } }

    private fun runner(id: String): ServiceRunner? {
        synchronized(lock) { runners[id]?.let { return it } }
        val link = link(id) ?: return null
        val template = templates(link.templateId) ?: return null
        val variant = template.variant(link.variantId) ?: return null
        val credential = synchronized(lock) { credentials[id] } ?: if (link.remembered) readRemembered(id, variant)?.also { c -> synchronized(lock) { credentials[id] = c } } else null
        val client = ServiceClient(template, variant, link.origin, link.trust.wire(), credential?.copy(), sender, clock, id)
        val runner = ServiceRunner(id, template, variant, client.asFetcher(), link.params, scope, clock, io, paused = !link.polling,
            onFirstSuccess = { rememberNow(id) })
        synchronized(lock) { runners[id]?.let { return it }; runners[id] = runner }
        return runner.start()
    }

    private fun readRemembered(id: String, @Suppress("UNUSED_PARAMETER") variant: TemplateVariant): ServiceCredential? {
        val link = link(id) ?: return null
        val chars = runCatching { vault.read(id) }.getOrNull() ?: return null
        return try { ServiceCredential(link.identity, chars) } finally { chars.fill(Char(0)) }
    }

    private fun rememberNow(id: String) {
        val (link, credential) = synchronized(lock) {
            if (!pendingRemember.remove(id)) return
            (links.firstOrNull { it.id == id } ?: return) to (credentials[id] ?: return)
        }
        val template = templates(link.templateId) ?: return
        if (runCatching { vault.store(link, template, credential) }.isFailure) return
        synchronized(lock) {
            val next = links.map { if (it.id == id) it.copy(remembered = true) else it }
            runCatching { store.save(next) }.onSuccess { links = next }
        }
    }

    private suspend fun stopRunner(id: String) { synchronized(lock) { runners.remove(id) }?.stop() }

    private fun build(id: String, draft: LinkDraft, polling: Boolean): ServiceLink {
        val template = templates(draft.templateId) ?: throw IllegalArgumentException("There is no template ${draft.templateId}.")
        val variantId = draft.variantId ?: throw IllegalArgumentException("Test the link before saving it.")
        val variant = template.variant(variantId)?.takeIf { it.unsupported == null } ?: throw IllegalArgumentException("That version of ${template.name} cannot be linked.")
        val origin = ServiceOrigin.parseOrThrow(draft.origin)
        if (origin.secure == (draft.trust is LinkTrust.PlainHttp)) throw IllegalArgumentException("The trust does not match the address's scheme.")
        val name = draft.name.trim().ifEmpty { template.name }
        require(name.length <= 40 && name.none { it.isISOControl() }) { "Use a name of at most 40 printable characters." }
        val secretIds = variant.auth.fields.filter { it.secret }.map { it.id }.toSet()
        require(draft.identity.keys.none { it in secretIds }) { "A secret is never stored with the link." }
        require(draft.identity.keys.all { k -> variant.auth.fields.any { it.id == k && !it.secret } }) { "An unknown credential field." }
        for ((pid, values) in draft.params) {
            val spec = variant.params.firstOrNull { it.id == pid } ?: throw IllegalArgumentException("An unknown setting.")
            require(values.size <= spec.max && values.all { spec.pattern.matches(it) }) { "${spec.label} holds a value this template does not accept." }
        }
        val trust = (draft.trust as? LinkTrust.PlainHttp)?.ack?.let { if (it.covers(origin)) draft.trust else LinkTrust.PlainHttp(null) } ?: draft.trust
        return ServiceLink(id, template.id, variant.id, name, origin, draft.guest, draft.identity, draft.params, trust, remembered = false, polling = polling)
    }
}
