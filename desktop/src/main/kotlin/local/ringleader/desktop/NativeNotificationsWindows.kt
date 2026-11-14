package local.ringleader.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.WString
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * Windows: real app notifications through the bundled helper `ringmaster-notify.exe` (Rust, native/ringmaster-notify),
 * which calls WinRT ToastNotificationManager under RingMASTER's AppUserModelID. The helper stays running so the
 * notifications it showed can report a click back. Requests are JSON lines built with kotlinx.serialization, so
 * no text is ever interpolated into a command line or into XML; the helper places text with the XML DOM.
 */

/** Paths the Windows backend needs: the helper and the installed launcher its Start menu shortcut points at. */
internal data class WindowsNotifyPaths(val helper: File, val launcher: File)

internal fun bundledNotifyHelper(): File? =
    System.getProperty("compose.application.resources.dir")?.let { File(it, "ringmaster-notify.exe") }?.takeIf { it.isFile }

/** jpackage's launcher sets jpackage.app-path to its own exe. Unset in development runs. */
internal fun installedLauncher(): File? =
    System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile && it.isAbsolute }

internal sealed interface WindowsPathsResult {
    data class Found(val paths: WindowsNotifyPaths) : WindowsPathsResult
    data class Missing(val reason: String) : WindowsPathsResult
}

internal fun defaultWindowsNotifyPaths(): WindowsPathsResult {
    val helper = bundledNotifyHelper() ?: return WindowsPathsResult.Missing("This build has no Windows notification helper.")
    val launcher = installedLauncher()
        ?: return WindowsPathsResult.Missing("Windows notifications need the installed RingMASTER; its Start menu shortcut gives it an identity.")
    return WindowsPathsResult.Found(WindowsNotifyPaths(helper, launcher))
}

internal fun startNotifyHelper(paths: WindowsNotifyPaths, appId: String): NotifyHelperLink {
    // Arguments go straight to CreateProcess as an array; nothing passes through a shell. The JDK starts it without
    // a console window. Stderr is discarded so a chatty helper can never block on a full pipe.
    val process = ProcessBuilder(paths.helper.path, "--aumid", appId, "--exe", paths.launcher.path)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    return NotifyHelperLink(process.inputStream, process.outputStream, kill = {
        runCatching { process.outputStream.close() }
        if (!runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }.getOrDefault(false)) process.destroyForcibly()
    })
}

/**
 * JSON-line channel to the helper. One reader thread; requests are matched to results by `seq`; anything else is
 * an event. Lines longer than [MAX_LINE] bytes are skipped whole.
 */
