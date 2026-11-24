package local.ringleader.core

/** Consume consent only once the scheduler interlock permits marking the send in flight. */
internal fun MutationLeases.confirmForeground(key: GuestKey, used: MutableSet<String>, id: String, mark: () -> Unit): Boolean? =
    foreground(key) { if (!used.add(id)) false else { mark(); true } }

/** A delayed concurrent read cannot erase a task's observed terminal exit evidence. */
internal fun PowerSubmission.withTaskStatus(status: PowerTaskStatus, manual: Boolean): PowerSubmission {
    val terminal = phase == PowerPhase.Succeeded || phase == PowerPhase.Unsuccessful
    return copy(
        checking = if (manual) false else checking,
        phase = if (terminal) phase else status.phase,
        detail = if (terminal) detail else status.detail,
    )
}
