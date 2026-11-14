package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import local.ringleader.core.*
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/**
 * Opt-in screenshots (RINGMASTER_SCREENSHOTS=1, RINGMASTER_UI_PHASE) of the rail, Settings and every scheduler surface,
 * with synthetic data only: dark and light, 960x640, 1600x900 and 960x640 at 200% interface with 150% text.
 * The "scheduler-before" set was taken on the base commit with only the rail-desk and settings-motion scenes.
 */
class SchedulerShotsTest {
    private class Tray(val status: TrayStatus) : TrayPort {
        override fun install() = status
        override fun update(tooltip: String, line: String) {}
        override fun notify(title: String, text: String) {}
        override fun remove() {}
    }

    @Test fun screenshots() = withBerlinZone { runSceneTest {
        if (System.getenv("RINGMASTER_SCREENSHOTS") != "1") return@runSceneTest
        val phase = System.getenv("RINGMASTER_UI_PHASE") ?: "scheduler-after"
        val only = System.getenv("RINGMASTER_SHOT_ONLY")
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        workspace.exploreDemo()
        val state = workspace.state.value
        try {
            for (light in listOf(false, true)) for ((w, h, d, t) in listOf(listOf(960, 640, 1, 1), listOf(1600, 900, 1, 1), listOf(960, 640, 2, 15))) {
                val text = if (t == 15) 1.5f else 1f
                Appearance.preview(light, textScale = text)
                val name = "${if (light) "light" else "dark"}-${w}x$h-${d}x-text${(text * 100).toInt()}"
                val scenes = listOf("rail-desk", "settings-motion", "settings-background", "schedules", "schedules-history", "schedules-tasklog",
                    "schedules-editor", "schedules-editor-errors", "schedules-delete", "schedules-off", "quit-prompt")
                for (sceneName in scenes) {
                    if (only != null && sceneName != only) continue
                    val fixture = when (sceneName) {
                        "schedules-off" -> SchedulerFixture(unreadable = true)
                        "schedules-editor", "schedules-editor-errors" -> SchedulerFixture(populated = false)
                        else -> SchedulerFixture()
                    }
                    val resident = ResidentRuntime(fixture.engine, null, null, null, MotionPreferences(Files.createTempDirectory("shot-prefs").resolve("motion.txt")), { _, _, _ -> Tray(TrayStatus.Installed) }, {})
                    resident.installTray()
                    val image = ImageComposeScene(w, h, Density(d.toFloat(), text), coroutineContext = coroutineContext) {
                        MaterialTheme(colorScheme = ringColors) {
                            CompositionLocalProvider(LocalReduceMotion provides true, LocalResident provides resident) {
                                Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                                    when (sceneName) {
                                        "rail-desk" -> Row {
                                            NavigationRail(state, "Schedules", "Screenshot", workspace, initialExpanded = true) {}
                                            Box(Modifier.weight(1f))
                                        }
                                        "settings-motion", "settings-background" -> BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            SettingsSheet(state, workspace, if (sceneName == "settings-motion") SettingsSection.Motion else SettingsSection.Background, {}, {}, {},
                                                Modifier.size(minOf(900.dp, maxWidth * .92f), minOf(640.dp, maxHeight * .92f)))
                                        }
                                        "quit-prompt" -> { Box(Modifier.fillMaxSize()); QuitPrompt(resident) }
                                        else -> Box(Modifier.fillMaxSize().padding(16.dp)) {
                                            SchedulesContent(fixture.engine, null, TrayStatus.Installed, true, {}, now = { fixture.now },
                                                initialEditor = sceneName.startsWith("schedules-editor"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    try {
                        val ui = CreateScene(image)
                        ui.frames()
                        when (sceneName) {
                            "schedules-history" -> { ui.clickText({ it == "HISTORY (5)" }); ui.until("history") { ui.has("FINISHED WITH WARNINGS") } }
                            "schedules-tasklog" -> { ui.clickText({ it == "HISTORY (5)" }); ui.until("history") { ui.has("TASK LOG") }
                                ui.clickText({ it == "TASK LOG" }); ui.until("log") { ui.has("TASK OK") } }
                            "schedules-editor" -> {
                                ui.until("guests") { ui.has("Choose…") }
                                ui.type("Weekday lab start")
                                ui.clickText({ it == "Choose…" }); ui.until("options") { ui.hasPrefix("VM 104") }
                                ui.clickText({ it.startsWith("VM 104") }); ui.until("identity") { ui.hasPrefix("Identity: SMBIOS UUID") }
                            }
                            "schedules-editor-errors" -> {
                                ui.until("guests") { ui.has("Choose…") }
                                ui.clickText({ it.equals("SAVE SCHEDULE", true) }); ui.until("errors") { ui.has("Enter a name.") }
                            }
                            "schedules-delete" -> { ui.clickText({ it.equals("DELETE", true) }); ui.until("confirm") { ui.has("Delete schedule?") } }
                        }
                        ui.frames()
                        val file = File("../artifacts/ui/$phase/$sceneName-$name.png")
                        file.parentFile.mkdirs()
                        file.writeBytes(image.render(10_000_000_000).encodeToData()!!.bytes)
                    } finally { image.close(); fixture.close() }
                }
            }
        } finally { workspace.close(); scope.cancel(); Appearance.preview(false) }
    } }
}
