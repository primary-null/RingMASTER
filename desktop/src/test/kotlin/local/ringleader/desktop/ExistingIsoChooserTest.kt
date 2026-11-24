package local.ringleader.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.SupervisorJob
import local.ringleader.core.*
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.*

class ExistingIsoChooserTest {
    private val local = StorageChoice("node-a", "local", "dir", setOf("iso"), false, null, null, "available")
    private val shared = StorageChoice("node-a", "shared", "dir", setOf("iso"), true, null, null, "available")
    private val iso = StorageVolume("local:iso/alpine.iso", "iso", null, 1, null, null, null, null, null, false, null)
    private val other = StorageVolume("shared:iso/debian.iso", "iso", null, 1, null, null, null, null, null, false, null)

    @Test fun choicesStayWithinNodeAndRequireFreshExactListings() {
        val context = CreateContext(storages = listOf(local, shared, local.copy(node = "node-b", id = "other"),
            local.copy(id = "offline", status = "offline"), local.copy(id = "disk", content = setOf("images"))))
        assertEquals(listOf("local", "shared"), isoPickerStorages(context, "node-a").map { it.id })
        val session = CreateSession(NewGuestKind.Vm, "node-a", 1, volumes = mapOf(
            ("local" to "iso") to ReadSection.loaded(listOf(iso), Instant.EPOCH),
            ("shared" to "iso") to ReadSection(value = listOf(other), error = "failed", stale = true),
        ))
        assertEquals(listOf(iso.volid), currentIsoChoices(session, listOf(local, shared)).map { it.second.volid })
        assertTrue(currentIsoChoices(session.copy(volumes = emptyMap()), listOf(local)).isEmpty())
    }

    @Test fun selectingExistingIsReadOnlyAndRefreshRetriesTheSelectedScope() = runSceneTest {
        val look = storageLooks().first { it.id == "dark-960x640" }
        val session = CreateSession(NewGuestKind.Vm, "node-a", 7, generation = 4, volumes = mapOf(
            ("local" to "iso") to ReadSection.loaded(listOf(iso), Instant.EPOCH),
            ("shared" to "iso") to ReadSection<List<StorageVolume>>(error = "failed"),
        ))
        var ready: StorageMediaReady? = null
        var inventoryRefreshes = 0
        val refreshed = mutableListOf<String>()
        val scene = storageScene(look, coroutineContext + SupervisorJob()) {
            ExistingIsoChooserContent(session, listOf(local, shared), "local", true, {}, { inventoryRefreshes++ },
                { refreshed += it }, { ready = it })
        }
        val d = StorageSceneDriver(scene, look)
        try {
            d.settle()
            d.click("local"); d.settle(); d.click("All available ISO storages"); d.settle()
            assertTrue(d.matches("Could not read shared", false).isNotEmpty())
            d.click("Choose…"); d.settle(); d.click("alpine.iso · local"); d.settle()
            d.click("Use selected ISO"); d.settle()
            assertEquals(iso.volid, ready?.volid)
            assertEquals(7L, ready?.epoch); assertEquals(4L, ready?.generation)
            d.click("Retry shared"); d.settle(); assertEquals(listOf("shared"), refreshed)
            d.click("Refresh ISO list"); d.settle()
            assertEquals(1, inventoryRefreshes); assertEquals(listOf("shared", "local", "shared"), refreshed)
        } finally { scene.close() }
    }
}
