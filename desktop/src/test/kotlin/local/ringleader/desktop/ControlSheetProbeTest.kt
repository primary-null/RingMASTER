package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.test.Test

/** Opt-in render of the Tech on/off controls in every state: RINGMASTER_PROBE=1 RINGMASTER_PROBE_DIR=<dir>. */
class ControlSheetProbeTest {
    @Test fun sheet() {
        if (System.getenv("RINGMASTER_PROBE") != "1") return
        val dir = File(System.getenv("RINGMASTER_PROBE_DIR") ?: return).apply { mkdirs() }
        val scene = ImageComposeScene(960, 700, Density(2f)) {
            MaterialTheme(ringColors) {
                Column(Modifier.fillMaxSize().background(Tech.Canvas).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionLabel("Switches")
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf("On" to true, "Off" to false).forEach { (l, v) -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { TechSwitch(v, {}); Text(l, color = Tech.Ink, fontSize = 12.sp) } }
                        listOf("On, disabled" to true, "Off, disabled" to false).forEach { (l, v) -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { TechSwitch(v, {}, enabled = false); Text(l, color = Tech.Muted, fontSize = 12.sp) } }
                    }
                    SectionLabel("Check boxes")
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { TechCheck(true, {}, "Include RAM"); TechCheck(false, {}, "Include RAM"); TechCheck(true, {}, "Disabled", enabled = false) }
                    SectionLabel("Slider")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("BLUR", Modifier.width(60.dp), color = Tech.Muted, fontSize = 10.sp, fontFamily = Tech.Mono)
                        TechSlider(.4f, {}, Modifier.width(300.dp).height(28.dp))
                        TechSlider(.7f, {}, Modifier.width(200.dp).height(28.dp), enabled = false)
                    }
                    SectionLabel("Next to actions")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { MiniAction("Run", primary = true) {}; MiniAction("Stop") {}; TechSwitch(true, {}); Text("Continuous", color = Tech.Ink, fontSize = 12.sp) }
                }
            }
        }
        File(dir, "controls.png").writeBytes(scene.render().encodeToData()!!.bytes)
        scene.close()
    }
}
