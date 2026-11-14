package local.ringleader.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.yield
import local.ringleader.core.BtScan
import org.junit.jupiter.api.Test

/**
 * Opt-in pictures of the three proposed Bluetooth scan concepts and of today's view (RINGMASTER_BT_CONCEPTS=<folder>).
 * Invented devices only. Writes stills to <folder>/<concept>/ and a short clip of numbered frames to <folder>/clips/<concept>/.
 * The concepts are shown through BtScanConcepts.preview, the test-only switch; the app never sets it.
 */
class BtConceptShotsTest {
    private data class Case(val name: String, val devices: Int, val width: Int, val height: Int, val density: Float = 1f,
        val theme: ThemeMode = ThemeMode.Dark, val reduce: Boolean = false, val atMs: Long = 8_000)

    private val cases = buildList {
        add(Case("busy-40-1600x1000-dark", 40, 1600, 1000))
        for (n in listOf(5, 40, 120)) for (w in listOf(960, 2560))
            for (theme in listOf(ThemeMode.Dark, ThemeMode.PitchBlack, ThemeMode.Light)) for (reduce in listOf(false, true)) {
                add(Case("$n-${w}x${if (w == 960) 600 else 1440}-${theme.name.lowercase()}${if (reduce) "-reduced" else ""}",
                    n, w, if (w == 960) 600 else 1440, if (w == 960) 1f else 1.25f, theme, reduce))
            }
    }

    @Test fun concepts() {
        val out = File(System.getenv("RINGMASTER_BT_CONCEPTS") ?: return).apply { mkdirs() }
        try {
            runSceneTest {
                for (concept in listOf<BtConcept?>(null) + BtConcept.entries) {
                    val tag = concept?.name?.lowercase() ?: "today"
                    BtScanConcepts.preview = concept
                    for (case in if (concept == null) cases.take(1) else cases) shoot(case, File(out, "$tag/${case.name}.png"), null, coroutineContext)
                    shoot(cases.first(), null, File(out, "clips/$tag").apply { deleteRecursively(); mkdirs() }, coroutineContext)
                }
            }
        } finally {
            BtScanConcepts.preview = null; BtScanConcepts.selected = null
            Appearance.preview(ThemeMode.Dark)
        }
    }

    /** Plays the scan from 1 s to [Case.atMs] with a snapshot every 200 ms, then writes the still, or one clip frame per 100 ms. */
    private suspend fun shoot(case: Case, still: File?, clip: File?, context: kotlin.coroutines.CoroutineContext) {
        Appearance.preview(case.theme)
        val sim = BtSimulatedScan(case.devices, lateArrivals = true)
        // The one the user cares about: a paired, named device that is heard throughout.
        BtScanConcepts.selected = sim.address(2)
        sim.advanceTo(if (clip == null) case.atMs else 1_000)
        var scan by mutableStateOf<BtScan?>(sim.snapshot())
        var received by mutableLongStateOf(0L)
        val scene = ImageComposeScene(case.width, case.height, Density(case.density), coroutineContext = context) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides case.reduce) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas) {
                    val concept = BtScanConcepts.preview
                    if (concept == null) BtScanView(scan, scanning = true, modifier = Modifier.padding(16.dp), platform = "Windows · WinRT", receivedAt = received)
                    else ConceptScanView(concept, scan, true, Modifier.padding(16.dp), "Synthetic scan", received)
                }
            } }
        }
        try {
            var time = 0L
            var ms = 1_000L
            var n = 0
            while (clip != null && ms <= case.atMs) {
                time += 16_000_000L; ms += 16
                if (ms % 208 < 16) { sim.advanceTo(ms); scan = sim.snapshot(); received = time }
                if (ms >= 2_000 && ms % 96 < 16 && n < 60) scene.render(time).use { File(clip, "%03d.png".format(n++)).writeBytes(it.encodeToData()!!.bytes) }
                else scene.render(time).close()
                yield()
            }
            if (still != null) {
                still.parentFile.mkdirs()
                // Settle initial composition; stills already start at the busy snapshot.
                repeat(8) { time += 16_000_000L; scene.render(time).close(); yield() }
                time += 16_000_000L
                scene.render(time).use { still.writeBytes(it.encodeToData()!!.bytes) }
            }
        } finally { scene.close() }
    }
}
