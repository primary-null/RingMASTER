@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import local.ringleader.core.*
import java.nio.file.Files
import kotlin.test.*

class ResponsiveLayoutTest {
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = buildList {
        fun visit(n: SemanticsNode) { add(n); n.children.forEach(::visit) }
        scene.semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun assertWhole(scene: ImageComposeScene, label: String, width: Int, height: Int) {
        val node = assertNotNull(nodes(scene).firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.equals(label, true) } == true }, label)
        val b = node.boundsInRoot
        assertTrue(b.width > 10 && b.height > 5 && b.left >= 0 && b.top >= 0 && b.right <= width && b.bottom <= height, "$label outside viewport: $b")
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        layouts.forEach { layout ->
            // Skia's intrinsic width can round a fraction above the constrained width even when every glyph fits.
            assertFalse(layout.didOverflowHeight, "$label loses vertical text")
            for (line in 0 until layout.lineCount) {
                assertTrue(layout.getLineRight(line) <= layout.size.width + 1f, "$label loses horizontal text")
                assertFalse(layout.isLineEllipsized(line), "$label is ellipsized")
            }
        }
    }
    @Test fun telemetryKeepsDisconnectWholeBesideExpandedRailAndLargeText() = runSceneTest {
        val root = Files.createTempDirectory("responsive-layout")
        val workspace = Workspace(this)
        try {
            val lab = MatrixLab(root)
            for (scale in listOf(1f,1.5f)) {
                val scene = ImageComposeScene(650,400,Density(1f,scale),coroutineContext=coroutineContext) {
                    MaterialTheme(colorScheme=ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) { TelemetryStrip(lab.state(lab.vm),workspace,{}) } }
                }
                try { MatrixDriver().frames(scene,6); assertWhole(scene,"Disconnect",650,400); assertWhole(scene,"Permissions",650,400) }
                finally { scene.close() }
            }
        } finally { workspace.close(); root.toFile().deleteRecursively() }
    }
    @Test fun inspectorKeepsWrappedActionsWhole() = runSceneTest {
        val root = Files.createTempDirectory("responsive-inspector")
        val workspace = Workspace(this)
        try {
            val lab = MatrixLab(root)
            for (scale in listOf(1f,1.5f)) {
                val scene = ImageComposeScene(1280,800,Density(1f,scale),coroutineContext=coroutineContext) {
                    MaterialTheme(colorScheme=ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                        InventoryScreen(lab.state(lab.vm),workspace,"Sample","",{},"All",{},{},{},lab.pins,lab.origin)
                    } }
                }
                try { MatrixDriver().frames(scene,6); for(label in listOf("Open details","Console","Close","Hard stop")) assertWhole(scene,label,1280,800) }
                finally { scene.close() }
            }
        } finally { workspace.close(); root.toFile().deleteRecursively() }
    }
    @Test fun everyGuestTabRemainsReachableAtLargeText() = runSceneTest {
        val tabs=listOf("Summary","Console","Hardware","Snapshots","Backups","Bluetooth","Actions")
        val scene=ImageComposeScene(420,220,Density(1f,1.5f),coroutineContext=coroutineContext) {
            MaterialTheme(colorScheme=ringColors) { TechTabs(tabs,"Summary",{}) }
        }
        try {
            val driver=MatrixDriver(); driver.frames(scene,5)
            for (tab in tabs) assertWhole(scene,tab,420,220)
        } finally { scene.close() }
    }
    @Test fun shortStoragePageCanScrollToItsContentsActions() = runSceneTest {
        val root=Files.createTempDirectory("responsive-storage")
        val workspace=Workspace(this)
        try {
            val lab=MatrixLab(root)
            val key=StorageKey(lab.origin,"pve-east","local-lvm")
            val state=lab.state().copy(storageSelection=key, storageContent=ReadSection.loaded(StorageContentView(key,null,demoStorageContent(lab.overview.storage.first { it.id=="local-lvm" })),lab.now,sample=true))
            val scene=ImageComposeScene(960,520,Density(2f,1.5f),coroutineContext=coroutineContext) {
                MaterialTheme(colorScheme=ringColors) { StoragePage(state,workspace) }
            }
            try {
                val driver=MatrixDriver();driver.frames(scene,5)
                val page=assertNotNull(nodes(scene).firstOrNull { it.config.contains(SemanticsActions.ScrollBy) })
                assertTrue(page.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!!.maxValue() > 0f)
                page.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f,500f)
                driver.frames(scene,6)
                assertWhole(scene,"Refresh",960,520)
            } finally { scene.close() }
        } finally { workspace.close();root.toFile().deleteRecursively() }
    }

}
