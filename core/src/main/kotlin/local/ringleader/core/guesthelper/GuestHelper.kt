package local.ringleader.core.guesthelper

import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import local.ringleader.core.RadioScripts

/*
 * The RingMASTER Engine as the app sees it. "RingMASTER Engine" is what the user reads; in code it is the guest helper,
 * because EngineSessions.kt and the service templates already use the word engine. The helper's source is
 * native/ringmaster-engine; its protocol is described in native/ringmaster-engine/src/server.rs.
 */

/** The helper version this app ships and offers to install. Kept equal to native/ringmaster-engine/Cargo.toml (a test checks). */
const val GUEST_HELPER_VERSION = "0.1.0"
/** The protocol this app reads. A helper with another api is refused, never guessed at. */
const val GUEST_HELPER_API = 1
const val GUEST_HELPER_PORT = 8696
const val GUEST_HELPER_NAME = "RingMASTER Engine"

/**
 * The probe the helper runs is the app's own ([RadioScripts.probe]), built into the helper at build time, so the helper
 * reports its SHA-256 and the app can tell whether both carry the same text.
 */
object GuestHelperScripts {
    /** SHA-256 of [RadioScripts.probe], as the helper reports it in `probe_sha256`. */
    val probeSha256: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256").digest(RadioScripts.probe.toByteArray(Charsets.UTF_8))
        digest.joinToString("") { "%02x".format(it) }
    }
}

/** What the helper's `/v1/whoami` says. Every text field is bounded and printable; [sees] counts what it can read. */
data class GuestHelperIdentity(
    val version: String,
    val api: Int,
    val probeSha256: String,
    val host: String,
    val osId: String,
    val osVersion: String,
    val osName: String,
    /** `vm`, `container`, `node`, `machine` or `unknown`. */
    val kind: String,
    val kindDetail: String,
    val arch: String,
    val uid: Long,
    val root: Boolean,
    val sees: GuestHelperSees,
    val startedEpochSecond: Long,
    val nowEpochSecond: Long,
)

data class GuestHelperSees(
    val bluetoothControllers: Int,
    val wirelessInterfaces: Int,
    val systemBus: Boolean,
    val rfkill: Boolean,
    val bluetoothctl: Boolean,
    val iw: Boolean,
    val nmcli: Boolean,
)

enum class GuestHelperMode(val wire: String) { Probe("probe"), BluetoothScan("bt-scan"), WifiScan("wifi-scan") }

/**
 * One run of a radio script. [output] is the script's text, unchanged: hand it to the radio parser (RadioScript.kt's
 * frame reader), the same one the guest agent path uses. [exit] is null when the script was stopped.
 */
data class GuestHelperRun(
    val mode: GuestHelperMode,
    val seconds: Int,
    val exit: Int?,
    val timedOut: Boolean,
    val truncated: Boolean,
    val millis: Long,
    val probeSha256: String,
    val output: String,
)

/** How an installed helper compares with the one this app ships. */
enum class GuestHelperAge { Current, Older, Newer, UnknownApi }

/** [Current] also needs the same probe text: a helper of this version built with another probe is older in what it reports. */
fun guestHelperAge(
    identity: GuestHelperIdentity,
    shipped: String = GUEST_HELPER_VERSION,
    probeSha256: String = GuestHelperScripts.probeSha256,
): GuestHelperAge {
    if (identity.api != GUEST_HELPER_API) return GuestHelperAge.UnknownApi
    val order = compareVersions(identity.version, shipped)
    return when {
        order < 0 -> GuestHelperAge.Older
        order > 0 -> GuestHelperAge.Newer
        identity.probeSha256 != probeSha256 -> GuestHelperAge.Older
        else -> GuestHelperAge.Current
    }
}

/** The age of a helper known only by the version saved with its pairing (before it answered in this run). */
fun guestHelperAgeOf(version: String): GuestHelperAge {
    val order = compareVersions(version, GUEST_HELPER_VERSION)
    return if (order < 0) GuestHelperAge.Older else if (order > 0) GuestHelperAge.Newer else GuestHelperAge.Current
}

/** One plain sentence for [age], or null when there is nothing to say. */
fun guestHelperAgeSentence(age: GuestHelperAge, installed: String): String? = when (age) {
    GuestHelperAge.Current -> null
    GuestHelperAge.Older -> "The $GUEST_HELPER_NAME here ($installed) is older than this app's ($GUEST_HELPER_VERSION). " +
        "Update it to see everything this app can show."
    GuestHelperAge.Newer -> "The $GUEST_HELPER_NAME here ($installed) is newer than this app. Update RingMASTER to read all of it."
    GuestHelperAge.UnknownApi -> "The $GUEST_HELPER_NAME here ($installed) speaks a version this app cannot read. " +
        "Update RingMASTER or reinstall the Engine."
}

private val VERSION = Regex("[0-9]{1,4}(\\.[0-9]{1,4}){0,3}")

