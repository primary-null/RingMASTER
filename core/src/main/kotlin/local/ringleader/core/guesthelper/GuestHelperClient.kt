package local.ringleader.core.guesthelper

import local.ringleader.core.UploadCancel
import local.ringleader.core.integrations.ServiceOrigin
import local.ringleader.core.integrations.ServiceWire
import local.ringleader.core.integrations.ServiceWireException
import local.ringleader.core.integrations.WireAnswer
import local.ringleader.core.integrations.WireFailure
import local.ringleader.core.integrations.WireLimits
import local.ringleader.core.integrations.WireMethod
import local.ringleader.core.integrations.WireRequest
import local.ringleader.core.integrations.WireTrust

/**
 * Talks to one paired helper over the linked services' one-shot wire ([ServiceWire]): one request on one fresh
 * connection, the certificate pinned, never retried or redirected, every answer bounded and parsed strictly.
 * [token] is read for each request and never kept; it goes in `Authorization: Bearer`, never in a URL.
 * Every call blocks: run it off the UI thread. A scan runs only when the user starts it.
 */
class GuestHelperClient(
    host: String,
    port: Int,
    private val pin: String,
    private val wire: ServiceWire,
    private val token: () -> CharArray?,
) {
    private val origin = ServiceOrigin.of("https", host, port)

    init { require(Regex("[0-9a-f]{64}").matches(pin)) { "The pin is 64 lower-case hex digits." } }

    /** Tests shorten the time limits; the answer-size caps stay. */
    internal var timeLimits: WireLimits? = null

    /** Without the token: says whether this is an Engine and its api. Used while pairing and by the address finder. */
    fun ping(cancel: UploadCancel = UploadCancel()): Int = parsePing(ok(send(WireMethod.GET, "/v1/ping", false, SMALL, cancel)).text)

    fun whoami(cancel: UploadCancel = UploadCancel()): GuestHelperIdentity = parseIdentity(ok(send(WireMethod.GET, "/v1/whoami", true, SMALL, cancel)).text)

    /** The radio probe: what the guest itself sees. */
    fun radios(cancel: UploadCancel = UploadCancel()): GuestHelperRun =
        parseRun(ok(send(WireMethod.GET, "/v1/radios", true, runLimits(0), cancel)).text, GuestHelperMode.Probe)

    fun scanBluetooth(seconds: Int, cancel: UploadCancel = UploadCancel()): GuestHelperRun =
        scan(GuestHelperMode.BluetoothScan, "/v1/scan/bluetooth", seconds, cancel)

    fun scanWifi(seconds: Int, cancel: UploadCancel = UploadCancel()): GuestHelperRun =
        scan(GuestHelperMode.WifiScan, "/v1/scan/wifi", seconds, cancel)

    private fun scan(mode: GuestHelperMode, path: String, seconds: Int, cancel: UploadCancel): GuestHelperRun {
        require(seconds in SCAN_SECONDS) { "A scan takes ${SCAN_SECONDS.first} to ${SCAN_SECONDS.last} seconds." }
        return parseRun(ok(send(WireMethod.POST, "$path?seconds=$seconds", true, runLimits(seconds), cancel)).text, mode)
    }

    private fun send(method: WireMethod, path: String, withToken: Boolean, bounds: WireLimits, cancel: UploadCancel): WireAnswer {
        val limits = timeLimits?.copy(maxBytes = bounds.maxBytes) ?: bounds
        val headers = if (withToken) {
            val chars = token() ?: throw GuestHelperException(GuestHelperFailure.WrongToken, "Enter the pairing token the installer showed.")
            try {
                val canonical = canonicalToken(chars) ?: throw GuestHelperException(GuestHelperFailure.WrongToken, TOKEN_SHAPE)
                listOf("Authorization" to "Bearer $canonical")
            } finally { chars.fill(Char(0)) }
        } else emptyList()
        return try {
            wire.exchange(origin, WireTrust.Pinned(pin), WireRequest(method, path, headers, carriesCredential = withToken), limits, cancel)
        } catch (e: ServiceWireException) {
            throw translate(e)
        }
    }

    companion object {
        val SCAN_SECONDS = 4..30
        private val SMALL = WireLimits(maxBytes = GuestHelperLimits.SMALL_ANSWER)
        const val TOKEN_SHAPE = "A pairing token is 25 letters and digits, shown in five groups of five."

        /** The helper's own limit is the scan plus 15 s; the wire waits a little longer so the helper's answer wins. */
        private fun runLimits(seconds: Int) =
            WireLimits(readMs = (seconds + 20) * 1000, totalMs = (seconds + 25) * 1000, maxBytes = GuestHelperLimits.RUN_ANSWER)

        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        /**
         * The token as the helper compares it: upper case, without dashes or spaces, O read as 0 and I or L as 1.
         * Null when it cannot be a token, so a mistyped one is caught here and never counts against the lockout.
         */
        fun canonicalToken(chars: CharArray): String? {
            if (chars.size > 64) return null
            val out = StringBuilder(25)
            for (raw in chars) {
                val c = when (val u = raw.uppercaseChar()) { '-', ' ' -> continue; 'O' -> '0'; 'I', 'L' -> '1'; else -> u }
                if (c !in ALPHABET) return null
                out.append(c)
            }
            return out.takeIf { it.length == 25 }?.toString()
        }

        internal fun ok(answer: WireAnswer): WireAnswer = when (answer.status) {
            in 200..299 -> answer
            401 -> throw GuestHelperException(GuestHelperFailure.WrongToken, "The $GUEST_HELPER_NAME did not accept the token.")
            429 -> {
                val wait = minutes(answer.retryAfterSeconds)
                val sentence = "Too many wrong tokens from this computer. The $GUEST_HELPER_NAME will listen again in $wait."
                throw GuestHelperException(GuestHelperFailure.LockedOut, sentence, answer.retryAfterSeconds)
            }
            409 -> {
                val sentence = "The $GUEST_HELPER_NAME is already scanning. Try again when it is done."
                throw GuestHelperException(GuestHelperFailure.Busy, sentence, answer.retryAfterSeconds)
            }
            404, 405 -> throw GuestHelperException(GuestHelperFailure.NotAnEngine, "This port does not answer as a $GUEST_HELPER_NAME.")
            else -> throw GuestHelperException(GuestHelperFailure.Refused, "The $GUEST_HELPER_NAME refused the request (HTTP ${answer.status}).")
        }

        private fun minutes(seconds: Long?): String = when {
            seconds == null -> "a few minutes"
            seconds < 60 -> "under a minute"
            else -> "${(seconds + 59) / 60} minutes"
        }

        internal fun translate(e: ServiceWireException): GuestHelperException = when (e.failure) {
            WireFailure.CertificateChanged -> GuestHelperException(GuestHelperFailure.CertificateChanged,
                "The certificate is not the one you paired. If the Engine was reinstalled, pair it again.", cause = e)
            WireFailure.TlsFailed, WireFailure.CertificateUntrusted -> GuestHelperException(GuestHelperFailure.NotTls,
                "This port does not speak TLS as the $GUEST_HELPER_NAME does. It may be another program or plain http.", cause = e)
            WireFailure.TooLarge, WireFailure.TooDeep ->
                GuestHelperException(GuestHelperFailure.TooLarge, "The $GUEST_HELPER_NAME's answer was too large and was not read.", cause = e)
            WireFailure.Timeout -> GuestHelperException(GuestHelperFailure.TooSlow, "The $GUEST_HELPER_NAME did not answer in time.", cause = e)
            WireFailure.Malformed, WireFailure.EncodingRefused, WireFailure.NoAnswer -> GuestHelperException(GuestHelperFailure.NotAnEngine,
                "This port does not answer as a $GUEST_HELPER_NAME.", cause = e)
            WireFailure.RequestRefused, WireFailure.AddressRefused, WireFailure.PlainHttpRefused,
            WireFailure.PlainHttpNeedsAcknowledgement, WireFailure.Cancelled ->
                GuestHelperException(GuestHelperFailure.Refused, e.message ?: "The request was not sent.", cause = e)
            WireFailure.NameNotResolved, WireFailure.ConnectionRefused, WireFailure.Unreachable -> GuestHelperException(GuestHelperFailure.Unreachable,
                if (e.failure == WireFailure.ConnectionRefused) "Nothing answers on that port. Is the $GUEST_HELPER_NAME installed and running?"
                else e.message ?: "That address cannot be reached.", cause = e)
        }
    }
}
