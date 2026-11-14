package local.ringleader.desktop

import java.awt.Frame
import java.awt.Rectangle
import java.awt.Robot
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import local.ringleader.core.profileFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Child JVM isolates all settings and the real app's resident/runtime from the developer's running app. */
object TrayNavigationProbe {
    @JvmStatic fun main(args: Array<String>) {
        val out = Path.of(args[0])
        var navigate: ((TrayDestination) -> Unit)? = null
        val runtime = ResidentRuntime(null, "Test instance", null, null,
            trayFactory = { open, route, quit -> navigate = route; TrayHost(open, route, quit) },
            alerts = DesktopAlerts(profileFile().parent, null))
        val failure = AtomicReference<Throwable?>()
        Thread({
            try {
                fun await(what: String, condition: () -> Boolean) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                    while (System.nanoTime() < deadline) {
                        var done = false
                        SwingUtilities.invokeAndWait { done = condition() }
                        if (done) return
                        Thread.sleep(100)
                    }
                    error("Timed out: $what")
                }
                await("tray installed") { runtime.trayStatus == TrayStatus.Installed && navigate != null }
                await("main window shown") { Frame.getFrames().any { it.title == "RingMASTER" && it.isVisible } }
                val window = Frame.getFrames().single { it.title == "RingMASTER" && it.isVisible }
                SwingUtilities.invokeAndWait { window.isAlwaysOnTop = true }
                for ((name, destination) in listOf(
                    "fleet" to TrayDestination.Fleet, "activity" to TrayDestination.Activity,
                    "schedules" to TrayDestination.Schedules, "storage" to TrayDestination.Storage,
                    "notifications" to TrayDestination.Notifications, "watcher" to TrayDestination.Watcher,
                    "settings" to TrayDestination.Settings, "updates" to TrayDestination.Updates, "about" to TrayDestination.About,
                )) {
                    SwingUtilities.invokeAndWait { runtime.hide() }
                    await("hidden") { !window.isVisible }
                    SwingUtilities.invokeAndWait { navigate!!(destination) }
                    await("consumed $destination and restored") { runtime.trayRequest == null && window.isVisible && !window.state.let { it and Frame.ICONIFIED != 0 } }
                    Thread.sleep(900)
                    var bounds = Rectangle()
                    SwingUtilities.invokeAndWait { bounds = Rectangle(window.locationOnScreen, window.size) }
                    val image = Robot(window.graphicsConfiguration.device).createScreenCapture(bounds)
                    ImageIO.write(image, "png", out.resolve("$name.png").toFile())
                    println("TRAY-ROUTE $destination restored and consumed")
                }
                Files.writeString(out.resolve("success.txt"), "nine routes restored and consumed")
            } catch (error: Throwable) {
                failure.set(error)
                error.printStackTrace()
            } finally { SwingUtilities.invokeLater { runtime.quit() } }
        }, "tray-route-probe").apply { isDaemon = true; start() }
        desktopMain(runtime)
        failure.get()?.let { throw it }
    }
}

class TrayNavigationNativeTest {
    @Test fun actualAppConsumesTrayRoutesAfterHiddenRestore() {
        val folder = System.getenv("RINGMASTER_TRAY_NAVIGATION_NATIVE")
        org.junit.jupiter.api.Assumptions.assumeTrue(folder != null && System.getProperty("os.name").startsWith("Windows"))
        val out = Path.of(folder!!)
        Files.createDirectories(out)
        Files.deleteIfExists(out.resolve("success.txt"))
        val config = out.resolve("config")
        Files.createDirectories(config.resolve("ringmaster"))
        Files.writeString(config.resolve("ringmaster/updates.properties"), "schema=1\nconsent=no\n")
        val java = Path.of(System.getProperty("java.home"), "bin", "java.exe")
        val child = ProcessBuilder(java.toString(), "-Dskiko.data.path=" + System.getProperty("skiko.data.path"),
            "-cp", System.getProperty("java.class.path"), TrayNavigationProbe::class.java.name, out.toString())
            .apply {
                environment()["XDG_CONFIG_HOME"] = config.toString()
                environment()["XDG_CACHE_HOME"] = out.resolve("cache").toString()
                redirectErrorStream(true)
                redirectOutput(out.resolve("probe.log").toFile())
            }.start()
        try {
            assertTrue(child.waitFor(120, TimeUnit.SECONDS), "native navigation probe completed")
            assertEquals(0, child.exitValue(), "see ${out.resolve("probe.log")}")
            assertTrue(Files.exists(out.resolve("success.txt")), "probe must finish every route, see ${out.resolve("probe.log")}")
        } finally {
            if (child.isAlive) child.destroyForcibly()
        }
    }
}
