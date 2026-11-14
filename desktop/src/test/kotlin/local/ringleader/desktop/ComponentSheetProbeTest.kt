package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.test.Test

/** Opt-in render of the 0.21.3 Tech components (tabs, fields, progress, dialog): RINGMASTER_PROBE=1 RINGMASTER_PROBE_DIR=<dir>. */
class ComponentSheetProbeTest {
    @Test fun sheet() {
        if (System.getenv("RINGMASTER_PROBE") != "1") return
        val dir = File(System.getenv("RINGMASTER_PROBE_DIR") ?: return).apply { mkdirs() }
        for (dialog in listOf(false, true)) {
            val scene = ImageComposeScene(1000, 720, Density(2f)) {
                MaterialTheme(ringColors) {
                    Column(Modifier.fillMaxSize().background(Tech.Canvas).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SectionLabel("Tabs")
                        TechTabs(listOf("Summary", "Console", "Hardware", "Snapshots", "Backups", "Actions"), "Snapshots", {})
                        SectionLabel("Fields")
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TechField("before-upgrade", {}, "Snapshot name", Modifier.weight(1f))
                            TechField("1bad", {}, "Snapshot name", Modifier.weight(1f), error = "Start with a letter; letters, digits, - and _ only.")
                            TechField("", {}, "Description (optional)", Modifier.weight(1f), placeholder = "What changed", singleLine = false)
                        }
                        SectionLabel("Progress")
                        TechProgress(fraction = .62f)
                        TechProgress()
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { TechBusy(); Text("Loading task log", fontSize = 12.sp, color = Tech.Muted) }
                        SectionLabel("Actions")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MiniAction("‹ Fleet") {}; MiniAction("Console", primary = true) {}; WebUiAction("https://pve.example.test:8006/#v1:0:=qemu/120") }
                    }
                    if (dialog) TechDialog("Confirm Hard stop", {}, width = 560.dp, subtitle = "Closing this confirmation does not change the guest.", actions = {
                        TechAction("Send", active = true) {}; TechAction("Cancel") {}
                    }) {
                        Text("Origin   https://pve.example.test:8006", fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Ink)
                        Text("Guest    VM 120 · debian-workbench", fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Ink)
                        Text("Hard stop cuts power immediately and can damage guest data.", color = Tech.Red, fontSize = 13.sp)
                    }
                }
            }
            scene.render()
            File(dir, if (dialog) "components-dialog.png" else "components.png").writeBytes(scene.render(500_000_000L).encodeToData()!!.bytes)
            scene.close()
        }
    }
}
