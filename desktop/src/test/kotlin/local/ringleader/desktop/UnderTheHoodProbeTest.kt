package local.ringleader.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import local.ringleader.core.*
import java.io.File
import kotlin.test.Test

class UnderTheHoodProbeTest {
    @Test fun render() {
        if(System.getenv("RINGMASTER_HOOD_PROBE")!="1") return
        Trace.clear()
        repeat(45) { i ->
            val span=Trace.begin(if(i%5==0) TraceSource.PdmApi else TraceSource.PveApi,"GET /cluster/resources",mapOf("demo" to "true","url" to "https://demo.invalid:8006/api2/json/cluster/resources"))
            Thread.sleep(listOf(5L,12L,41L,70L,180L)[i%5])
            span.end(status=if(i%13==0) 503 else 200,bytesIn=18432)
            if(i%3==0) Trace.emit(TraceSource.Task,"poll","Task polling · running",mapOf("demo" to "true"))
        }
        Trace.emit(TraceSource.Console,"open","VNC session · demo guest",mapOf("demo" to "true"))
        val session=Trace.begin(TraceSource.Console,"VNC · demo guest",mapOf("demo" to "true"),session=true)
        session.bytes(1938274,1024)
        Trace.liveLoop.value=TraceLiveLoop("demo",java.time.Instant.now().plusSeconds(5),5000)
        try {
            val dir=File(System.getenv("RINGMASTER_PROBE_DIR") ?: "../artifacts/under-the-hood").apply { mkdirs() }
            listOf(1920 to 1080,1280 to 800).forEach { (w,h) ->
                javax.swing.SwingUtilities.invokeAndWait {
                val scene=ImageComposeScene(w,h,Density(1f)) { CompositionLocalProvider(LocalHoodProbe provides true, LocalReduceMotion provides true) { MaterialTheme(ringColors) { UnderTheHoodScreen(WorkspaceState(demo=true)) } } }
                try { File(dir,"hood-${w}x$h.png").writeBytes(scene.render().encodeToData()!!.bytes) } finally { scene.close() }
                }
            }
        } finally { session.end(); Trace.liveLoop.value=TraceLiveLoop() }
    }
}
