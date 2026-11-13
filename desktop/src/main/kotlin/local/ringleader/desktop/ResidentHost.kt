package local.ringleader.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import local.ringleader.core.*

/**
 * One RingMASTER process per config store. The lock is an OS file lock on `instance.lock` beside `profiles.json`; the
 * OS drops it when the process ends, so a crash never leaves a stale owner.
 */
internal class InstanceLock private constructor(private val channel: FileChannel, private val lock: FileLock) {
    @Volatile private var released = false
    fun release() {
        if (released) return
        released = true
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    sealed interface Outcome {
        data class Owner(val lock: InstanceLock) : Outcome
        /** Another live process holds this store. */
        data object Taken : Outcome
        /** The lock file could not be created or locked; ownership is unknown, so no scheduler may start. */
        data class Failed(val reason: String) : Outcome
    }

    companion object {
        fun acquire(dir: Path): Outcome = try {
            createOwnerOnlyDirectories(dir)
            val file = dir.resolve("instance.lock")
            if (Files.isSymbolicLink(file)) Outcome.Failed("The instance lock is a link.")
            else {
                val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                grantOwnerOnly(file)
                val held = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (held == null) { channel.close(); Outcome.Taken } else Outcome.Owner(InstanceLock(channel, held))
            }
        } catch (e: Exception) {
            Outcome.Failed("The instance lock could not be created (${e.javaClass.simpleName}).")
        }
    }
}

/**
 * Local-only "show the window" channel. A Unix domain socket file (supported on Windows 10 1803+ and Linux) in the
 * owner-only config folder: no TCP/IP listener exists. The owner writes a random token to an owner-only file; a second
 * launch must present it. The only accepted requests are SHOW and SHOW-SCHEDULES; nothing else is parsed or executed.
 */
internal class InstanceChannel private constructor(private val server: ServerSocketChannel, private val socket: Path, private val tokenFile: Path) {
    @Volatile private var closed = false

    fun close() {
        if (closed) return
        closed = true
        runCatching { server.close() }
        runCatching { Files.deleteIfExists(socket) }
        runCatching { Files.deleteIfExists(tokenFile) }
    }

