package local.ringleader.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Opt-in native Windows smoke (RINGMASTER_NATIVE_TRAY=1): starts the real desktop app in a child JVM with a throwaway
 * config and cache folder (no saved connections, so nothing reaches any server; the update check waits for consent),
 * then drives it like the OS does:
 *  - WM_CLOSE (what the title bar's X, Alt+F4 and the taskbar's Close send) hides the window into the tray and the
 *    process keeps running;
 *  - a second launch on the same config exits and the first window becomes visible again (single instance + local IPC).
 * It shows a real tray icon and one balloon on this desktop for a few seconds. It never touches the user's own config.
 */
class NativeTraySmokeTest {
    @Suppress("FunctionName")
    private interface User32 : Library {
        fun interface Enum : Callback { fun invoke(hwnd: Pointer?, data: Pointer?): Boolean }
        fun EnumWindows(callback: Enum, data: Pointer?): Boolean
        fun GetWindowThreadProcessId(hwnd: Pointer?, pid: IntArray): Int
        fun GetWindowTextW(hwnd: Pointer?, text: CharArray, max: Int): Int
        fun IsWindowVisible(hwnd: Pointer?): Boolean
        fun IsIconic(hwnd: Pointer?): Boolean
        fun ShowWindow(hwnd: Pointer?, command: Int): Boolean
        fun SetWindowPos(hwnd: Pointer?, after: Pointer?, x: Int, y: Int, width: Int, height: Int, flags: Int): Boolean
        fun PostMessageW(hwnd: Pointer?, msg: Int, w: Pointer?, l: Pointer?): Boolean
    }

    private val user32 by lazy { Native.load("user32", User32::class.java) }

    private fun window(pid: Long): Pointer? {
        var found: Pointer? = null
        user32.EnumWindows({ hwnd, _ ->
            val owner = IntArray(1)
            user32.GetWindowThreadProcessId(hwnd, owner)
            if (owner[0].toLong() == pid) {
                val text = CharArray(256)
                val n = user32.GetWindowTextW(hwnd, text, text.size)
                if (String(text, 0, n) == "RingMASTER") found = hwnd
            }
            found == null
        }, null)
        return found
    }

