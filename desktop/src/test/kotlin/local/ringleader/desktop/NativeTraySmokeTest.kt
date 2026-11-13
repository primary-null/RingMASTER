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

    private fun launch(config: Path, cache: Path, log: Path): Process {
        val java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString()
        val pb = ProcessBuilder(java, "-Dskiko.data.path=" + System.getProperty("skiko.data.path"), "-cp", System.getProperty("java.class.path"), "local.ringleader.desktop.MainKt")
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
}
