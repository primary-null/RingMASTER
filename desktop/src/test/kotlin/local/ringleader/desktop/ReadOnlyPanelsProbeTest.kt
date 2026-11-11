package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import local.ringleader.core.DEMO_REMOTE_KEY
import local.ringleader.core.PDM_KIND
import local.ringleader.core.PERMISSIONS_DENIED
import local.ringleader.core.PermissionsView
import local.ringleader.core.ReadSection
import local.ringleader.core.RecordedStorage
import local.ringleader.core.StorageContentView
import local.ringleader.core.StorageKey
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import local.ringleader.core.demoStorageContent

/**
 * Opt-in render of the 0.21.4 read-only panels with demo data: RINGMASTER_PROBE=1 RINGMASTER_PROBE_DIR=<dir>.
 * Never contacts a server. Every panel is drawn from a [WorkspaceState] built here, so the PNGs show exactly what the parent will wire.
 */
class ReadOnlyPanelsProbeTest {
    private val gib = 1024L * 1024 * 1024
    private val at = Instant.parse("2026-11-09T14:03:22Z")

    /**
     * Runs work inline and never resumes a delay. The log panel's follow loop waits five seconds between reads; with real time
     * that wake-up lands on another thread while a scene is being drawn, and Compose refuses a scene touched from two threads.
     */
    @OptIn(InternalCoroutinesApi::class)
    private object FrozenTime : CoroutineDispatcher(), Delay {
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {}
    }

    private fun shot(dir: File, name: String, widthDp: Int, heightDp: Int, light: Boolean = false, content: @Composable () -> Unit) {
        Appearance.preview(light)
        try {
            val scene = ImageComposeScene((widthDp * 1.5f).toInt(), (heightDp * 1.5f).toInt(), Density(1.5f)) {
                MaterialTheme(ringColors) {
                    CompositionLocalProvider(LocalReduceMotion provides true) {
                        Box(Modifier.fillMaxSize().background(Tech.Canvas).padding(16.dp)) { content() }
                    }
                }
            }
            try {
                repeat(6) { scene.render(it * 100_000_000L) }
                File(dir, "$name${if (light) "-light" else ""}.png").writeBytes(scene.render(1_000_000_000L).encodeToData()!!.bytes)
            } finally { scene.close() }
        } finally { Appearance.preview(false) }
    }

    @Test fun render() {
        if (System.getenv("RINGMASTER_PROBE") != "1") return
        val dir = File(System.getenv("RINGMASTER_PROBE_DIR") ?: return).apply { mkdirs() }
        val scope = CoroutineScope(FrozenTime + SupervisorJob())
        val workspace = Workspace(scope)
        try {
            workspace.exploreDemo(); workspace.setLive(false)
            workspace.loadPermissions()
            workspace.loadNodeServices("demo-node-a")
            workspace.loadNodeUpdates("demo-node-a")
            workspace.openNodeSyslog("demo-node-a")
            workspace.selectStorage("demo-node-a", "shared-data")
            val demo = workspace.state.value

            // Permissions: the demo identity at dialog width and at inspector width, a refused read, and the light theme.
            shot(dir, "permissions-wide", 860, 1080) { PermissionsPanel(demo, workspace) }
            shot(dir, "permissions-narrow", 380, 1700) { PermissionsPanel(demo, workspace) }
            shot(dir, "permissions-wide", 860, 1080, light = true) { PermissionsPanel(demo, workspace) }
            // Inside a TechDialog, which measures intrinsic heights and has its own frame.
            shot(dir, "permissions-dialog", 940, 900) {
                TechDialog("Permissions", {}, width = 860.dp, subtitle = "What this identity may do on the connected server.", actions = { TechAction("Close") {} }) {
                    PermissionsPanel(demo, workspace, framed = false)
                }
            }
            val denied = demo.copy(permissions = ReadSection<PermissionsView>().failed(PERMISSIONS_DENIED, true))
            shot(dir, "permissions-denied", 520, 420) { PermissionsPanel(denied, workspace) }
            shot(dir, "permissions-pdm", 520, 420) { PermissionsPanel(denied.copy(kind = PDM_KIND, permissions = ReadSection()), workspace) }

            // Node tools at inspector width: every tab, and the log at page width.
            for (tab in NODE_TOOL_TABS) shot(dir, "node-tools-${tab.lowercase()}", 380, if (tab == "Log") 720 else 640) {
                Box(Modifier.width(348.dp)) { NodeToolsPanel(demo, workspace, "demo-node-a", initialTab = tab) }
            }
            shot(dir, "node-log-wide", 980, 560) { NodeSyslogPanel(demo, workspace, "demo-node-a", height = 380.dp) }
            shot(dir, "node-tools-log", 380, 720, light = true) { Box(Modifier.width(348.dp)) { NodeToolsPanel(demo, workspace, "demo-node-a", initialTab = "Log") } }
            val refusedLog = demo.copy(nodeSyslog = ReadSection<local.ringleader.core.SyslogView>().failed(local.ringleader.core.NODE_SYSLOG_DENIED, true))
            shot(dir, "node-log-denied", 380, 330) { Box(Modifier.width(348.dp)) { NodeSyslogPanel(refusedLog, workspace, "demo-node-a", height = 120.dp) } }

            // Storage: a richer inventory than the demo's, so every content type, the PBS verification marks and "no usage recorded" show.
            val local = RecordedStorage(DEMO_REMOTE_KEY, "demo-node-a", "local", false, 34 * gib, 100 * gib, "available", "dir", "iso,vztmpl,backup,snippets,import")
            val pbs = RecordedStorage(DEMO_REMOTE_KEY, "demo-node-a", "pbs-main", true, 410 * gib, 1000 * gib, "available", "pbs", "backup")
            val remoteLocal = RecordedStorage(DEMO_REMOTE_KEY, "demo-node-b", "local", false, null, null, "unknown", "dir", "iso,vztmpl,backup,snippets")
            val overview = demo.overview!!.copy(storage = demo.overview!!.storage + listOf(local, pbs, remoteLocal))
            fun storageState(row: RecordedStorage): WorkspaceState {
                val key = StorageKey(row.remote, row.node, row.id)
                return demo.copy(overview = overview, storageSelection = key,
                    storageContent = ReadSection.loaded(StorageContentView(key, null, demoStorageContent(row)), at, sample = true))
            }
            val images = overview.storage.first { it.id == "local-lvm" }
            shot(dir, "storage-local", 1280, 760) { StoragePage(storageState(local), workspace) }
            shot(dir, "storage-pbs", 1280, 560) { StoragePage(storageState(pbs), workspace) }
            shot(dir, "storage-disks", 1280, 560) { StoragePage(storageState(overview.storage.first { it.id == "shared-data" }), workspace) }
            shot(dir, "storage-narrow", 700, 900) { StoragePage(storageState(local), workspace) }
            shot(dir, "storage-none", 1280, 520) { StoragePage(demo.copy(overview = overview, storageSelection = null, storageContent = ReadSection()), workspace) }
            shot(dir, "storage-local", 1280, 760, light = true) { StoragePage(storageState(local), workspace) }
            shot(dir, "storage-images", 1280, 420) { StoragePage(storageState(images), workspace) }
        } finally {
            workspace.close()
            scope.cancel()
        }
    }
}
