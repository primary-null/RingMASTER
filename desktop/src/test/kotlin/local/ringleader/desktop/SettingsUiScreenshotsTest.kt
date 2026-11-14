package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import local.ringleader.core.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Offline settings sections plus key application screens at accessibility text sizes. */
class SettingsUiScreenshotsTest {
    @Test fun screenshots() {
        if (System.getenv("RINGMASTER_SCREENSHOTS") != "1") return
        val phase = System.getenv("RINGMASTER_UI_PHASE") ?: "settings"
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        workspace.exploreDemo()
        workspace.select(workspace.state.value.overview!!.guests.first())
        workspace.inspectGuest("hour")
        val updates = UpdateChecker(AppVersion.value, UpdatePreferences(Files.createTempDirectory("settings-updates").resolve("updates.properties")))
        val state = workspace.state.value
        val desk = ProfileDesk(workspace, ProfileStore(Files.createTempDirectory("settings-ui").resolve("profiles.json")))
        Appearance.preview(false)
        try {
            for (scale in listOf(1f, 1.3f, 1.5f)) for ((w, h) in listOf(1920 to 1080, 1600 to 900, 960 to 640)) {
                Appearance.preview(false, textScale = scale)
                Appearance.useDisplay(DisplayInfo("settings-fixture", 1920, 1080, 1.0, 60, java.awt.Rectangle(0, 0, 1920, 1080), java.awt.Rectangle(0, 0, 1920, 1040)))
                val screens = linkedMapOf<String, @Composable () -> Unit>(
                    "desk" to { DeskScreen(state, desk.state.value, desk) {} },
                    "fleet" to { InventoryScreen(state, workspace, null, "", {}, "All", {}, {}, {}) },
                    "guest" to { GuestDetailPage(state, workspace, {}, { _, _ -> }) },
                    "activity" to { ActivityScreen(state, workspace, null, {}, {}) }
                )
                SettingsSection.entries.forEach { section ->
                    for (scroll in if (section in listOf(SettingsSection.Appearance, SettingsSection.Size, SettingsSection.About)) listOf(0, 10000) else listOf(0)) {
                    screens["settings-${section.name.lowercase()}${if (scroll > 0) "-bottom" else ""}"] = {
                        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            SettingsSheet(state, workspace, section, {}, {}, {},
                                Modifier.size(minOf(900.dp, maxWidth * .92f), minOf(640.dp, maxHeight * .92f)), initialScroll = scroll, updatesChecker = updates)
                        }
                    }
                }
                }
                for ((name, content) in screens) {
                    val scene = ImageComposeScene(w, h, Density(1f, scale)) {
                        MaterialTheme(colorScheme = ringColors) {
                            CompositionLocalProvider(LocalReduceMotion provides true) {
                                Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                                    if (name.startsWith("settings-")) content() else Row {
                                        NavigationRail(state, "Fleet", "Screenshot", workspace, initialExpanded = false) {}
                                        Box(Modifier.weight(1f).padding(16.dp)) { content() }
                                    }
                                }
                            }
                        }
                    }
                    try {
                        val file = File("../artifacts/ui/$phase/text-${(scale * 100).toInt()}-$name-${w}x$h.png")
                        file.parentFile.mkdirs()
                        file.writeBytes(scene.render().encodeToData()!!.bytes)
                    } finally { scene.close() }
                }
            }
        } finally { desk.close(); workspace.close(); scope.cancel(); Appearance.useBrowserDisplay(); Appearance.preview(false) }
    }

    @Test fun updatesScreenshots() {
        if (System.getenv("RINGMASTER_SCREENSHOTS") != "1") return
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        val root = Files.createTempDirectory("update-ui")
        val checker = UpdateChecker(AppVersion.value, UpdatePreferences(root.resolve("updates.properties")))
        val manifest = UpdateManifest("0.22.0", java.time.Instant.parse("2026-11-10T00:00:00Z"),
            "A signed release with clearer settings and safer configuration. Plain text <b>stays plain text</b>.", null, null, emptyList())
        val offer = OfferedUpdate(manifest, UpdateAsset("debian-13-amd64-deb", "https://example.com/app.deb", "a".repeat(64), 1048576))
        val states = listOf(UpdateState.Disabled, UpdateState.NotConfigured, UpdateState.NeedsConsent, UpdateState.Idle(null), UpdateState.Checking,
            UpdateState.UpToDate(), UpdateState.UpToDate("No release has been published yet."), UpdateState.Available(offer),
            UpdateState.Skipped("0.22.0"), UpdateState.Failed("The update feed could not be verified."), UpdateState.Failed("No build for this system.", RELEASE_PAGE))
        Appearance.preview(false)
        try {
            for ((w,h) in listOf(1600 to 900,960 to 640)) for ((index,state) in states.withIndex()) {
                val scene=ImageComposeScene(w,h,Density(1f)) {
                    MaterialTheme(colorScheme=ringColors) {
                        CompositionLocalProvider(LocalReduceMotion provides true) {
                            Surface(Modifier.fillMaxSize(),color=Tech.Canvas,contentColor=Tech.Ink) {
                                BoxWithConstraints(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                                    SettingsSheet(workspace.state.value,workspace,SettingsSection.Updates,{},{},{},
                                        Modifier.size(minOf(900.dp,maxWidth*.92f),minOf(640.dp,maxHeight*.92f)),updatesChecker=checker,updatePreview=state)
                                }
                            }
                        }
                    }
                }
                try {
                    val file=File("../artifacts/ui/updates-sol/updates-${index}-${state.javaClass.simpleName}-${w}x$h.png")
                    file.parentFile.mkdirs(); file.writeBytes(scene.render().encodeToData()!!.bytes)
                } finally { scene.close() }
            }
        } finally { workspace.close(); scope.cancel(); Appearance.preview(false) }
    }

    @Test fun textScaleIsBoundedAndSnapped() {
        assertEquals(.85f, Appearance.normalizeTextScale(.5f))
        assertEquals(1.5f, Appearance.normalizeTextScale(2f))
        assertEquals(1.15f, Appearance.normalizeTextScale(1.13f))
        assertEquals(1f, Appearance.normalizeTextScale(Float.NaN))
        assertEquals(1f, Appearance.normalizeTextScale(Float.POSITIVE_INFINITY))
    }
}
