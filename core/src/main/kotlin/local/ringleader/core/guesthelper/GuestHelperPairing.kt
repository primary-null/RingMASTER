package local.ringleader.core.guesthelper

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import local.ringleader.core.ProbedCertificate
import local.ringleader.core.SecretVault
import local.ringleader.core.VaultException
import local.ringleader.core.VaultStatus
import local.ringleader.core.integrations.ServiceOrigin
import local.ringleader.core.integrations.ServiceWire
import local.ringleader.core.probeTlsCertificate

/** Reads the leaf certificate a host shows on a port, trusting nothing. Tests swap in their own. */
fun interface CertificateReader {
    fun read(host: String, port: Int): ProbedCertificate?
}

val DefaultCertificateReader = CertificateReader { host, port -> runBlocking { probeTlsCertificate(ServiceOrigin.of("https", host, port).canonical) } }

/** Step 1's result: what to show the user before anything is trusted. */
data class GuestHelperCertificate(
    val host: String,
    val port: Int,
    val certificate: ProbedCertificate,
    /** The api the Engine named in its unauthenticated ping. */
    val api: Int,
    /** The fingerprint the installer printed, when the app read it over a channel it already trusts (the guest agent). */
    val expected: String?,
) {
    /** 64 lower-case hex digits, as pinned. */
    val fingerprint: String get() = certificate.fingerprint
    /** In pairs, as the installer prints nothing else and people compare it by eye: `AB:CD:...`. */
    val shown: String get() = fingerprint.chunked(2).joinToString(":") { it.uppercase() }
    /** True or false when [expected] is known; null when only the user can compare. */
    val matchesExpected: Boolean? get() = expected?.let { it.replace(":", "").lowercase() == fingerprint }
}

/** A pin the user confirmed (step 2). Only [GuestHelperPairing.confirm] makes one. */
class ConfirmedPin internal constructor(val host: String, val port: Int, val pin: String)

/**
 * Pairing as pure steps, each one testable on its own and none of them storing anything:
 * 1. [readCertificate]: read the certificate the address shows and check, without the token, that an Engine answers.
 * 2. [confirm]: the user compares the fingerprint with the one the installer printed and says yes.
 * 3. [checkToken]: `whoami` with the token, over the confirmed pin.
 * 4. [link]: the record to save ([GuestHelperPairings.save] stores it and puts the token in the password safe).
 * Nothing is trusted on first use: without step 2 there is no pin, and without a pin no token is sent.
 * All steps block: run them off the UI thread.
 */
class GuestHelperPairing(
    private val wire: ServiceWire,
    private val reader: CertificateReader = DefaultCertificateReader,
) {
    fun readCertificate(host: String, port: Int = GUEST_HELPER_PORT, expected: String? = null): GuestHelperCertificate {
        val origin = runCatching { ServiceOrigin.of("https", host, port) }.getOrElse {
            throw GuestHelperException(GuestHelperFailure.Refused, it.message ?: ServiceOrigin.BAD_ADDRESS)
        }
        val certificate = reader.read(origin.host, origin.port)
            ?: throw GuestHelperException(GuestHelperFailure.NotTls,
                "No TLS certificate could be read on port ${origin.port}. Is the $GUEST_HELPER_NAME installed and running, and does a firewall let it through?")
        // The ping carries no secret, so it may go over the certificate just read, before anyone trusts it.
        val api = GuestHelperClient(origin.host, origin.port, certificate.fingerprint, wire) { null }.ping()
        if (api != GUEST_HELPER_API) {
            val sentence = "This $GUEST_HELPER_NAME speaks a version this app cannot read. Update RingMASTER or reinstall the Engine."
            throw GuestHelperException(GuestHelperFailure.UnknownApi, sentence)
        }
        return GuestHelperCertificate(origin.host, origin.port, certificate, api, expected)
    }

    /**
     * The user's answer to "Is this the fingerprint the installer showed?". A fingerprint the app read over the guest agent
     * must match as well; a mismatch is refused even when the user said yes.
     */
    fun confirm(seen: GuestHelperCertificate, userConfirmed: Boolean): ConfirmedPin {
        if (seen.matchesExpected == false) throw GuestHelperException(GuestHelperFailure.CertificateChanged,
            "The certificate on the network is not the one the installer made. Something else may be answering on that address.")
        if (!userConfirmed) throw GuestHelperException(GuestHelperFailure.Refused, "Pairing stopped. Nothing was trusted or saved.")
        return ConfirmedPin(seen.host, seen.port, seen.fingerprint)
    }

    /** Checks [token] (wiped afterwards) with `whoami`. A wrong token counts toward the Engine's lockout of this computer. */
    fun checkToken(pin: ConfirmedPin, token: CharArray): GuestHelperIdentity {
        val copy = token.copyOf()
        token.fill(Char(0))
        if (GuestHelperClient.canonicalToken(copy) == null) {
            copy.fill(Char(0))
            throw GuestHelperException(GuestHelperFailure.WrongToken, GuestHelperClient.TOKEN_SHAPE)
        }
        var given = false
        return GuestHelperClient(pin.host, pin.port, pin.pin, wire) { if (given) null else { given = true; copy } }.whoami()
    }

    fun link(
        place: GuestHelperPlace,
        pin: ConfirmedPin,
        identity: GuestHelperIdentity,
        now: Instant = Instant.now(),
        id: String = UUID.randomUUID().toString(),
    ) = GuestHelperLink(id, place, pin.host, pin.port, pin.pin, identity.version, identity.host, now, now)
}

