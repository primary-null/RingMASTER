@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
@file:Suppress("INVISIBLE_MEMBER","INVISIBLE_REFERENCE")
package local.ringleader.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import local.ringleader.core.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class StorageViewTest {
    @Test fun overviewFiltersKeepGuestAndVolumeConstraints() {
        val row=demoOverview.storage.first()
        val volumes=demoStorageContent(row)
        val iso=volumes.first { it.content=="iso" }
        assertEquals(listOf(iso),storageFilteredVolumes(volumes,VOLUME_TYPE_ALL,null,iso.volid))
        assertEquals(emptyList(),storageFilteredVolumes(volumes,"backup",null,iso.volid))
        assertTrue(storageFilteredVolumes(volumes,"iso",999,null).isEmpty())
    }
    @Test fun demoVisibilityCannotBecomeRealCompleteness() {
        val workspace=Workspace(CoroutineScope(Dispatchers.Unconfined))
        workspace.exploreDemo()
        val state=workspace.state.value
        assertEquals(StorageVisibility.Unknown,storageVerdict(state,state.overview!!.storage.first()))
        val subject=LensSubject.StorageSubject("demo-node-a","local")
        assertTrue(lensServerRanges(state,subject,null))
        assertFalse(lensServerRanges(state.copy(demo=false,kind=PDM_KIND),subject,null))
        assertNull(liveMetricFor(listOf("used","total")))
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        val all=mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) { all+=node; node.children.forEach(::visit) }
        scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }; return all
    }
    private fun text(node: SemanticsNode)=node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    private suspend fun step(scene: ImageComposeScene) { repeat(4) { scene.render(System.nanoTime()).close(); yield() } }
    @Test fun overviewFilterChangesContentsWithoutReadingAgain() = runSceneTest {
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        val workspace=Workspace(scope); workspace.exploreDemo()
        val row=workspace.state.value.overview!!.storage.first()
        workspace.selectStorage(row.node,row.id)
        val state=workspace.state.value
        val scene=ImageComposeScene(1600,1000,coroutineContext=coroutineContext) {
            MaterialTheme(colorScheme=ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) { StoragePage(state,workspace) } }
        }
        try {
            step(scene)
            val chip=nodes(scene).first { text(it).startsWith("ISO ") && it.config.getOrNull(SemanticsActions.OnClick)!=null }
            chip.config[SemanticsActions.OnClick].action!!.invoke(); step(scene)
            assertTrue(nodes(scene).any { text(it).contains("local:iso/") })
            assertFalse(nodes(scene).any { text(it).contains("local:vztmpl/") })
            assertEquals(state.storageContent,workspace.state.value.storageContent)
        } finally {scene.close();scope.cancel()}
    }
    @Test fun deniedListingKeepsUsageButHidesBreakdown() = runSceneTest {
        val scope=CoroutineScope(coroutineContext+SupervisorJob()); val workspace=Workspace(scope);workspace.exploreDemo()
        val row=workspace.state.value.overview!!.storage.first(); workspace.selectStorage(row.node,row.id)
        val state=workspace.state.value.copy(storageContent=ReadSection<StorageContentView>().failed(STORAGE_CONTENT_DENIED,true))
        val scene=ImageComposeScene(1600,1000,coroutineContext=coroutineContext) { MaterialTheme(colorScheme=ringColors) { StoragePage(state,workspace) } }
        try {step(scene);val texts=nodes(scene).map(::text);assertTrue(texts.any {it=="USAGE"});assertFalse(texts.any {it=="WHAT USES THE SPACE"});assertTrue(texts.any {it.contains("Not permitted")})} finally {scene.close();scope.cancel()}
    }
    @Test fun arrowsDoNotReadUntilEnter() = runSceneTest {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val workspace=Workspace(scope);workspace.exploreDemo()
        val scene=ImageComposeScene(1600,1000,coroutineContext=coroutineContext) {
            val state by workspace.state.collectAsState()
            MaterialTheme(colorScheme=ringColors) {StoragePage(state,workspace)}
        }
        try {
            step(scene)
            val card=nodes(scene).first { text(it).contains("shared-data") && it.config.getOrNull(SemanticsActions.OnClick)!=null }
            val at=card.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press,at);scene.sendPointerEvent(PointerEventType.Release,at);step(scene)
            val before=workspace.state.value.storageSelection
            scene.sendKeyEvent(KeyEvent(Key.MoveEnd,KeyEventType.KeyDown));scene.sendKeyEvent(KeyEvent(Key.MoveEnd,KeyEventType.KeyUp));step(scene)
            assertEquals(before,workspace.state.value.storageSelection)
            scene.sendKeyEvent(KeyEvent(Key.Enter,KeyEventType.KeyDown));scene.sendKeyEvent(KeyEvent(Key.Enter,KeyEventType.KeyUp));step(scene)
            assertEquals("local-lvm",workspace.state.value.storageSelection?.storage)
        } finally {scene.close();scope.cancel()}
    }
    @Test fun conditionalVisibilityAndRingSemantics() = runSceneTest {
        val scene=ImageComposeScene(800,500,coroutineContext=coroutineContext) { MaterialTheme(colorScheme=ringColors) {
            Column { StorageVisibilityNote(StorageVisibility.Complete);StorageRing(.95,FillLevel.Critical);StorageVisibilityNote(StorageVisibility.Unknown) }
        } }
        try {
            step(scene)
            assertEquals(1,nodes(scene).count {text(it)==STORAGE_VISIBILITY_UNKNOWN_LINE})
            assertTrue(nodes(scene).any {it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Recorded usage 95%")==true})
            val why=nodes(scene).first {text(it)=="WHY?" && it.config.getOrNull(SemanticsActions.OnClick)!=null}
            why.config[SemanticsActions.OnClick].action!!.invoke();step(scene)
            assertTrue(nodes(scene).any {text(it)==STORAGE_VISIBILITY_WHY})
        } finally {scene.close()}
    }
}
