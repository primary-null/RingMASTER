package local.ringleader.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import local.ringleader.core.SecretVault
import local.ringleader.core.createOwnerOnlyDirectories
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

/**
 * Cache directory. A blank or relative `XDG_CACHE_HOME` is ignored, matching [profileFile].
 * The folder name stays `ring-leader`.
 */
internal fun cacheDirectory(env: String? = System.getenv("XDG_CACHE_HOME"), home: Path = Path.of(System.getProperty("user.home"))): Path {
    val configured = env?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
    val base = if (configured != null && configured.isAbsolute) configured.normalize() else home.resolve(".cache")
    val dir = base.resolve("ring-leader").normalize()
    require(dir.isAbsolute) { "The cache location must be an absolute path." }
    return dir
}

/** Where RingMASTER keeps things on this computer, and the deliberate wipe. */
object AppData {
    val configDir: Path get() = profileFile().parent
    val cacheDir: Path get() = cacheDirectory()
    /** User tool plugins are files the user wrote by hand, so the wipe leaves them. */
    const val KEPT = "plugins"
    /** Shown before the wipe. Matches [clearAll]: plugins and the rest of the cache stay. */
    const val KEPT_NOTE = "Kept: your tool plugins folder, and the rest of the cache. An older Chromium engine download is not removed. Nothing on any server is changed."

    /** What the wipe removes, as shown before it runs. */
    val removes = listOf(
        "Saved connections (names, addresses, token IDs, pins)",
        "Appearance, interface sizes and background images",
        "Window placement, rail, live-refresh and update preferences",
        "Configuration backups",
        "A leftover console browser profile, if one is in the cache",
        REMEMBERED,
    )

    /** Shown in the wipe list. [vaultName] replaces the generic sentence when the dialog already has the safe's name. */
    const val REMEMBERED = "Secrets remembered in Windows Credential Manager or your password safe"

    fun rememberedLine(vaultName: String?): String =
        if (vaultName.isNullOrBlank()) REMEMBERED else "Secrets remembered in $vaultName"

    /**
     * Forgets every remembered secret, then deletes everything in [config] except [KEPT], and the console browser profile.
     * A vault failure is one line in the result; the files are still wiped. Call off the UI thread: the safe may prompt.
     */
    fun clearAll(config: Path = configDir, cache: Path = cacheDir, vault: SecretVault = SystemVault.instance): List<String> {
        val failed = mutableListOf<String>()
        try {
            vault.forgetAll()
        } catch (t: Throwable) {
            val reason = (t.message ?: "the password safe failed.").take(180)
            failed += "Remembered secrets in ${vault.name} ($reason)"
        }
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
        createOwnerOnlyDirectories(path)
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
