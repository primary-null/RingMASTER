@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
@file:Suppress("INVISIBLE_MEMBER","INVISIBLE_REFERENCE")
package local.ringleader.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import local.ringleader.core.*
import org.junit.jupiter.api.Test
import kotlin.test.*
import java.io.File

class StorageStatesTest {
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        val all=mutableListOf<SemanticsNode>();fun visit(n: SemanticsNode) { all+=n;n.children.forEach(::visit) }
        scene.semanticsOwners.forEach {visit(it.rootSemanticsNode)};return all
    }
    private fun text(n: SemanticsNode)=n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") {it.text}.orEmpty()
    @Test fun stateMatrix() = runSceneTest {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val workspace=Workspace(scope);workspace.exploreDemo()
        val selected=workspace.state.value.overview!!.storage.first {it.id=="shared-data"}
        workspace.selectStorage(selected.node,selected.id);workspace.loadStorageConfig();workspace.loadStorageHistory(selected.node,selected.id)
        yield()
        val base=workspace.state.value;val overview=base.overview!!;val key=base.storageSelection!!
        val loaded=base.storageContent.value!!
        val variants=linkedMapOf(
            "shared-backups" to base,
            "two" to base.copy(overview=overview.copy(storage=overview.storage.filter {it.id!="shared-data"}),storageSelection=null),
            "thirty" to base.copy(overview=overview.copy(storage=(1..30).map { selected.copy(id="pool-$it",node="demo-node-${if(it<=18) 'a' else 'b'+(it-19)%3}",shared=false,diskBytes=it*1_000_000_000L) }),storageSelection=null),
            "differing" to base.copy(overview=overview.copy(storage=overview.storage.map {if(it.id=="shared-data" && it.node!=selected.node) it.copy(diskBytes=it.diskBytes!!+1_000_000_000L) else it})),
            "offline-unknown" to base.copy(overview=overview.copy(members=overview.members.map {it.copy(status="offline")},storage=overview.storage.map {it.copy(diskBytes=null,maxDiskBytes=null)})),
            "loading" to base.copy(storageContent=ReadSection<StorageContentView>().started()),
            "denied" to base.copy(storageContent=ReadSection<StorageContentView>().failed(STORAGE_CONTENT_DENIED,true)),
            "empty" to base.copy(storageContent=ReadSection.loaded(loaded.copy(volumes=emptyList()),java.time.Instant.parse("2026-11-12T10:00:00Z"))),
            "error" to base.copy(storageContent=ReadSection<StorageContentView>().failed("Contents could not be read.")),
            "stale" to base.copy(stale=true,storageContent=base.storageContent.failed("Refresh failed.")),
            "PDM" to base.copy(demo=false,kind=PDM_KIND,storageSelection=null),
            "partial" to base.copy(demo=false,address=DEMO_REMOTE_KEY,permissions=ReadSection.loaded(PermissionsView(PermissionDump(emptyMap()),PermissionIdentity.Token),java.time.Instant.parse("2026-11-12T10:00:00Z"))),
            "high" to base.copy(overview=overview.copy(storage=overview.storage.map {it.copy(diskBytes=(it.maxDiskBytes!!*.96).toLong())})),
            "unknown-sizes" to base.copy(storageContent=ReadSection.loaded(loaded.copy(volumes=loaded.volumes.map {it.copy(sizeBytes=null,approximateSizeBytes=null)}),java.time.Instant.parse("2026-11-12T10:00:00Z"))),
            "history-denied" to base.copy(storageView=base.storageView.copy(history=ReadSection<StorageHistoryView>().failed(STORAGE_HISTORY_DENIED,true))),
            "config-denied" to base.copy(storageView=base.storageView.copy(config=ReadSection<List<StorageConfigRow>>().failed(STORAGE_CONFIG_DENIED,true))),
            "showcase" to base,
            "thin" to base.copy(overview=overview.copy(storage=overview.storage.map {if(it.id==selected.id) it.copy(pluginType="lvmthin") else it})),
        )
        for((name,state) in variants) {
            Appearance.preview(ThemeMode.Dark,showcase=name=="showcase")
            val scene=ImageComposeScene(1600,1000,coroutineContext=coroutineContext) {
                MaterialTheme(colorScheme=ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) { ChartLensHost(state,workspace) {StoragePage(state,workspace)} } }
            }
            try {
                repeat(4) {scene.render(it*100_000_000L).close();yield()}
                val texts=nodes(scene).map(::text)
                when(name) {
                    "denied" -> {assertTrue(texts.any {it.contains("Not permitted")});assertFalse(texts.contains("WHAT USES THE SPACE"))}
                    "thirty" -> assertTrue(texts.any {it=="SEARCH STORAGE · NODE · TYPE"})
                    "differing" -> assertTrue(texts.any {it.contains("Differing recorded samples")})
                    "offline-unknown" -> assertTrue(texts.any {it.contains("No usage recorded")})
                    "partial" -> assertTrue(texts.any {it.contains("hidden:")})
                    "empty" -> assertTrue(texts.any {it==STORAGE_EMPTY_FILTERED})
                    "stale" -> assertTrue(texts.any {it.contains("Inventory is stale")})
                }
                if(name=="showcase") assertTrue(nodes(scene).any {it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Hidden")==true})
                if(name=="shared-backups") {
                    val expand=nodes(scene).first {text(it)=="OPEN LARGE" && it.config.getOrNull(SemanticsActions.OnClick)!=null}
                    expand.config[SemanticsActions.OnClick].action!!.invoke()
                    repeat(6) {scene.render(700_000_000L+it*100_000_000L).close();yield()}
                    assertTrue(nodes(scene).any {text(it)=="STORAGE USAGE"})
                }
                System.getenv("STORAGE_SHOTS")?.let { folder ->
                    val file=File(folder,"state-$name.png");file.parentFile.mkdirs();scene.render(600_000_000L).use {file.writeBytes(it.encodeToData()!!.bytes)}
                }
            } finally {scene.close()}
        }
        Appearance.preview(false);scope.cancel()
    }
}
