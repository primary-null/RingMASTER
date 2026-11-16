package local.ringleader.core

import java.net.URLEncoder
import java.time.Instant
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlinx.serialization.json.*

class TwoFactorRequiredException : IllegalStateException("This account uses two-factor authentication. Enter the current code.")
/** What a session shows when the server answered a password sign-in with HTTP 401. */
const val LOGIN_REFUSED = "The server refused this user name or password."
class LoginRefusedException(message: String = LOGIN_REFUSED) : IllegalStateException(message)

internal fun validateIdentity(identity: String, kind: String) {
    if ('!' in identity) require(identity.matches(tokenIdPattern)) { "Use a token ID in user@realm!token form." }
    else {
        require(kind != PDM_KIND) { "PDM connections use API tokens." }
        require(identity.matches(Regex("[^\\s@!=:]+@[^\\s@!=:]+"))) { "Use a user in user@realm form, for example root@pam." }
    }
}

private const val CLOSED = "This connection is closed."

internal fun formEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
internal class PveTicket(val ticket: String, val csrf: String, val issued: Instant) {
    val cookie get() = "PVEAuthCookie=${formEncode(ticket)}"
    override fun toString() = "PveTicket(redacted)"
}

/**
 * Owned by one live connection; no credentials are shared between sessions.
 *
 * The lock is never held across the network. Concurrent callers that need a new ticket share one login (single
 * flight): the first one posts, the others wait for its result. [close] never waits on a login in flight.
 */
internal class TicketStore(private val clock: () -> Instant = Instant::now) {
    private class Flight {
        private val done = CountDownLatch(1)
        @Volatile private var result: Result<PveTicket>? = null
        /** The first result wins; later ones are ignored. */
        fun complete(value: Result<PveTicket>) {
            synchronized(this) { if (result != null) return; result = value }
            done.countDown()
        }
        fun await(): PveTicket { done.await(); return result!!.getOrThrow() }
    }
    private val lock = Any()
    private val entries = mutableMapOf<Pair<String, String>, PveTicket>()
    private val flights = mutableMapOf<Pair<String, String>, Flight>()
    private var closed = false
    @Volatile private var refused: LoginRefusedException? = null

    /**
     * Drop [refused] because the server refused it, only if it is still the cached ticket. A late 401 for a ticket that another
     * request has already replaced must not throw the fresh one away (and cost one more password POST). A login already in
     * flight carries on.
     */
    fun clear(refused: PveTicket) = synchronized(lock) { entries.values.removeIf { it === refused } }

    /** The connection closed: drop the ticket, release callers waiting on a login, and refuse new logins. Never waits on the network. */
    fun close() {
        val waiting = synchronized(lock) {
            closed = true
            entries.clear()
            flights.values.toList().also { flights.clear() }
        }
        waiting.forEach { it.complete(Result.failure(IllegalStateException(CLOSED))) }
    }
    override fun toString() = "TicketStore(redacted)"

    fun get(connection: Connection): PveTicket {
        val key = connection.address to connection.tokenId
        val mine = Flight()
        var renew: String? = null
        val joined = synchronized(lock) {
            check(!closed) { CLOSED }
            refused?.let { throw it }
            val old = entries[key]
            if (old != null) {
                val age = Duration.between(old.issued, clock())
                if (age <= Duration.ofMinutes(60)) return old
                entries.remove(key)
                if (age <= Duration.ofMinutes(115)) renew = old.ticket
            }
            flights[key] ?: run { flights[key] = mine; null }
        }
        if (joined != null) return joined.await()
        val result = runCatching { login(connection, renew) }
        val open = synchronized(lock) {
            if (flights[key] === mine) flights.remove(key)
            result.exceptionOrNull()?.let { failure ->
                if (failure is LoginRefusedException) {
                    refused = failure
                }
            }
            if (!closed) result.onSuccess { entries[key] = it }
            !closed
        }
        mine.complete(if (open) result else Result.failure(IllegalStateException(CLOSED)))
        return mine.await()
    }

