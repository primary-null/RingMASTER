package local.ringleader.desktop

/**
 * Confirmation gate for a scanned certificate fingerprint. The scan offers a fingerprint;
 * [request] opens the dialog but does not yield a pin; [confirm] returns it once; [cancel]
 * or a fresh [offer] drops it. Changing the address via [addressChanged] also clears.
 */
class ScannedPinPrompt {
    private var offeredAddress: String? = null
    private var offeredFingerprint: String? = null
    private var requested = false

    val pending get() = requested
    val address get() = offeredAddress
    val fingerprint get() = offeredFingerprint

    fun offer(address: String, fingerprint: String) {
        offeredAddress = address
        offeredFingerprint = fingerprint
        requested = false
    }

    fun request(): String? {
        if (offeredFingerprint == null) return null
        requested = true
        return null
    }

    fun confirm(): String? {
        if (!requested || offeredFingerprint == null) return null
        val pin = offeredFingerprint
        offeredAddress = null
        offeredFingerprint = null
        requested = false
        return pin
    }

    fun cancel() {
        requested = false
    }

    fun addressChanged(current: String) {
        if (offeredAddress != null && offeredAddress != current) {
            offeredAddress = null
            offeredFingerprint = null
            requested = false
        }
    }
}
