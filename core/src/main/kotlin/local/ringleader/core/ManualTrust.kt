package local.ringleader.core

/** A review snapshot; connectManual re-resolves it before accepting explicit approval. */
data class ManualTrust(
    val origin: String? = null,
    val known: List<String> = emptyList(),
    val observed: String? = null,
    val savedPin: Boolean = false,
    val error: String? = null,
) {
    val conflict get() = known.size > 1
    val changed get() = observed != null && known.isNotEmpty() && observed !in known
    val needsApproval get() = observed != null && (known.singleOrNull() != observed)
    // Saved profiles must be edited explicitly; this control never overwrites their pins.
    val canApprove get() = error == null && observed != null && !conflict && (!savedPin || !changed)
}
