package local.ringleader.desktop

/** Discovery evidence is an offer, never approval. Edits/cancel permanently invalidate it. */
class ScannedPinOffer(
    private val address: String,
    private val kind: String,
    private val pin: String?,
    private val clock: () -> Long = System::nanoTime,
) {
    private val created = clock()
    private var valid = true
    fun invalidate() { valid = false }
    fun current(address: String, kind: String): String? = pin.takeIf {
        valid && this.address == address && this.kind == kind && clock() - created in 0..300_000_000_000L
    }
}