    companion object {
        private const val MAX_REQUEST = 160
        private val requests = setOf("SHOW", "SHOW-SCHEDULES")

        /** Only the lock owner calls this. Returns null when local sockets are unavailable (the app still works). */
        fun serve(dir: Path, onRequest: (String) -> Unit): InstanceChannel? = try {
            val socket = dir.resolve("instance.sock")
            val tokenFile = dir.resolve("instance.token")
            Files.deleteIfExists(socket) // We own the store lock, so any socket file left here is stale.
            val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val temp = Files.createTempFile(dir, ".instance-", ".tmp")
            try {
                writeOwnerOnlyText(temp, token)
                Files.move(temp, tokenFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp) }
            val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            server.bind(UnixDomainSocketAddress.of(socket))
            grantOwnerOnly(socket)
            val channel = InstanceChannel(server, socket, tokenFile)
            Thread({
                while (!channel.closed) {
                    val client = try { server.accept() } catch (_: Exception) { if (channel.closed) break else continue }
                    // A client gets two seconds to send one short line; a stalled client is dropped.
                    val watchdog = Thread({ runCatching { Thread.sleep(2000); client.close() } }, "ringmaster-instance-watchdog").apply { isDaemon = true; start() }
                    runCatching {
                        client.use { c ->
                            val line = readLine(c) ?: return@use
                            val parts = line.split(' ')
                            if (parts.size == 2 && parts[0] in requests && constantTimeEquals(parts[1], token)) {
                                c.write(ByteBuffer.wrap("OK\n".toByteArray()))
                                onRequest(parts[0])
                            }
                        }
                    }
                    watchdog.interrupt()
                }
            }, "ringmaster-instance").apply { isDaemon = true; start() }
            channel
        } catch (_: Exception) { null }

        /** Second launch: ask the owner to show its window. True only when the owner confirmed. */
        fun signal(dir: Path, request: String = "SHOW"): Boolean = try {
            require(request in requests)
            val tokenFile = dir.resolve("instance.token")
            val socket = dir.resolve("instance.sock")
            if (Files.isSymbolicLink(tokenFile) || Files.size(tokenFile) > 128) false
            else {
                val token = Files.readString(tokenFile).trim()
                SocketChannel.open(StandardProtocolFamily.UNIX).use { c ->
                    c.connect(UnixDomainSocketAddress.of(socket))
                    c.write(ByteBuffer.wrap("$request $token\n".toByteArray()))
                    readLine(c) == "OK"
                }
            }
        } catch (_: Exception) { false }

        private fun readLine(c: SocketChannel): String? {
            val buffer = ByteBuffer.allocate(MAX_REQUEST)
            while (buffer.hasRemaining()) {
                if (c.read(buffer) < 0) break
                val bytes = buffer.array().copyOf(buffer.position())
                val end = bytes.indexOf('\n'.code.toByte())
                if (end >= 0) return String(bytes, 0, end, Charsets.US_ASCII)
            }
            return null
        }

        private fun constantTimeEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}

/** The OS vault, read-only for the scheduler, plus the Desk's non-secret marker list. */
internal class VaultScheduleSecrets(private val vault: SecretVault, private val markers: RememberedProfiles) : ScheduleSecrets {
    override val vaultName: String get() = vault.name
    override fun status(): VaultStatus = vault.status()
    override fun remembered(profileId: String): Boolean = profileId in markers.load()
    override fun read(profileId: String): CharArray? = vault.read(profileId)
}

/** Tray state shown in Settings and on the Schedules page. */
internal sealed interface TrayStatus {
    data object Starting : TrayStatus
    data object Installed : TrayStatus
    data class Unsupported(val reason: String) : TrayStatus
    data class Failed(val reason: String) : TrayStatus
}

/**
 * The resident process: one scheduler and one tray icon that outlive the window's visibility. Created only by the
 * desktop entry point for the instance-lock owner, never in browser mode and never in tests that draw scenes.
 */
internal class ResidentRuntime(
    val engine: ScheduleEngine?,
    /** Why scheduling is off (no lock), or null. */
    val engineProblem: String?,
    private val lock: InstanceLock?,
    private val engineScope: CoroutineScope?,
    private val preferences: MotionPreferences = MotionPreferences(),
    private val trayFactory: (onOpen: () -> Unit, onSchedules: () -> Unit, onQuit: () -> Unit) -> TrayPort = ::TrayHost,
    private val consoles: () -> Unit = { ConsoleHost.close() },
) {
    var windowVisible by mutableStateOf(true); private set
    /** Bumped to raise the window over others. */
    var raiseToken by mutableStateOf(0); private set
    /** A page the window should open next (tray Schedules, second launch). Consumed by the app. */
    var pageRequest by mutableStateOf<String?>(null)
    var trayStatus by mutableStateOf<TrayStatus>(TrayStatus.Starting); private set
    var closeToTray by mutableStateOf(preferences.option("closeToTray", defaultCloseToTray())); private set
    /** The in-window "Quit?" prompt, shown when closing would stop enabled schedules. */
    var quitPrompt by mutableStateOf(false)
    private var tray: TrayPort? = null
    private var channel: InstanceChannel? = null
    private var exit: (() -> Unit)? = null
    @Volatile private var quitting = false

    private var hooked = false

    /** Call once from the application scope. Logoff, shutdown or a terminated JVM run the same bounded engine stop. */
    fun attach(exitApplication: () -> Unit) {
        exit = exitApplication
        if (hooked) return
        hooked = true
        Runtime.getRuntime().addShutdownHook(Thread({ stopEngine(Duration.ofSeconds(2)); release() }, "ringmaster-session-end"))
    }

    /** The lock owner listens for a second launch, which only ever asks this process to show its window. */
    internal fun serve(dir: Path) {
        channel = InstanceChannel.serve(dir) { request -> show(if (request == "SHOW-SCHEDULES") "Schedules" else null) }
    }

    /** Call on the UI thread once the window exists. Installs the tray when the platform offers one. */
    fun installTray() {
        if (tray != null) return
        val host = trayFactory({ show() }, { show("Schedules") }, { quit() })
        tray = host
        trayStatus = host.install()
    }

    fun chooseCloseToTray(on: Boolean) {
        closeToTray = on
        preferences.put("closeToTray", on)
    }

    /** Every close entry point (window X, Alt+F4 or the taskbar's Close, the custom title bar) comes here. */
    fun closeRequested() {
        if (quitting) return
        if (closeToTray && trayStatus == TrayStatus.Installed) { hide(); return }
        if (enabledSchedules() > 0 && engine != null) { quitPrompt = true; return }
        quit()
    }

    fun hide() {
        if (trayStatus != TrayStatus.Installed) return // Never hide the last way back.
        windowVisible = false
        if (!preferences.option("trayExplained", false)) {
            preferences.put("trayExplained", true)
            tray?.notify("RingMASTER is still running", "Schedules continue while it runs here. Open it from this icon, or choose Quit RingMASTER in its menu to exit.")
        }
        refreshTray()
    }

    /** Thread-safe: the tray, the instance channel and the UI may all call it. */
    fun show(page: String? = null) = javax.swing.SwingUtilities.invokeLater {
        if (quitting) return@invokeLater
        if (page != null) pageRequest = page
        windowVisible = true
        raiseToken++
    }

    fun refreshTray() {
        val count = enabledSchedules()
        val text = if (Appearance.showcase || count == 0) "RingMASTER" else "RingMASTER · $count schedule${if (count == 1) "" else "s"} on"
        tray?.update(text, when {
            engine == null -> "Scheduling is off"
            count == 0 -> "No schedules on"
            else -> "$count schedule${if (count == 1) "" else "s"} on"
        })
    }

    private fun enabledSchedules(): Int = engine?.state?.value?.document?.plans?.count { it.enabled && it.invalidReason == null } ?: 0

    /** Explicit Quit: stops the scheduler (bounded), closes consoles and the tray, releases the store, then exits. */
    fun quit() {
        if (quitting) return
        quitting = true
        quitPrompt = false
        windowVisible = false
        tray?.remove()
        Thread({
            stopEngine(Duration.ofSeconds(5))
            javax.swing.SwingUtilities.invokeLater {
                runCatching { consoles() }
                release()
                exit?.invoke()
            }
        }, "ringmaster-quit").apply { isDaemon = true; start() }
    }

    /** Keep the instance lock until Quit; a timed-out stop must never permit a configuration wipe. */
    suspend fun prepareForClear(timeout: Duration = Duration.ofSeconds(5)): Boolean {
        val stopped = engine?.shutdown(timeout) ?: true
        if (stopped) engineScope?.cancel()
        return stopped
    }

    suspend fun clearData(clear: () -> List<String>, timeout: Duration = Duration.ofSeconds(5)): List<String> =
        if (prepareForClear(timeout)) clear()
        else listOf("A scheduled run is still stopping. Nothing was cleared. Quit RingMASTER and retry after it stops.")

    private fun stopEngine(timeout: Duration) {
        val e = engine ?: return
        runCatching { runBlocking { e.shutdown(timeout) } }
        runCatching { engineScope?.cancel() }
    }

    private fun release() {
        channel?.close()
        lock?.release()
    }

    companion object {
        fun defaultCloseToTray(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

        /**
         * Desktop start: claim the store, then either become the resident owner or hand over to the running one. Returns
         * null when this process should exit because another instance was asked to show itself.
         */
        fun start(dir: Path = profileFile().parent): ResidentRuntime? {
            return when (val outcome = InstanceLock.acquire(dir)) {
                is InstanceLock.Outcome.Taken -> {
                    if (!InstanceChannel.signal(dir)) alreadyRunningNotice()
                    null
                }
                is InstanceLock.Outcome.Failed -> ResidentRuntime(null, "Scheduling is off: ${outcome.reason} Another RingMASTER could be running, so this one does not run schedules.", null, null)
                is InstanceLock.Outcome.Owner -> {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    val engine = ScheduleEngine(
                        store = ScheduleStore(dir.resolve("schedules.json")),
                        profiles = { ProfileStore(dir.resolve("profiles.json")).load() },
                        secrets = VaultScheduleSecrets(SystemVault.instance, RememberedProfiles(dir.resolve("remembered.json"))),
                        gateway = LiveScheduleGateway(),
                        leases = MutationLeases.shared,
                        clock = Clock.systemUTC(),
                        scope = scope,
                    )
                    engine.start()
                    ResidentRuntime(engine, null, outcome.lock, scope).also { it.serve(dir) }
                }
            }
        }

        private fun alreadyRunningNotice() = runCatching {
            javax.swing.JOptionPane.showMessageDialog(null,
                "RingMASTER is already running. Open it from its icon in the notification area.", "RingMASTER", javax.swing.JOptionPane.INFORMATION_MESSAGE)
        }
    }
}