/** Dotted numeric versions; a part that is not a number sorts as 0. */
internal fun compareVersions(a: String, b: String): Int {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

/** Why a helper exchange failed, by class. Each has one plain sentence ([GuestHelperException.message]). */
enum class GuestHelperFailure {
    /** The certificate is not the one paired (or confirmed). */
    CertificateChanged,
    /** The token was not accepted. */
    WrongToken,
    /** Too many wrong tokens from this computer; [GuestHelperException.retryAfterSeconds] says how long. */
    LockedOut,
    /** A scan or probe is already running there. */
    Busy,
    /** The port answered plain http or something that is not TLS. */
    NotTls,
    /** Something answered with TLS, but not as a RingMASTER Engine. */
    NotAnEngine,
    /** The Engine speaks a protocol version this app does not know. */
    UnknownApi,
    TooLarge,
    TooSlow,
    Unreachable,
    /** The answer did not have the fields and limits the protocol gives. */
    Malformed,
    Refused,
}

class GuestHelperException(val failure: GuestHelperFailure, message: String, val retryAfterSeconds: Long? = null, cause: Throwable? = null) :
    Exception(message, cause)

/** Bounds on what is read from a helper: everything it says is the guest's own claim, and the guest may be hostile. */
internal object GuestHelperLimits {
    const val SMALL_ANSWER = 64 * 1024
    /** The helper caps a script's output at 192 KiB; escaped as JSON that can grow, so the answer may be larger. */
    const val RUN_ANSWER = 2 * 1024 * 1024
    const val OUTPUT_CHARS = 192 * 1024
    const val TEXT = 256
}

private fun JsonObject.text(key: String, max: Int = GuestHelperLimits.TEXT, allowEmpty: Boolean = true): String {
    val p = this[key] as? JsonPrimitive ?: malformed(key)
    if (!p.isString) malformed(key)
    val s = p.content
    if (s.length > max || (!allowEmpty && s.isEmpty()) || s.any { it.isISOControl() }) malformed(key)
    return s
}
/** A JSON number or boolean, never a string that looks like one. */
private fun JsonObject.bare(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }
private fun JsonObject.int(key: String): Int = bare(key)?.intOrNull ?: malformed(key)
private fun JsonObject.long(key: String): Long = bare(key)?.longOrNull ?: malformed(key)
private fun JsonObject.bool(key: String): Boolean = bare(key)?.booleanOrNull ?: malformed(key)
private fun malformed(key: String): Nothing =
    throw GuestHelperException(GuestHelperFailure.Malformed, "The $GUEST_HELPER_NAME's answer is not readable ($key).")
private fun notAnEngine(): Nothing =
    throw GuestHelperException(GuestHelperFailure.NotAnEngine, "This port does not answer as a $GUEST_HELPER_NAME.")
private val SHA256 = Regex("[0-9a-f]{64}")

internal fun parseObject(text: String): JsonObject {
    val element = try { Json.parseToJsonElement(text) } catch (_: SerializationException) { malformed("not JSON") }
    return element as? JsonObject ?: malformed("not an object")
}

/** The unauthenticated `/v1/ping`: an Engine and its api, nothing else. Anything else on the port is not an Engine. */
internal fun parsePing(text: String): Int {
    val o = try { parseObject(text) } catch (_: GuestHelperException) { notAnEngine() }
    if ((o["product"] as? JsonPrimitive)?.content != "ringmaster-engine") notAnEngine()
    return o.int("api")
}

internal fun parseIdentity(text: String): GuestHelperIdentity {
    val o = parseObject(text)
    if (o.text("product") != "ringmaster-engine") notAnEngine()
    val api = o.int("api")
    if (api != GUEST_HELPER_API) {
        val sentence = guestHelperAgeSentence(GuestHelperAge.UnknownApi, o.text("version", 32))!!
        throw GuestHelperException(GuestHelperFailure.UnknownApi, sentence)
    }
    val version = o.text("version", 32)
    if (!VERSION.matches(version)) malformed("version")
    val sha = o.text("probe_sha256", 64)
    if (!SHA256.matches(sha)) malformed("probe_sha256")
    val s = o["sees"] as? JsonObject ?: malformed("sees")
    val sees = GuestHelperSees(
        bluetoothControllers = s.int("bluetooth_controllers").coerceIn(0, 1024),
        wirelessInterfaces = s.int("wireless_interfaces").coerceIn(0, 1024),
        systemBus = s.bool("system_bus"),
        rfkill = s.bool("rfkill"),
        bluetoothctl = s.bool("bluetoothctl"),
        iw = s.bool("iw"),
        nmcli = s.bool("nmcli"),
    )
    val kind = o.text("kind", 16).takeIf { it in setOf("vm", "container", "node", "machine", "unknown") } ?: "unknown"
    return GuestHelperIdentity(
        version = version, api = api, probeSha256 = sha,
        host = o.text("host", 253), osId = o.text("os_id", 80), osVersion = o.text("os_version", 80), osName = o.text("os_name", 80),
        kind = kind, kindDetail = o.text("kind_detail", 80), arch = o.text("arch", 32),
        uid = o.long("uid"), root = o.bool("root"), sees = sees,
        startedEpochSecond = o.long("started"), nowEpochSecond = o.long("now"),
    )
}

internal fun parseRun(text: String, expected: GuestHelperMode): GuestHelperRun {
    val o = parseObject(text)
    if (o.int("api") != GUEST_HELPER_API) {
        throw GuestHelperException(GuestHelperFailure.UnknownApi, "The $GUEST_HELPER_NAME answered in a version this app cannot read.")
    }
    if (o.text("mode", 16) != expected.wire) malformed("mode")
    val exit: JsonElement = o["exit"] ?: malformed("exit")
    val code = if (exit is JsonNull) null else o.bare("exit")?.intOrNull ?: malformed("exit")
    val sha = o.text("probe_sha256", 64)
    if (!SHA256.matches(sha)) malformed("probe_sha256")
    val p = o["output"] as? JsonPrimitive ?: malformed("output")
    if (!p.isString || p.content.length > GuestHelperLimits.OUTPUT_CHARS) malformed("output")
    return GuestHelperRun(expected, o.int("seconds"), code, o.bool("timed_out"), o.bool("truncated"), o.long("ms"), sha, p.content)
}
