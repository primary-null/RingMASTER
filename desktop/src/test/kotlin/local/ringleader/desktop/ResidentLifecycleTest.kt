package local.ringleader.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import local.ringleader.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Child process for the cross-process single-instance test: owns the store lock and echoes show requests. */
object InstanceProbe {
    @JvmStatic fun main(args: Array<String>) {
        val dir = Path.of(args[0])
        when (val outcome = InstanceLock.acquire(dir)) {
            is InstanceLock.Outcome.Owner -> {
                val channel = InstanceChannel.serve(dir) { println("REQUEST $it"); System.out.flush() }
                println(if (channel != null) "OWNER" else "OWNER-NO-CHANNEL"); System.out.flush()
                readLine()
                channel?.close(); outcome.lock.release()
            }
            else -> println("NOT-OWNER $outcome")
        }
    }
}

class ResidentLifecycleTest {
    private class FakeTray(val status: TrayStatus) : TrayPort {
        var notified = 0; var removed = 0; var tooltip = ""; var line = ""
        override fun install() = status
        override fun update(tooltip: String, line: String) { this.tooltip = tooltip; this.line = line }
        override fun notify(title: String, text: String) { notified++ }
        override fun remove() { removed++ }
    }

    private fun flushEdt() = javax.swing.SwingUtilities.invokeAndWait { }

    private fun runtime(tray: FakeTray, engine: ScheduleEngine? = null, consoles: () -> Unit = {}) =
        ResidentRuntime(engine, null, null, null, MotionPreferences(Files.createTempDirectory("resident-prefs").resolve("motion.txt")), { _, _, _ -> tray }, consoles)

