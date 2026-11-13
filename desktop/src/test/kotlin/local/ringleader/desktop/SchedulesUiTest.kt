package local.ringleader.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import local.ringleader.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Meaningful interaction with the Schedules page and editor, driven through the real engine on a fake gateway. */
class SchedulesUiTest {
    private fun scene(fixture: SchedulerFixture, coroutineContext: kotlin.coroutines.CoroutineContext, tray: TrayStatus = TrayStatus.Installed, closeToTray: Boolean = true) =
        ImageComposeScene(1280, 900, coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                    SchedulesContent(fixture.engine, null, tray, closeToTray, {}, now = { fixture.now })
                }
            } }
        }

    @Test fun createScheduleNeedsGuestEvidenceAndExplicitAuthorizationThenPauseAndDelete() = withBerlinZone { runSceneTest {
        SchedulerFixture(populated = false).use { fixture ->
            val scene = scene(fixture, coroutineContext)
            try {
                val ui = CreateScene(scene)
                ui.frames()
                assertTrue(ui.has("SCHEDULER RUNNING"))
                assertTrue(ui.has("TRAY: KEEP RUNNING"))
                assertTrue(ui.hasPrefix("Schedules run only while RingMASTER is running"))
                // Background access rows: ready, not remembered, password.
                assertTrue(ui.has("Background runs may use the secret remembered in Windows Credential Manager."))
                assertTrue(ui.hasPrefix("No secret is remembered for this connection"))
                assertTrue(ui.hasPrefix("Background runs need an API token"))

                ui.clickText({ it.equals("NEW SCHEDULE", true) })
                ui.until("editor") { ui.has("New schedule") && ui.has("Save schedule".uppercase()) }
                ui.type("Weekday lab start")
                ui.until("guest list") { fixture.gateway.guestReads == 1 && ui.has("Choose…") }
                assertEquals(0, fixture.gateway.dispatches)
                ui.clickText({ it == "Choose…" })
                ui.until("guest options") { ui.hasPrefix("VM 104") }
                assertTrue(ui.hasPrefix("VM 900 · debian-template"))
                ui.clickText({ it.startsWith("VM 104") })
                ui.until("identity check") { ui.has("Identity: SMBIOS UUID. A run stops if it changes.") }
                assertTrue(ui.has("VM.PowerMgmt: held on this guest."))

                // Without the authorization ticked nothing is saved.
                ui.clickText({ it.equals("SAVE SCHEDULE", true) })
                ui.until("authorization error") { ui.has("Confirm that RingMASTER may run this schedule without asking.") }
                assertEquals(0, fixture.engine.state.value.document.plans.size)
                ui.clickText({ it.startsWith("I authorize RingMASTER to start alpha (104) on Lab cluster") })
                ui.clickText({ it.equals("SAVE SCHEDULE", true) })
                ui.until("saved") { fixture.engine.state.value.document.plans.size == 1 && !ui.has("SAVE SCHEDULE") }
                val plan = fixture.engine.state.value.document.plans.single()
                assertEquals("Weekday lab start", plan.name)
                assertEquals(RepeatKind.Weekdays, plan.repeat.kind)
                assertEquals(104, plan.target.vmid)
                assertEquals(IdentityMethod.SmbiosUuid, plan.target.identity.method)
                ui.until("row") { ui.has("Weekday lab start") && ui.has("ON") }
                assertTrue(ui.hasPrefix("Next run Fri 20 Nov 2026 07:00 (+01:00)"))
                assertEquals(0, fixture.gateway.dispatches, "saving never sends anything")

                ui.clickText({ it.equals("PAUSE", true) })
                ui.until("paused") { !fixture.engine.state.value.document.plans.single().enabled && ui.has("PAUSED") }
                ui.clickText({ it.equals("RESUME", true) })
                ui.until("resumed") { fixture.engine.state.value.document.plans.single().enabled }

                ui.clickText({ it.equals("DELETE", true) })
                ui.until("delete confirmation") { ui.has("Delete schedule?") }
                ui.clickText({ it.equals("DELETE", true) }, last = true)
                ui.until("deleted") { fixture.engine.state.value.document.plans.isEmpty() }
                assertFalse(java.nio.file.Files.readString(fixture.file).contains(ScheduleFixtures.SECRET))
            } finally { scene.close() }
        }
    } }

    @Test fun historyShowsHonestOutcomesAndTheExactTaskLog() = runSceneTest {
        SchedulerFixture().use { fixture ->
            val scene = scene(fixture, coroutineContext, tray = TrayStatus.Unsupported("This desktop has no notification area that RingMASTER can use."), closeToTray = false)
            try {
                val ui = CreateScene(scene)
                ui.frames()
                assertTrue(ui.has("NO TRAY"))
                assertTrue(ui.hasPrefix("Closing the window quits RingMASTER. This desktop has no notification area"))
                assertTrue(ui.has("NEEDS REVIEW"))
                assertTrue(ui.hasPrefix("Overlaps with Morning maintenance stop"))
                ui.clickText({ it == "HISTORY (5)" })
                ui.until("history") { ui.has("OK") }
                assertTrue(ui.has("FINISHED WITH WARNINGS"))
                assertTrue(ui.has("UNKNOWN, CHECK THE TASK LIST"))
                assertTrue(ui.hasPrefix("Due Fri 13 Nov 2026 07:30 (+01:00)."))
                assertTrue(ui.texts().any { it.contains("3 earlier occurrences were missed too.") })
                ui.clickText({ it.equals("TASK LOG", true) })
                ui.until("task log") { ui.has("TASK OK") }
                assertEquals(1, fixture.gateway.logReads)
                assertEquals(0, fixture.gateway.dispatches)
            } finally { scene.close() }
        }
    }

    @Test fun unreadableStoreTurnsSchedulingOffAndOffersAnExplicitReset() = runSceneTest {
        SchedulerFixture(unreadable = true).use { fixture ->
            val scene = scene(fixture, coroutineContext)
            try {
                val ui = CreateScene(scene)
                ui.frames()
                assertTrue(ui.has("SCHEDULER OFF"))
                assertTrue(ui.has("schedules.json contains unsupported or invalid data and was left unchanged."))
                ui.clickText({ it.equals("SET THE FILE ASIDE AND START EMPTY", true) })
                ui.until("reset") { fixture.engine.state.value.problem == null && ui.has("SCHEDULER RUNNING") }
                assertTrue(java.nio.file.Files.list(fixture.dir).use { s -> s.anyMatch { it.fileName.toString().startsWith("schedules.unreadable-") } })
            } finally { scene.close() }
        }
    }
}
