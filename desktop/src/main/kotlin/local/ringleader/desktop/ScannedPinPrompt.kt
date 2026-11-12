package local.ringleader.desktop

/**
 * Confirmation gate for a scanned certificate fingerprint. The scan offers a fingerprint;
 * [request] opens the dialog but does not yield a pin; [confirm] returns it once; [cancel]
 * or a fresh [offer] drops it. Changing the address via [addressChanged] also clears, and
 * tells the caller to empty the fingerprint field when it still holds a pin confirmed for
 * the scanned address.
 */
class ScannedPinPrompt {
    private var offeredAddress: String? = null
    private var offeredFingerprint: String? = null
    private var requested = false
    private var pinnedAddress: String? = null
    private var pinnedFingerprint: String? = null

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
        pinnedAddress = offeredAddress
        pinnedFingerprint = pin
        offeredAddress = null
        offeredFingerprint = null
        requested = false
        return pin
    }

    fun cancel() {
        requested = false
    }

    /**
     * The address field changed to [current]. Returns true when [field], the fingerprint field, still holds the
     * pin confirmed for another address: the caller empties it. A fingerprint the user typed is left alone.
     */
    fun addressChanged(current: String, field: String? = null): Boolean {
        if (offeredAddress != null && offeredAddress != current) {
            offeredAddress = null
            offeredFingerprint = null
            requested = false
        }
        val pinned = pinnedFingerprint ?: return false
        if (pinnedAddress == current) return false
        pinnedAddress = null
        pinnedFingerprint = null
        return field == null || canonical(field) == canonical(pinned)
    }

    private fun canonical(pin: String) = pin.replace(":", "").trim().lowercase()
}