/**
 * The paired helpers of this computer: the records in [GuestHelperStore] and their tokens. A token is kept in memory for
 * this run and in the OS password safe when it is available, so a pairing still works on a system without one until
 * RingMASTER closes; [GuestHelperLink.tokenInSafe] says which, and the Settings page says it in words.
 */
class GuestHelperPairings(private val store: GuestHelperStore, vault: SecretVault, private val wire: ServiceWire) {
    private val vault = GuestHelperVault(vault)
    private val lock = Any()
    private var links: List<GuestHelperLink> = store.load()
    private val tokens = HashMap<String, CharArray>()

    val blockedReason: String? get() = store.blockedReason
    val vaultStatus: VaultStatus get() = vault.status

    fun list(): List<GuestHelperLink> = synchronized(lock) { links.toList() }
    fun link(id: String): GuestHelperLink? = synchronized(lock) { links.firstOrNull { it.id == id } }
    fun forPlace(place: GuestHelperPlace): GuestHelperLink? = synchronized(lock) { links.firstOrNull { it.place == place } }

    /**
     * Saves [link] with [token] (wiped afterwards). A pairing of the same place replaces the old one and forgets its token.
     * The record is written only after the token is kept somewhere, so a saved pairing can always be read in this run.
     */
    fun save(link: GuestHelperLink, token: CharArray): Result<GuestHelperLink> = runCatching {
        val inSafe = try { keepInSafe(link, token) } finally { token.fill(Char(0)) }
        val saved = link.copy(tokenInSafe = inSafe)
        val replaced = synchronized(lock) {
            val old = links.filter { it.place == saved.place && it.id != saved.id }
            val next = links.filterNot { it.place == saved.place || it.id == saved.id } + saved
            try {
                store.save(next)
            } catch (e: IllegalStateException) {
                tokens.remove(saved.id)?.fill(Char(0))
                if (inSafe) forgetInSafe(saved.id)
                throw e
            }
            links = next
            old
        }
        replaced.forEach { forget(it.id) }
        saved
    }

    /** Copies [token] into memory, and into the safe when it is there. True when the safe has it. */
    private fun keepInSafe(link: GuestHelperLink, token: CharArray): Boolean {
        synchronized(lock) { tokens.put(link.id, token.copyOf())?.fill(Char(0)) }
        if (vault.status !is VaultStatus.Available) return false
        return try {
            vault.store(link, token)
            true
        } catch (_: VaultException) {
            false // The token stays in memory; the record says so and Settings tells the user it ends with this run.
        }
    }

    /** Forgets the pairing and its token. The Engine itself stays installed; removing it is the uninstaller's job. */
    fun unpair(id: String): Result<Unit> = runCatching {
        synchronized(lock) {
            val next = links.filterNot { it.id == id }
            store.save(next)
            links = next
        }
        forget(id)
    }

    private fun forget(id: String) {
        synchronized(lock) { tokens.remove(id)?.fill(Char(0)) }
        forgetInSafe(id)
    }

    private fun forgetInSafe(id: String) {
        try {
            vault.forget(id)
        } catch (_: VaultException) {
            // An entry the safe cannot remove names no secret by itself, and the pairing record that used it is gone.
        }
    }

    /** The token for [id]: from memory, else from the safe. A fresh array the caller wipes, or null. */
    fun token(id: String): CharArray? {
        synchronized(lock) { tokens[id]?.let { return it.copyOf() } }
        return try { vault.read(id) } catch (_: VaultException) { null }
    }

    /** A client for a saved pairing; it reads the token for each request and never keeps it. */
    fun client(link: GuestHelperLink): GuestHelperClient = GuestHelperClient(link.host, link.port, link.pin, wire) { token(link.id) }

    /** Records what a good `whoami` said: the version and when the Engine was last seen. */
    fun seen(id: String, identity: GuestHelperIdentity, now: Instant = Instant.now()): Result<Unit> = runCatching {
        synchronized(lock) {
            val next = links.map { if (it.id == id) it.copy(version = identity.version, hostName = identity.host, lastSeen = now) else it }
            store.save(next)
            links = next
        }
    }
}
