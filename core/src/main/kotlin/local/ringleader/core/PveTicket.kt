package local.ringleader.core

import java.net.URLEncoder
import java.time.Instant
import java.time.Duration
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.json.*

class TwoFactorRequiredException : IllegalStateException("This account uses two-factor authentication. Enter the current code.")
class LoginRefusedException(message: String = "The server refused this user name or password.") : IllegalStateException(message)

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

    /** Drop the cached ticket because the server refused it. A login already in flight carries on. */
    fun clear() = synchronized(lock) { entries.clear() }

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
        val body = fields.entries.joinToString("&") { "${formEncode(it.key)}=${formEncode(it.value)}" }.toByteArray(Charsets.UTF_8)
        val http = serverOrigin(c.address).resolve("/api2/json/access/ticket").toURL().openConnection() as HttpsURLConnection
        http.instanceFollowRedirects = false
        http.connectTimeout = 10_000
        http.readTimeout = 20_000
        pinnedSslContext(c.fingerprint)?.let {
            http.sslSocketFactory = it.socketFactory
            http.setHostnameVerifier { _, session -> certificateMatches(session.peerCertificates[0] as X509Certificate, c.fingerprint) }
        }
        val data = try {
            http.requestMethod = "POST"
            http.doOutput = true
            http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            http.outputStream.use { it.write(body) }
            when (http.responseCode) {
                200 -> try { Json.parseToJsonElement(http.inputStream.bufferedReader().use { it.readText() }).jsonObject["data"]!!.jsonObject }
                    catch (_: Exception) { error("The server returned an invalid login response.") }
                401 -> throw LoginRefusedException(if (challenge == null) "The server refused this user name or password." else "The server refused the two-factor code.")
                else -> error("The server refused the login request (HTTP ${http.responseCode}).")
            }
        } finally { body.fill(0); http.disconnect() }
        val ticket = data["ticket"]?.jsonPrimitive?.contentOrNull ?: error("The server returned an invalid login response.")
        if (data["NeedTFA"]?.jsonPrimitive?.content in listOf("1", "true")) {
            if (challenge != null || otp.isNullOrBlank()) throw TwoFactorRequiredException()
            val code = if (listOf("totp:", "recovery:", "webauthn:", "yubico:").any { otp.startsWith(it) }) otp
                else (if ('-' in otp || otp.length > 8) "recovery:" else "totp:") + otp
            return request(c, code, challenge = ticket)
        }
        val csrf = data["CSRFPreventionToken"]?.jsonPrimitive?.contentOrNull ?: error("The server returned an invalid login response.")
        return PveTicket(ticket, csrf, clock())
        } catch (t: Throwable) { trace.end(error=t); throw t } finally { trace.end() }
    }
}