    private fun waitFor(what: String, seconds: Int, condition: () -> Boolean) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (System.nanoTime() < deadline) { if (condition()) return; Thread.sleep(250) }
        error("Timed out: $what")
    }

    private fun launch(config: Path, cache: Path, log: Path, nativeFrame: String? = null): Process {
        val java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString()
        val command = mutableListOf(java, "-Dskiko.data.path=" + System.getProperty("skiko.data.path"))
        nativeFrame?.let { command += "-Dringmaster.nativeFrame=$it" }
        command += listOf("-cp", System.getProperty("java.class.path"), "local.ringleader.desktop.MainKt")
        val pb = ProcessBuilder(command)
        pb.environment()["XDG_CONFIG_HOME"] = config.toString()
        pb.environment()["XDG_CACHE_HOME"] = cache.toString()
        pb.redirectErrorStream(true).redirectOutput(log.toFile())
        return pb.start()
    }

    @Test fun closeHidesToTheTrayAndASecondLaunchRestoresTheWindow() {
        if (System.getenv("RINGMASTER_NATIVE_TRAY") != "1" || !System.getProperty("os.name").startsWith("Windows")) return
        val root = Files.createTempDirectory("native-tray")
        val config = root.resolve("config"); val cache = root.resolve("cache")
        val first = launch(config, cache, root.resolve("first.log"))
        try {
            waitFor("the first window", 90) { window(first.pid())?.let { user32.IsWindowVisible(it) } == true }
            val hwnd = window(first.pid())!!
            Thread.sleep(3000) // let the tray icon install after the window opened
            assertTrue(Files.exists(config.resolve("ringmaster").resolve("instance.lock")))
            user32.PostMessageW(hwnd, 0x0010 /* WM_CLOSE */, null, null)
            waitFor("hidden to the tray", 20) { !user32.IsWindowVisible(hwnd) }
            Thread.sleep(1500)
            assertTrue(first.isAlive, "closing kept the process running")
            assertFalse(user32.IsWindowVisible(hwnd))
            val prefs = Files.readString(config.resolve("ringmaster").resolve("motion.txt"))
            assertTrue(prefs.contains("trayExplained=true"), "the first-use explanation was shown: $prefs")

            val second = launch(config, cache, root.resolve("second.log"))
            assertTrue(second.waitFor(60, TimeUnit.SECONDS), "the second launch exits")
            assertEquals(0, second.exitValue())
            waitFor("restored by the second launch", 20) { user32.IsWindowVisible(hwnd) }
            assertTrue(first.isAlive)
            assertEquals(null, window(second.pid()), "the second launch never opened a window")
            println("NATIVE-TRAY-SMOKE OK first=${first.pid()} second=${second.pid()} log=${root}")
        } finally {
            first.destroy()
            if (!first.waitFor(10, TimeUnit.SECONDS)) first.destroyForcibly()
        }
    }

    /** Actual application and actual display, not a retained WM_PRINT image or a standalone Compose scene. */
    @Test fun maximizedAppRemainsPaintedAfterTrayAndMinimizeRestores() {
        val output = System.getenv("RINGMASTER_NATIVE_RESTORE")?.let(Path::of)
        org.junit.jupiter.api.Assumptions.assumeTrue(output != null && System.getProperty("os.name").startsWith("Windows"))
        val root = output!!
        Files.createDirectories(root)
        val config = root.resolve("config"); val cache = root.resolve("cache")
        val settings = config.resolve("ringmaster")
        Files.createDirectories(settings)
        Files.writeString(settings.resolve("appearance.properties"), "schema=1\ntheme=PitchBlack\n")
        Files.writeString(settings.resolve("updates.properties"), "schema=1\nconsent=no\n")
        val nativeFrame = System.getenv("RINGMASTER_NATIVE_RESTORE_FRAME")
        val first = launch(config, cache, root.resolve("first.log"), nativeFrame)
        fun restore(name: String) {
            val second = launch(config, cache, root.resolve("$name-launch.log"), nativeFrame)
            try {
                assertTrue(second.waitFor(20, TimeUnit.SECONDS), "second launch exits")
                assertEquals(0, second.exitValue())
            } finally { if (second.isAlive) second.destroyForcibly() }
        }
        fun capture(hwnd: Pointer, name: String) {
            assertTrue(user32.SetWindowPos(hwnd, Pointer(-1L), 0, 0, 0, 0, 0x0013)) // TOPMOST, no move/size/activate
            Thread.sleep(800)
            val gc = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
            val image = java.awt.Robot(gc.device).createMultiResolutionScreenCapture(gc.bounds).resolutionVariants.last() as java.awt.image.BufferedImage
            javax.imageio.ImageIO.write(image, "png", root.resolve("$name.png").toFile())
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            val dark = pixels.count { val r = it ushr 16 and 255; val g = it ushr 8 and 255; val b = it and 255; r < 70 && g < 70 && b < 70 }
            val amber = pixels.count { val r = it ushr 16 and 255; val g = it ushr 8 and 255; val b = it and 255; r > 140 && g in 60..210 && b < 90 }
            Files.writeString(root.resolve("frames.tsv"), "$name\t${image.width}\t${image.height}\t$dark\t$amber\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
            assertTrue(dark > pixels.size * .70 && amber > 100, "$name is blank or obscured: dark=$dark amber=$amber")
        }
        try {
            waitFor("the app window", 60) { window(first.pid())?.let { user32.IsWindowVisible(it) } == true }
            val hwnd = window(first.pid())!!
            Thread.sleep(3000)
            user32.ShowWindow(hwnd, 3) // SW_MAXIMIZE
            capture(hwnd, "initial")
            repeat(6) { cycle ->
                user32.PostMessageW(hwnd, 0x0010, null, null) // WM_CLOSE -> tray
                waitFor("hidden", 10) { !user32.IsWindowVisible(hwnd) }
                restore("$cycle-tray")
                waitFor("tray restored", 10) { user32.IsWindowVisible(hwnd) && !user32.IsIconic(hwnd) }
                capture(hwnd, "$cycle-tray")
                user32.ShowWindow(hwnd, 6) // SW_MINIMIZE
                waitFor("minimized", 10) { user32.IsIconic(hwnd) }
                restore("$cycle-minimize")
                waitFor("minimize restored", 10) { user32.IsWindowVisible(hwnd) && !user32.IsIconic(hwnd) }
                capture(hwnd, "$cycle-minimize")
                assertTrue(first.isAlive)
            }
        } finally {
            first.destroy()
            if (!first.waitFor(10, TimeUnit.SECONDS)) first.destroyForcibly()
        }
    }
}
