package local.ringleader.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import local.ringleader.core.profileFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * The last settings write that failed (preferences, appearance, window placement). Writes keep working in memory;
 * the window shows this once so a read-only or full disk is not silent. A later successful write does not clear it,
 * only Dismiss does, so a flapping disk is still noticed.
 */
object SaveStatus {
    var problem by mutableStateOf<String?>(null); private set
    fun report(what: String, error: Throwable) {
        problem = "$what could not be saved (${error.message?.take(140) ?: error.javaClass.simpleName}). The change applies until RingMASTER closes."
    }
    fun notice(message: String) { problem = message }
    fun dismiss() { problem = null }
}

/** The project's public home (the maintainer's GitHub account). */
const val PROJECT_URL = "https://github.com/primary-null"

/** Where RingMASTER keeps things on this computer, and the deliberate wipe. */
object AppData {
    val configDir: Path get() = profileFile().parent
    val cacheDir: Path get() = (System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.home"), ".cache")).resolve("ring-leader")
    /** User tool plugins are files the user wrote by hand, so the wipe leaves them. */
    const val KEPT = "plugins"

    /** What the wipe removes, as shown before it runs. */
    val removes = listOf(
        "Saved connections (names, addresses, token IDs, pins)",
        "Appearance, interface sizes and background images",
        "Window placement, rail, live-refresh and update preferences",
        "Configuration backups",
        "The embedded console's browser profile (cookies, cache)",
    )

    /** Deletes everything in [configDir] except [KEPT], and the console browser profile. Returns the paths it could not remove. */
    fun clearAll(config: Path = configDir, cache: Path = cacheDir): List<String> {
        val failed = mutableListOf<String>()
        fun wipe(path: Path) {
            if (!Files.exists(path)) return
            runCatching {
                Files.walk(path).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            }.onFailure { failed += path.toString() }
        }
        if (Files.isDirectory(config)) Files.list(config).use { s -> s.toList() }.filter { it.fileName.toString() != KEPT }.forEach(::wipe)
        wipe(cache.resolve("chromium-profile"))
        return failed
    }

    fun open(path: Path): Boolean = runCatching {
        Files.createDirectories(path)
        java.awt.Desktop.getDesktop().open(path.toFile()); true
    }.getOrDefault(false)

    /** The third-party notices shipped beside the app (`app/resources`), or null in a development run without them. */
    fun notices(): String? = runCatching {
        val dir = System.getProperty("compose.application.resources.dir") ?: error("no resources dir")
        Files.readString(Path.of(dir, "THIRD-PARTY-NOTICES.txt"))
    }.getOrNull() ?: runCatching {
        Files.readString(Path.of("desktop/resources/common/THIRD-PARTY-NOTICES.txt"))
    }.getOrNull()
}

/** What happened when RingMASTER asked the system to open a link. A still-running launch is left alone. */
internal enum class BrowserLaunch { Accepted, Pending, Failed }

internal fun launchLinuxBrowser(url: String): BrowserLaunch {
    val process = ProcessBuilder("xdg-open", url)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start()
    // xdg-open exits non-zero when nothing handles the link. A browser it hands off to may keep running or exit 0.
    // A process that is still alive is neither success nor failure, and killing it could interrupt a real launch.
    return when {
        !process.waitFor(4, java.util.concurrent.TimeUnit.SECONDS) -> BrowserLaunch.Pending
        process.exitValue() == 0 -> BrowserLaunch.Accepted
        else -> BrowserLaunch.Failed
    }
}

/**
 * Opens an http(s) link in the system browser. Blocks up to a few seconds, so call it off the UI thread.
 * Linux goes through xdg-open first: AWT's GTK path reports success even when no browser is installed.
 * Windows and macOS use AWT's Desktop, which has already returned by the time this does.
 */
internal fun openInBrowser(url: String): BrowserLaunch = runCatching {
    require(url.startsWith("https://") || url.startsWith("http://"))
    if (System.getProperty("os.name").lowercase().let { "linux" in it || "bsd" in it }) return launchLinuxBrowser(url)
    if (!java.awt.Desktop.isDesktopSupported()) return BrowserLaunch.Failed
    val desktop = java.awt.Desktop.getDesktop()
    if (!desktop.isSupported(java.awt.Desktop.Action.BROWSE)) return BrowserLaunch.Failed
    desktop.browse(java.net.URI(url))
    BrowserLaunch.Accepted
}.getOrDefault(BrowserLaunch.Failed)
