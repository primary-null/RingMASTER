package local.ringleader.desktop

import local.ringleader.core.*

internal data class CreatePreview(val lines: List<ParamLine>, val problem: String? = null)
internal fun safeCreatePreview(encode: () -> List<ParamLine>): CreatePreview = try {
    CreatePreview(encode())
} catch (_: IllegalArgumentException) {
    CreatePreview(emptyList(), "Complete or correct the highlighted fields to preview exact parameters.")
}
internal fun safeFreezeCreate(freeze: () -> FreezeResult): FreezeResult = try {
    freeze()
} catch (_: IllegalArgumentException) {
    FreezeResult.Invalid(mapOf(CreateFields.DISKS to "Correct the disk or volume settings before creating."))
}
