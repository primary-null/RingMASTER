@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import java.io.File

/**
 * The themes the certificate-trust screens are looked at under (owner, 2026-11-14: every screen in high contrast and under
 * the OS-theme previews): the two ordinary themes, the built-in high contrast, and a Windows contrast theme's own colours
 * (Desert, a light one). Each entry switches [Appearance] in memory only.
 */
internal val trustShotThemes: List<Pair<String, () -> Unit>> = listOf(
    "dark" to { Appearance.preview(ThemeMode.Dark) },
    "light" to { Appearance.preview(ThemeMode.Light) },
    "contrast" to { Appearance.preview(ThemeMode.HighContrast) },
    "desert" to { Appearance.previewSystemContrast(SystemContrastColors(Color(0xFFFFFAEF), Color(0xFF3D3D3D), Color(0xFF903909), Color(0xFFFFF5E3), Color(0xFF1C5E75)), light = true) },
)

/** Writes [scene] as `<name>.png` into the folder named by `RM_FIELD_SHOTS`, when it is set. */
internal fun saveTrustShot(scene: ImageComposeScene, name: String) {
    val folder = System.getenv("RM_FIELD_SHOTS") ?: return
    File(folder).mkdirs()
    scene.render(900_000_000).use { image -> image.encodeToData()!!.use { File(folder, "$name.png").writeBytes(it.bytes) } }
}