    /** Runs outside the lock. Renewal posts the old ticket; when that fails, a full login uses the password and the one-time code. */
    private fun login(connection: Connection, renew: String?): PveTicket {
        if (renew != null) {
            try { return request(connection, renew, renewal = true) }
            catch (_: Exception) { /* Full login below, with the original password. */ }
        }
        val otp = connection.takeOtp()
        return connection.withSecret { password -> request(connection, password, otp = otp) }
    }
    private fun request(c: Connection, password: String, challenge: String? = null, otp: String? = null, renewal: Boolean = false): PveTicket {
        val trace = Trace.begin(TraceSource.Auth, if (renewal) "Renew ticket" else "Ticket login", emptyMap())
        try {
        val fields = linkedMapOf("username" to c.tokenId, "password" to password)
        if (challenge != null) fields["tfa-challenge"] = challenge
        // PVE 8 and 9 declare `new-format` as "now ignored and assumed to be 1" (pve-access-control cfd8636b, 2023-06-05,
        // "drop support for old login API"; same text on stable-bookworm and master), so it changes nothing in their reply.
        // It states the flow this code speaks: a NeedTFA answer whose ticket is the challenge for a second POST here.
        fields["new-format"] = "1"
        val body = fields.entries.joinToString("&") { "${formEncode(it.key)}=${formEncode(it.value)}" }.toByteArray(Charsets.UTF_8)
        val http = openPinnedHttps(serverOrigin(c.address).resolve("/api2/json/access/ticket"), c.fingerprint, 10_000, 20_000)
        val data = try {
            http.requestMethod = "POST"
            http.doOutput = true
            // Fixed length puts the POST in streaming mode, which the JDK never re-sends after a dropped connection: one
            // sign-in, two-factor or renewal attempt is one POST of its password, code or ticket. A lost answer is an I/O failure.
            http.setFixedLengthStreamingMode(body.size)
            http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectAboveTlsFloor(http)
            http.outputStream.use { it.write(body) }
            when (http.responseCode) {
                200 -> try { serverJson(http.inputStream.use { readAnswer(it, ANSWER_LIMIT_TICKET) }).jsonObject["data"]!!.jsonObject }
                    catch (e: AnswerRefusedException) { throw e }
                    catch (_: Exception) { error("The server returned an invalid login response.") }
                401 -> throw LoginRefusedException(if (challenge == null) LOGIN_REFUSED else "The server refused the two-factor code.")
                else -> error("The server refused the login request (HTTP ${http.responseCode}).")
            }
        } finally { body.fill(0); http.disconnect() }
        val ticket = data["ticket"]?.jsonPrimitive?.contentOrNull ?: error("The server returned an invalid login response.")
        // NeedTFA arrives as 1 from PVE; true and "1" are read the same. A success has no NeedTFA and may carry cap and clustername.
        if ((data["NeedTFA"] as? JsonPrimitive)?.content in listOf("1", "true")) {
            if (challenge != null || otp.isNullOrBlank()) throw TwoFactorRequiredException()
            return request(c, tfaResponse(otp), challenge = ticket)
        }
        val csrf = data["CSRFPreventionToken"]?.jsonPrimitive?.contentOrNull ?: error("The server returned an invalid login response.")
        return PveTicket(ticket, csrf, clock())
        } catch (t: Throwable) { trace.end(error=t); throw t } finally { trace.end() }
    }
}

private val tfaPrefixes = listOf("totp:", "recovery:", "webauthn:", "yubico:")
private const val MODHEX = "cbdefghijklnrtuv"

/**
 * The second-factor answer with its kind. A typed prefix is kept. Otherwise six to eight digits are a TOTP code; 32 to 64
 * ModHex characters are a Yubico OTP (a YubiKey types 44: a 12-character public ID and a 32-character token, and ModHex has no
 * digits); anything else with a dash or longer than eight characters is a recovery key (Proxmox prints them as
 * `xxxx-xxxx-xxxx-xxxx`); the rest is sent as TOTP and the server judges it.
 */
internal fun tfaResponse(otp: String): String = when {
    tfaPrefixes.any { otp.startsWith(it) } -> otp
    otp.length in 6..8 && otp.all { it in '0'..'9' } -> "totp:$otp"
    otp.length in 32..64 && otp.all { it.lowercaseChar() in MODHEX } -> "yubico:$otp"
    '-' in otp || otp.length > 8 -> "recovery:$otp"
    else -> "totp:$otp"
}
