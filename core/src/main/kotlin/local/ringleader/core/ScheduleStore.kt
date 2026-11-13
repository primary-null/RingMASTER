package local.ringleader.core

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Everything the scheduler persists. No secret, token or password is ever part of it. */
data class ScheduleDocument(
    val plans: List<SchedulePlan> = emptyList(),
    val access: List<BackgroundAccess> = emptyList(),
    val runs: List<ScheduleRun> = emptyList(),
)

/**
 * [readOnly] is a newer file or one that changed under us: execution and writes stop until the user resolves it.
 * [unreadable] is a malformed file; it is never overwritten implicitly, only set aside by an explicit user action.
 */
class ScheduleStoreException(message: String, val readOnly: Boolean = false, val unreadable: Boolean = false) : IllegalStateException(message)

const val SCHEDULE_STORE_VERSION = 1
internal const val MAX_SCHEDULE_BYTES = 1024 * 1024

/**
 * `schedules.json` beside `profiles.json`. Strict UTF-8 JSON, duplicate and unknown keys rejected, bounded size and counts.
 * Writes are owner-only same-directory temporary files moved atomically; there is no non-atomic fallback. A file that
 * changed on disk since it was read is never overwritten (fail closed).
 */
class ScheduleStore(
    val file: Path,
    private val replace: (Path, Path) -> Unit = { temporary, target ->
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { throw ScheduleStoreException("Schedules could not be saved safely. The previous file is unchanged.") }
    },
) {
    private var diskDigest: String? = null
    private var loaded = false
    private var blocked: ScheduleStoreException? = null

    fun load(): ScheduleDocument {
        try {
            if (Files.isSymbolicLink(file)) throw ScheduleStoreException("schedules.json is a link and was left unchanged.", readOnly = true)
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                diskDigest = null; loaded = true; blocked = null
                return ScheduleDocument()
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw ScheduleStoreException("schedules.json is not a regular file and was left unchanged.", readOnly = true)
            val bytes = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_SCHEDULE_BYTES + 1) }
            if (bytes.size > MAX_SCHEDULE_BYTES) throw ScheduleStoreException("schedules.json is too large and was left unchanged.", unreadable = true)
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) { throw ScheduleStoreException("schedules.json is not valid UTF-8 and was left unchanged.", unreadable = true) }
            val document = decodeSchedules(text)
            diskDigest = digest(bytes); loaded = true; blocked = null
            return document
        } catch (e: ScheduleStoreException) {
            blocked = e; loaded = true
            throw e
        } catch (_: Exception) {
            val e = ScheduleStoreException("schedules.json could not be read and was left unchanged.", unreadable = true)
            blocked = e; loaded = true
            throw e
        }
    }

    fun problem(): ScheduleStoreException? = blocked

    fun save(document: ScheduleDocument) {
        check(loaded) { "Load schedules before saving." }
        blocked?.let { throw it }
        val payload = encodeSchedules(document).toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_SCHEDULE_BYTES) throw ScheduleStoreException("Schedules are too large to save. The previous file is unchanged.")
        val directory = file.parent ?: throw ScheduleStoreException("The schedule location is invalid.")
        createOwnerOnlyDirectories(directory)
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file)) throw ScheduleStoreException("The schedule location is a link. Nothing was saved.", readOnly = true)
        val current = if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { digest(it.readNBytes(MAX_SCHEDULE_BYTES + 1)) } else null
        if (current != diskDigest) {
            val e = ScheduleStoreException("schedules.json changed outside RingMASTER. Scheduling stopped; reopen RingMASTER to reload it.", readOnly = true)
            blocked = e
            throw e
        }
        var temporary: Path? = null
        var replaced = false
        try {
            val temp = Files.createTempFile(directory, ".schedules-", ".tmp")
            temporary = temp
            grantOwnerOnly(temp)
            Files.write(temp, payload)
            replace(temp, file)
            replaced = true
            temporary = null
            grantOwnerOnly(file)
            diskDigest = digest(payload)
        } catch (e: ScheduleStoreException) { throw e }
        catch (e: Exception) {
            throw ScheduleStoreException(if (replaced)
                "The schedule file was replaced, but securing it failed (${e.javaClass.simpleName}). Scheduling stopped; Quit and relaunch RingMASTER to reload it."
                else "Schedules could not be saved (${e.javaClass.simpleName}). The previous file is unchanged; Quit and relaunch RingMASTER to retry.")
        }
        finally { temporary?.let { runCatching { Files.deleteIfExists(it) } } }
    }

    /** Explicit user action for an unreadable file: move it aside and start empty. A newer file is never moved. */
    fun setAside(now: Instant): Path {
        val problem = blocked ?: throw ScheduleStoreException("schedules.json is readable; nothing was moved.")
        if (!problem.unreadable) throw ScheduleStoreException("Only an unreadable schedules.json can be set aside.")
        val stamp = now.toString().replace(Regex("[^0-9TZ]"), "")
        val target = file.resolveSibling("schedules.unreadable-$stamp.json")
        Files.move(file, target, StandardCopyOption.ATOMIC_MOVE)
        diskDigest = null; blocked = null; loaded = true
        return target
    }

    private fun digest(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

internal fun encodeSchedules(document: ScheduleDocument): String = buildJsonObject {
    put("version", SCHEDULE_STORE_VERSION)
    put("plans", buildJsonArray {
        document.plans.forEach { p ->
            add(buildJsonObject {
                put("id", p.id); put("name", p.name); put("revision", p.revision); put("action", p.action.name)
                put("repeat", buildJsonObject {
                    put("kind", p.repeat.kind.name)
                    if (p.repeat.kind == RepeatKind.Days) put("days", buildJsonArray { p.repeat.days.sorted().forEach { add(JsonPrimitive(it.name)) } })
                    if (p.repeat.kind == RepeatKind.Once) put("date", p.repeat.date.toString())
                })
                put("time", "%02d:%02d".format(p.time.hour, p.time.minute)); put("zone", p.zone.id)
                put("target", buildJsonObject {
                    val t = p.target
                    put("profileId", t.profileId); put("binding", t.binding); put("node", t.node); put("kind", t.kind)
                    put("vmid", t.vmid); put("name", t.name)
                    put("identity", buildJsonObject { put("method", t.identity.method.name); put("digest", t.identity.digest) })
                })
                put("enabled", p.enabled); put("authorizedAt", p.authorizedAt.toString()); put("notBefore", p.notBefore.toString())
                p.lastHandledDue?.let { put("lastHandledDue", it.toString()) }
                p.invalidReason?.let { put("invalidReason", it) }
            })
        }
    })
    put("access", buildJsonArray {
        document.access.forEach { a -> add(buildJsonObject { put("profileId", a.profileId); put("binding", a.binding); put("grantedAt", a.grantedAt.toString()) }) }
    })
    put("runs", buildJsonArray {
        document.runs.forEach { r ->
            add(buildJsonObject {
                put("id", r.id); put("planId", r.planId); put("revision", r.revision); put("due", r.due.toString())
                put("action", r.action.name); put("guest", r.guest); put("phase", r.phase.name); put("detail", r.detail)
                put("updatedAt", r.updatedAt.toString())
                r.upid?.let { put("upid", it) }
                r.node?.let { put("node", it) }
                if (r.missedEarlier > 0) put("missedEarlier", r.missedEarlier)
            })
        }
    })
}.toString() + "\n"

private class Bad : Exception()

internal fun decodeSchedules(text: String): ScheduleDocument {
    val root = try { rejectDuplicateKeys(text); Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
        ?: throw ScheduleStoreException("schedules.json is not valid JSON and was left unchanged.", unreadable = true)
    val version = (root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    if (version != null && version > SCHEDULE_STORE_VERSION) {
        throw ScheduleStoreException("schedules.json was written by a newer RingMASTER. Scheduling is off and the file is left untouched.", readOnly = true)
    }
    try {
        if (version != SCHEDULE_STORE_VERSION || root.keys != setOf("version", "plans", "access", "runs")) throw Bad()
        val plans = array(root["plans"], MAX_SCHEDULES).map(::decodePlan)
        val access = array(root["access"], 256).map { a ->
            val o = obj(a, setOf("profileId", "binding", "grantedAt"))
            BackgroundAccess(str(o, "profileId"), str(o, "binding"), instant(o, "grantedAt"))
        }
        val runs = array(root["runs"], MAX_SCHEDULE_RUNS + 64).map(::decodeRun)
        if (plans.map { it.id }.distinct().size != plans.size || access.map { it.profileId }.distinct().size != access.size ||
            runs.map { it.id }.distinct().size != runs.size) throw Bad()
        return ScheduleDocument(plans, access, runs)
    } catch (_: Exception) {
        throw ScheduleStoreException("schedules.json contains unsupported or invalid data and was left unchanged.", unreadable = true)
    }
}

private fun decodePlan(element: JsonElement): SchedulePlan {
    val o = obj(element, setOf("id", "name", "revision", "action", "repeat", "time", "zone", "target", "enabled", "authorizedAt", "notBefore", "lastHandledDue", "invalidReason"),
        required = setOf("id", "name", "revision", "action", "repeat", "time", "zone", "target", "enabled", "authorizedAt", "notBefore"))
    val r = obj(o["repeat"]!!, setOf("kind", "days", "date"), required = setOf("kind"))
    val kind = RepeatKind.valueOf(str(r, "kind"))
    val repeat = when (kind) {
        RepeatKind.Once -> { if ("days" in r) throw Bad(); ScheduleRepeat(kind, date = LocalDate.parse(str(r, "date"))) }
        RepeatKind.Days -> { if ("date" in r) throw Bad(); ScheduleRepeat(kind, days = array(r["days"], 7).map { DayOfWeek.valueOf(prim(it)) }.toSet()) }
        else -> { if (r.keys != setOf("kind")) throw Bad(); ScheduleRepeat(kind) }
    }
    val t = obj(o["target"]!!, setOf("profileId", "binding", "node", "kind", "vmid", "name", "identity"))
    val i = obj(t["identity"]!!, setOf("method", "digest"))
    val time = parseScheduleTime(str(o, "time")) ?: throw Bad()
    if (str(o, "time") != "%02d:%02d".format(time.hour, time.minute)) throw Bad()
    val zone = parseScheduleZone(str(o, "zone")) ?: throw Bad()
    return SchedulePlan(
        id = str(o, "id"), name = str(o, "name"), revision = int(o, "revision"), action = ScheduleAction.valueOf(str(o, "action")),
        repeat = repeat, time = time, zone = zone,
        target = ScheduleTarget(str(t, "profileId"), str(t, "binding"), str(t, "node"), str(t, "kind"), int(t, "vmid"), str(t, "name"),
            GuestIdentity(IdentityMethod.valueOf(str(i, "method")), str(i, "digest"))),
        enabled = bool(o, "enabled"), authorizedAt = instant(o, "authorizedAt"), notBefore = instant(o, "notBefore"),
        lastHandledDue = if ("lastHandledDue" in o) instant(o, "lastHandledDue") else null,
        invalidReason = if ("invalidReason" in o) str(o, "invalidReason") else null,
    )
}

private fun decodeRun(element: JsonElement): ScheduleRun {
    val o = obj(element, setOf("id", "planId", "revision", "due", "action", "guest", "phase", "detail", "updatedAt", "upid", "node", "missedEarlier"),
        required = setOf("id", "planId", "revision", "due", "action", "guest", "phase", "detail", "updatedAt"))
    return ScheduleRun(
        id = str(o, "id"), planId = str(o, "planId"), revision = int(o, "revision"), due = instant(o, "due"),
        action = ScheduleAction.valueOf(str(o, "action")), guest = str(o, "guest"), phase = RunPhase.valueOf(str(o, "phase")),
        detail = str(o, "detail"), updatedAt = instant(o, "updatedAt"),
        upid = if ("upid" in o) str(o, "upid") else null, node = if ("node" in o) str(o, "node") else null,
        missedEarlier = if ("missedEarlier" in o) int(o, "missedEarlier") else 0,
    ).also { if (java.util.UUID.fromString(it.planId).toString() != it.planId) throw Bad() }
}

private fun array(element: JsonElement?, max: Int): JsonArray = (element as? JsonArray)?.takeIf { it.size <= max } ?: throw Bad()
private fun obj(element: JsonElement, allowed: Set<String>, required: Set<String> = allowed): JsonObject {
    val o = element as? JsonObject ?: throw Bad()
    if (!allowed.containsAll(o.keys) || !o.keys.containsAll(required)) throw Bad()
    return o
}
private fun prim(element: JsonElement?): String {
    val p = element as? JsonPrimitive ?: throw Bad()
    if (!p.isString || p is JsonNull) throw Bad()
    return p.content
}
private fun str(o: JsonObject, key: String): String = prim(o[key])
private fun int(o: JsonObject, key: String): Int {
    val p = o[key] as? JsonPrimitive ?: throw Bad()
    if (p.isString) throw Bad()
    val value = p.longOrNull ?: throw Bad()
    if (value !in Int.MIN_VALUE..Int.MAX_VALUE) throw Bad()
    return value.toInt()
}
private fun bool(o: JsonObject, key: String): Boolean {
    val p = o[key] as? JsonPrimitive ?: throw Bad()
    if (p.isString) throw Bad()
    return p.booleanOrNull ?: throw Bad()
}
private fun instant(o: JsonObject, key: String): Instant = Instant.parse(str(o, key))
