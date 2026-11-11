package local.ringleader.desktop

import me.friwi.jcefmaven.CefAppBuilder
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import java.io.File
import java.util.Base64

/** Local proof only. The process engine survives dialog disposal; each browser has a lease. */
object ConsoleHost {
    private data class BoundBrowser(val client: CefClient, val browser: CefBrowser)
    private val sessions = EngineSessions(
        build = ::buildApp,
        open = { engine: CefApp ->
            val html = ConsoleHost::class.java.getResourceAsStream("/console-prototype.html")!!.use { it.readBytes() }
            val client = engine.createClient()
            try {
                BoundBrowser(client, client.createBrowser("data:text/html;base64," + Base64.getEncoder().encodeToString(html), false, false))
            } catch (error: Exception) {
                client.dispose()
                throw error
            }
        },
        closeBrowser = { bound: BoundBrowser -> bound.browser.close(true); bound.client.dispose() },
        closeEngine = { engine: CefApp -> engine.dispose() },
    )
    fun begin(): Long = sessions.begin()
    fun start(ticket: Long): CefBrowser = sessions.start(ticket).browser
    fun isCurrent(ticket: Long): Boolean = sessions.isCurrent(ticket)
    fun release(ticket: Long? = null) = sessions.release(ticket)
    fun close() = sessions.close()

    private fun buildApp(): CefApp {
        val builder = CefAppBuilder()
        val cacheRoot = System.getenv("XDG_CACHE_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".cache")
        builder.setInstallDir(File(cacheRoot, "ring-leader/jcef-152.0.6"))
        builder.addJcefArgs("--no-first-run", "--no-default-browser-check")
        builder.cefSettings.root_cache_path = File(cacheRoot, "ring-leader/chromium-profile").absolutePath
        builder.cefSettings.windowless_rendering_enabled = false
        return builder.build()
    }
}
