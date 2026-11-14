@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class U1HarnessRegressionTest {
    @Test fun resumedEvidenceRequiresTheSameActualSource() {
        val root = java.nio.file.Files.createTempDirectory("matrix-source-test").toFile()
        try {
            val source = java.io.File(root, "desktop/src/main/View.kt").apply { parentFile.mkdirs(); writeText("before") }
            val output = java.io.File(root, "evidence")
            val before = matrixSourceFingerprint(root)
            bindMatrixSource(output, before)
            bindMatrixSource(output, before)
            source.writeText("after")
            val after = matrixSourceFingerprint(root)
            assertFalse(before == after)
            assertFailsWith<IllegalStateException> { bindMatrixSource(output, after) }
            assertEquals(before, java.io.File(output, "source-sha256.txt").readText().trim())
            val legacy = java.io.File(root, "legacy").apply { mkdirs() }
            java.io.File(legacy, "valid-frames.tsv").writeText("old evidence")
            assertFailsWith<IllegalStateException> { bindMatrixSource(legacy, before) }
        } finally { root.deleteRecursively() }
    }

    @Test fun geometrySeparatesNativeWindowTitleBarContentAndOsPixels() {
        val dark = matrixLooks.single { it.name == "dark" }
        val minimum = matrixGeometry(matrixSize("min-960x640"), dark)
        assertEquals(960, minimum.sceneWidthPx)
        assertEquals(640, minimum.sceneHeightPx)
        assertEquals(32f, minimum.titleBarLogical)
        assertEquals(608f, minimum.contentHeightLogical)
        assertEquals(608f, minimum.layoutContentHeightDp)
        assertEquals(960, minimum.minimumWidthLogical)
        assertEquals(640, minimum.minimumHeightLogical)
        assertTrue(minimum.reachable)

        val os125 = matrixGeometry(matrixSize("min-960x640"), matrixLooks.single { it.name == "dark-os125" })
        assertEquals(1200, os125.sceneWidthPx)
        assertEquals(800, os125.sceneHeightPx)
        assertEquals(1.25f, os125.density)
        assertEquals(960f, os125.layoutWidthDp)
        assertEquals(608f, os125.layoutContentHeightDp)

        val ui200 = matrixGeometry(matrixSize("laptop-1366x728"), matrixLooks.single { it.name == "dark-ui200" })
        assertEquals(1366, ui200.minimumWidthLogical)
        assertEquals(728, ui200.minimumHeightLogical)
        assertEquals(64f, ui200.titleBarLogical)
        assertEquals(664f, ui200.contentHeightLogical)
        assertEquals(332f, ui200.layoutContentHeightDp)
        assertTrue(ui200.reachable)

        val quarter = matrixGeometry(matrixSize("stress-quarter-960x520"), dark)
        assertFalse(quarter.reachable)
        assertTrue(quarter.reachabilityReason.contains("below minimum"))

        val ui70 = matrixGeometry(matrixSize("native-min-ui70-672x448"), matrixLooks.single { it.name == "dark-ui70" })
        assertEquals(672, ui70.minimumWidthLogical)
        assertEquals(448, ui70.minimumHeightLogical)
        assertTrue(ui70.reachable)
    }

    @Test fun recipeTableHasNoDuplicateCaseGeometryMasqueradingAsCoverage() {
        assertEquals(matrixLooks.map { it.name }.toSet(), matrixSizesForLook.keys)
        val knownSizes = matrixSizes.map { it.name }.toSet()
        matrixSizesForLook.values.flatten().forEach { assertTrue(it in knownSizes, "unknown viewport $it") }
        matrixLooks.forEach { look ->
            val geometries = matrixSizesForLook.getValue(look.name).map { matrixGeometry(matrixSize(it), look) }
            assertEquals(geometries.size, geometries.map { it.visualSignature }.toSet().size, "duplicate geometry in ${look.name}")
        }
        assertEquals(setOf("tall-column-at-minimum-width"), matrixSize("half-960x1040").aliases)
        assertFalse(matrixSizes.any { it.name.contains("tall-column-at-minimum-width") }, "the alias must not create a duplicate frame")
    }

    @Test fun everyNamedBriefStateHasAnExplicitSemanticContract() {
        assertTrue(u1RequiredStateNames.size >= 39)
        u1RequiredStateNames.forEach { state ->
            val contract = matrixStateContract(state)
            assertTrue(contract.expectations.isNotEmpty(), "$state has no state-preparation assertion")
        }
        assertTrue(matrixStateContract("guest-ct-console").limitation.contains("does not prove a live terminal"))
        assertEquals(MatrixGroup.Create, matrixStateContract("create-vm").group)
        assertEquals(MatrixGroup.Create, matrixStateContract("create-ct").group)
    }

    @Test fun everyDesktopSettingsSectionHasARequiredRealContentContract() {
        SettingsSection.entries.forEach { section ->
            val name = "settings-${section.name.lowercase()}"
            assertTrue(name in u1RequiredStateNames, "$name is absent from required coverage")
            val contract = matrixStateContract(name)
            assertEquals(MatrixGroup.Settings, contract.group)
            assertTrue(contract.expectations.any { it.text == section.label }, "$name has no heading contract")
        }
        assertTrue(matrixStateContract("settings-background").expectations.any {
            it.text == "Keep running in the notification area when the window is closed"
        }, "the background fixture must render the desktop control, not the no-resident fallback")
    }

    @Test fun wrongPreparedStateFailsBeforeItCanBeAccepted() = runSceneTest {
        val expected = MatrixShot(
            name = "fixture",
            page = "Fleet",
            contract = MatrixStateContract(MatrixGroup.Dialogs, listOf(MatrixExpectation("Expected overlay"), MatrixExpectation("Commit", control = true))),
        ) { }
        val scene = ImageComposeScene(640, 480, coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) {
                Column {
                    Text("Ordinary page")
                    TechAction("Commit") {}
                }
            }
        }
        try {
            repeat(3) { scene.render((it + 1) * 16_000_000L).close(); yield() }
            val failure = assertFailsWith<IllegalStateException> { assertMatrixState(scene, expected) }
            assertTrue(failure.message.orEmpty().contains("Expected overlay"))
        } finally {
            scene.close()
        }
    }
}