internal class NotifyHelperLink(
    input: InputStream,
    private val output: OutputStream,
    private val kill: () -> Unit = {},
) : AutoCloseable {
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject?>>()
    private val seq = AtomicLong(1)
    private val hello = CompletableFuture<String?>()
    @Volatile var onEvent: (JsonObject) -> Unit = {}
    @Volatile var alive = true
        private set

    init {
        Thread({ readLoop(BufferedInputStream(input)) }, "native-notify-helper").apply { isDaemon = true; start() }
    }

    /** The helper's version from its first line, or null when it did not start in time. */
    fun awaitHello(timeoutMillis: Long): String? =
        runCatching { hello.get(timeoutMillis, TimeUnit.MILLISECONDS) }.getOrNull()

    /** Sends one request and waits for its result, or null on timeout or a dead helper. */
    fun request(op: String, fields: Map<String, JsonElement> = emptyMap(), timeoutMillis: Long): JsonObject? {
        if (!alive) return null
        val id = seq.getAndIncrement()
        val answer = CompletableFuture<JsonObject?>()
        pending[id] = answer
        val line = buildJsonObject {
            put("op", JsonPrimitive(op))
            put("seq", JsonPrimitive(id))
            fields.forEach { (k, v) -> put(k, v) }
        }.toString()
        return try {
            synchronized(output) {
                output.write((line + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
            }
            answer.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: IOException) {
            alive = false
            null
        } catch (_: Exception) {
            null
        } finally {
            pending.remove(id)
        }
    }

    override fun close() {
        alive = false
        runCatching { kill() }
        pending.values.forEach { it.complete(null) }
    }

    private fun readLoop(input: BufferedInputStream) {
        try {
            while (true) {
                val line = readBoundedLine(input) ?: break
                if (line.isEmpty()) continue
                val obj = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                when (obj.str("t")) {
                    "hello" -> hello.complete(obj.str("v").orEmpty())
                    "result" -> obj["seq"]?.jsonPrimitive?.longOrNull?.let { pending[it]?.complete(obj) }
                    else -> runCatching { onEvent(obj) }
                }
            }
        } catch (_: IOException) {
        } finally {
            alive = false
            hello.complete(null)
            pending.values.forEach { it.complete(null) }
        }
    }

    companion object {
        const val MAX_LINE = 16 * 1024

        /** One UTF-8 line without the newline; "" for a skipped overlong line; null at end of input. */
        internal fun readBoundedLine(input: InputStream): String? {
            val bytes = java.io.ByteArrayOutputStream()
            var overflow = false
            while (true) {
                val b = input.read()
                if (b < 0) return if (bytes.size() == 0 && !overflow) null else if (overflow) "" else bytes.toString(Charsets.UTF_8)
                if (b == '\n'.code) return if (overflow) "" else bytes.toString(Charsets.UTF_8).trimEnd('\r')
                if (!overflow) {
                    if (bytes.size() >= MAX_LINE) { overflow = true; bytes.reset() } else bytes.write(b)
                }
            }
        }
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Windows backend. All calls arrive on the notifier's worker thread. */
internal class WindowsToastBackend(
    private val config: NativeNotifierConfig,
    private val locate: () -> WindowsPathsResult = ::defaultWindowsNotifyPaths,
    private val start: (WindowsNotifyPaths, String) -> NotifyHelperLink = ::startNotifyHelper,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : NativeNotificationBackend {
    override val platform = "Windows notifications"

    private var link: NotifyHelperLink? = null
    private var identityReady = false
    private var justRegistered = false
    private val starts = ArrayDeque<Long>()
    @Volatile private var activated: (Long) -> Unit = {}

    override fun onActivated(callback: (Long) -> Unit) {
        activated = callback
    }

    override fun availability(): NativeAvailability {
        when (val gate = gate()) {
            is Gate.Open -> {}
            is Gate.Closed -> return when (val o = gate.outcome) {
                is NativeDeliveryOutcome.Unsupported -> NativeAvailability.Unsupported(o.detail)
                is NativeDeliveryOutcome.Blocked -> NativeAvailability.Blocked(o.detail)
                else -> NativeAvailability.Unknown(o.detail)
            }
        }
        val reply = link?.request("status", timeoutMillis = REQUEST_MILLIS)
            ?: return NativeAvailability.Unknown("The notification helper did not answer.")
        return when (val setting = reply.str("setting")) {
            "enabled" -> NativeAvailability.Ready("Windows notifications are on for ${config.appName}.")
            "disabled_app", "disabled_user", "disabled_policy", "disabled_manifest" -> NativeAvailability.Blocked(blockedText(setting))
            else -> NativeAvailability.Unsupported(unknownIdentityText(reply.str("detail")))
        }
    }

    override fun deliver(notification: PreparedNotification): NativeDeliveryOutcome {
        when (val gate = gate()) {
            is Gate.Open -> {}
            is Gate.Closed -> return gate.outcome
        }
        val reply = link?.request(
            "show",
            mapOf(
                "handle" to JsonPrimitive("n${notification.handle}"),
                "tag" to JsonPrimitive(notification.tag),
                "title" to JsonPrimitive(notification.title),
                "body" to JsonPrimitive(notification.body),
            ),
            timeoutMillis = REQUEST_MILLIS,
        ) ?: return NativeDeliveryOutcome.Failed("The notification helper did not answer.")
        val detail = reply.str("detail").orEmpty()
        return when (reply.str("outcome")) {
            "sent" -> { justRegistered = false; NativeDeliveryOutcome.Sent("Accepted by Windows.") }
            "blocked" -> NativeDeliveryOutcome.Blocked(blockedText(detail))
            "unsupported" -> { identityReady = false; NativeDeliveryOutcome.Unsupported(unknownIdentityText(detail)) }
            else -> NativeDeliveryOutcome.Failed("Windows did not show the notification${codeSuffix(detail)}.")
        }
    }

    override fun withdraw(tag: String) {
        if (gate() !is Gate.Open) return
        link?.request("withdraw", mapOf("tag" to JsonPrimitive(tag)), timeoutMillis = REQUEST_MILLIS)
    }

    override fun close() {
        link?.close()
        link = null
    }

    private sealed interface Gate {
        data object Open : Gate
        data class Closed(val outcome: NativeDeliveryOutcome) : Gate
    }

    /** A running helper and a Start menu identity, or the reason there is none. */
    private fun gate(): Gate {
        val paths = when (val found = locate()) {
            is WindowsPathsResult.Found -> found.paths
            is WindowsPathsResult.Missing -> return Gate.Closed(NativeDeliveryOutcome.Unsupported(found.reason))
        }
        if (link?.alive != true) {
            link?.close()
            link = null
            identityReady = false
            val now = nowMillis()
            while (starts.isNotEmpty() && now - starts.first() > RESTART_WINDOW_MILLIS) starts.removeFirst()
            if (starts.size >= MAX_STARTS) return Gate.Closed(NativeDeliveryOutcome.Failed("The notification helper keeps stopping."))
            starts.addLast(now)
            val started = runCatching { start(paths, config.windowsAppId) }.getOrElse {
                return Gate.Closed(NativeDeliveryOutcome.Failed("The notification helper could not start."))
            }
            if (started.awaitHello(HELLO_MILLIS) == null) {
                started.close()
                return Gate.Closed(NativeDeliveryOutcome.Failed("The notification helper did not start."))
            }
            started.onEvent = ::onHelperEvent
            link = started
        }
        if (identityReady) return Gate.Open
        val reply = link!!.request(
            "identity",
            mapOf("write" to JsonPrimitive(config.registerWindowsShortcutIdentity)),
            timeoutMillis = IDENTITY_MILLIS,
        ) ?: return Gate.Closed(NativeDeliveryOutcome.Failed("The notification helper did not answer."))
        return when (reply.str("identity")) {
            "present" -> { identityReady = true; Gate.Open }
            "registered" -> { identityReady = true; justRegistered = true; Gate.Open }
            "missing" -> Gate.Closed(NativeDeliveryOutcome.Unsupported("${config.appName}'s Start menu shortcut has no notification identity, and adding one is turned off."))
            "conflict" -> Gate.Closed(NativeDeliveryOutcome.Unsupported("${config.appName}'s Start menu shortcut carries another app ID. It was left unchanged."))
            "no_shortcut" -> Gate.Closed(NativeDeliveryOutcome.Unsupported("${config.appName}'s Start menu shortcut was not found. Repairing or reinstalling ${config.appName} restores it."))
            else -> Gate.Closed(NativeDeliveryOutcome.Failed("${config.appName}'s Start menu shortcut could not be read${codeSuffix(reply.str("detail").orEmpty())}."))
        }
    }

    private fun onHelperEvent(event: JsonObject) {
        if (event.str("t") != "activated") return
        val handle = event.str("handle")?.takeIf { HANDLE.matches(it) }?.drop(1)?.toLongOrNull() ?: return
        activated(handle)
    }

    private fun blockedText(setting: String) = when (setting) {
        "disabled_app" -> "Notifications are turned off for ${config.appName} in Windows Settings."
        "disabled_user" -> "Notifications are turned off for this Windows account."
        "disabled_policy" -> "A Windows policy turns notifications off."
        "disabled_manifest" -> "Windows does not accept notifications from this build."
        else -> "Windows blocks notifications for ${config.appName}."
    }

    private fun unknownIdentityText(code: String?): String {
        val base = "Windows does not recognise ${config.appName}'s notification identity${codeSuffix(code.orEmpty())}."
        return if (justRegistered) "$base It was just added to the Start menu shortcut; Windows can take a moment to pick it up." else base
    }

    private fun codeSuffix(detail: String) = if (CODE.matches(detail)) " ($detail)" else ""

    private companion object {
        const val HELLO_MILLIS = 4_000L
        const val REQUEST_MILLIS = 4_000L
        const val IDENTITY_MILLIS = 5_000L
        const val MAX_STARTS = 3
        const val RESTART_WINDOW_MILLIS = 10 * 60_000L
        val HANDLE = Regex("n[0-9]{1,15}")
        val CODE = Regex("0x[0-9A-F]{8}")
    }
}

/**
 * Process-wide AppUserModelID. Windows starts RingMASTER with the shortcut's ID when it is launched from that
 * shortcut. A launch from elsewhere (an older taskbar pin, autostart, the exe itself) gets an ID derived from the
 * exe path, and its windows then group apart from the shortcut. Microsoft's rule is that an explicit ID must be set
 * on the shortcut and on the process alike, before the first window is shown.
 */
object WindowsAppIdentity {
    @Suppress("FunctionName")
    private interface Shell32 : Library {
        fun SetCurrentProcessExplicitAppUserModelID(appId: WString): Int
    }

    /** Sets the ID for this process. Call before any window exists. False off Windows or on failure. */
    fun applyToCurrentProcess(appId: String = NativeNotifierConfig().windowsAppId): Boolean {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return false
        if (!Regex("[A-Za-z0-9._-]{1,128}").matches(appId)) return false
        return runCatching { Native.load("shell32", Shell32::class.java).SetCurrentProcessExplicitAppUserModelID(WString(appId)) == 0 }
            .getOrDefault(false)
    }
}