    private fun engineWithOnePlan(): ScheduleEngine {
        val dir = Files.createTempDirectory("resident-engine")
        val now = Instant.parse("2026-11-20T05:00:00Z")
        val plan = ScheduleFixtures.plan(notBefore = now)
        ScheduleStore(dir.resolve("schedules.json")).apply { load(); save(ScheduleDocument(listOf(plan))) }
        val engine = ScheduleEngine(ScheduleStore(dir.resolve("schedules.json")), { listOf(ScheduleFixtures.profile()) }, FakeScheduleSecrets(),
            FakeScheduleGateway(), MutationLeases(), Clock.fixed(now, ZoneOffset.UTC), CoroutineScope(SupervisorJob() + Dispatchers.IO))
        engine.start()
        val deadline = System.nanoTime() + 5_000_000_000
        while (!engine.state.value.loaded && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(engine.state.value.loaded)
        return engine
    }

    @Test fun clearDataStopsTheEngineBeforeInvokingTheWipe() = kotlinx.coroutines.runBlocking {
        val engine = engineWithOnePlan()
        val r = runtime(FakeTray(TrayStatus.Installed), engine)
        var cleared = false
        assertEquals(emptyList(), r.clearData({
            assertTrue(engine.state.value.stopped)
            assertFalse(engine.state.value.running)
            cleared = true
            emptyList()
        }))
        assertTrue(cleared)
    }

    @Test fun closingHidesToTheTrayExplainsOnceAndTrayOpenRestoresWithAPage() {
        val tray = FakeTray(TrayStatus.Installed)
        val r = runtime(tray)
        r.installTray()
        assertEquals(TrayStatus.Installed, r.trayStatus)
        r.chooseCloseToTray(true)
        r.closeRequested()
        assertFalse(r.windowVisible)
        assertEquals(1, tray.notified)
        r.show(); flushEdt()
        assertTrue(r.windowVisible)
        assertEquals(1, r.raiseToken)
        r.closeRequested()
        assertFalse(r.windowVisible)
        assertEquals(1, tray.notified, "the first-use explanation is shown once")
        r.show("Schedules"); flushEdt()
        assertTrue(r.windowVisible)
        assertEquals("Schedules", r.pageRequest)
        assertEquals(2, r.raiseToken)
    }

    @Test fun aFailedOrMissingTrayNeverHidesTheLastWindowAndCloseQuits() {
        for (status in listOf(TrayStatus.Failed("refused"), TrayStatus.Unsupported("none"))) {
            val tray = FakeTray(status)
            var consoles = 0
            val exited = CountDownLatch(1)
            val r = runtime(tray, consoles = { consoles++ })
            r.attach { exited.countDown() }
            r.installTray()
            r.chooseCloseToTray(true)
            r.hide()
            assertTrue(r.windowVisible, "hide() refuses without an installed tray")
            r.closeRequested()
            assertTrue(exited.await(5, TimeUnit.SECONDS), "close quits when there is no tray")
            assertEquals(1, consoles)
            r.closeRequested(); r.quit(); flushEdt()
            assertEquals(1, consoles, "quit runs once")
        }
    }

    @Test fun closingWithSchedulesOnAndNoTrayAsksFirstAndQuitStopsTheEngine() {
        val engine = engineWithOnePlan()
        val tray = FakeTray(TrayStatus.Installed)
        val r = runtime(tray, engine)
        val exited = CountDownLatch(1)
        r.attach { exited.countDown() }
        r.installTray()
        r.chooseCloseToTray(false)
        r.closeRequested()
        assertTrue(r.quitPrompt)
        assertTrue(r.windowVisible)
        assertEquals(1L, exited.count)
        r.quit()
        assertTrue(exited.await(10, TimeUnit.SECONDS))
        assertTrue(engine.state.value.stopped)
        assertEquals(1, tray.removed)
        assertFalse(r.windowVisible)
    }

    @Test fun trayTextIsGenericAndShowcaseHidesTheCount() {
        val engine = engineWithOnePlan()
        val tray = FakeTray(TrayStatus.Installed)
        val r = runtime(tray, engine)
        r.installTray()
        try {
            Appearance.preview(false, showcase = false)
            r.refreshTray()
            assertEquals("RingMASTER · 1 schedule on", tray.tooltip)
            assertFalse(tray.tooltip.contains("alpha") || tray.tooltip.contains("Plan"))
            Appearance.preview(false, showcase = true)
            r.refreshTray()
            assertEquals("RingMASTER", tray.tooltip)
        } finally { Appearance.preview(false) }
    }

    @Test fun oneOwnerPerStoreAndTheChannelOnlyAcceptsTheToken() {
        val dir = Files.createTempDirectory("instance")
        val owner = InstanceLock.acquire(dir) as InstanceLock.Outcome.Owner
        assertEquals(InstanceLock.Outcome.Taken, InstanceLock.acquire(dir))
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        val channel = InstanceChannel.serve(dir) { seen += it }
        assertNotNull(channel, "local sockets are available on this platform")
        assertTrue(InstanceChannel.signal(dir, "SHOW-SCHEDULES"))
        assertEquals(listOf("SHOW-SCHEDULES"), seen)
        // A client without the token is ignored.
        java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { c ->
            c.connect(java.net.UnixDomainSocketAddress.of(dir.resolve("instance.sock")))
            c.write(java.nio.ByteBuffer.wrap("SHOW ${"0".repeat(64)}\n".toByteArray()))
            Thread.sleep(200)
        }
        assertEquals(1, seen.size)
        channel.close()
        assertFalse(InstanceChannel.signal(dir))
        assertFalse(Files.exists(dir.resolve("instance.token")))
        owner.lock.release()
        assertTrue(InstanceLock.acquire(dir) is InstanceLock.Outcome.Owner)
    }

    @Test fun aSecondProcessIsNotTheOwnerAndCanAskTheOwnerToShow() {
        val dir = Files.createTempDirectory("instance-process")
        val classpath = listOf(InstanceLock::class.java, ProfileStore::class.java, Unit::class.java, InstanceProbe::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString()
        val probe = ProcessBuilder(java, "-cp", classpath, "local.ringleader.desktop.InstanceProbe", dir.toString()).redirectErrorStream(true).start()
        try {
            val out = probe.inputStream.bufferedReader()
            assertEquals("OWNER", out.readLine())
            assertEquals(InstanceLock.Outcome.Taken, InstanceLock.acquire(dir), "the other process holds the store")
            assertTrue(InstanceChannel.signal(dir), "the owner confirmed the show request")
            assertEquals("REQUEST SHOW", out.readLine())
            probe.outputStream.write("\n".toByteArray()); probe.outputStream.flush()
            assertTrue(probe.waitFor(10, TimeUnit.SECONDS))
            val after = InstanceLock.acquire(dir)
            assertTrue(after is InstanceLock.Outcome.Owner, "released when the owner exits: $after")
            after.lock.release()
        } finally { probe.destroyForcibly() }
    }
}
