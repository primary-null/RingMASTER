package local.ringleader.core

/** Whether the operating system's password safe can be used right now. */
sealed interface VaultStatus {
    data object Available : VaultStatus
    /** [reason] is one plain sentence for the desk. It never names a profile. */
    data class Unavailable(val reason: String) : VaultStatus
}

/** A vault call failed. The message is one plain sentence, never the secret or a profile name. */
class VaultException(message: String) : Exception(message)

/**
 * The operating system's password safe, keyed by profile id. Every call can block (an unlock prompt): call it off the UI
 * thread. Implementations never write a file of their own.
 */
interface SecretVault {
    /** "Windows Credential Manager", "your password safe (Secret Service)". Shown in the desk's help text. */
    val name: String
    fun status(): VaultStatus
    /** Copies [secret]; the caller wipes its own array. [login] and [label] are non-secret and help the user recognise the entry. */
    fun store(profileId: String, login: String, label: String, secret: CharArray)
    /** A fresh array the caller must wipe, or null when there is no entry. */
    fun read(profileId: String): CharArray?
    /** No entry is not an error. */
    fun forget(profileId: String)
    /** Every entry this app stored, also those of profiles that no longer exist. */
    fun forgetAll()
}

/** Systems and hosts without a vault. */
class NoVault(private val reason: String) : SecretVault {
    override val name = "the password safe"
    override fun status() = VaultStatus.Unavailable(reason)
    override fun store(profileId: String, login: String, label: String, secret: CharArray): Unit = throw VaultException(reason)
    override fun read(profileId: String): CharArray? = throw VaultException(reason)
    override fun forget(profileId: String) {}
    override fun forgetAll() {}
}

/** The browser host: never reads or stores, but forgets, so a profile deleted from a browser leaves no entry behind. */
class ForgetOnlyVault(private val inner: SecretVault, private val reason: String = "Remember is not available in browser mode.") : SecretVault {
    override val name get() = inner.name
    override fun status() = VaultStatus.Unavailable(reason)
    override fun store(profileId: String, login: String, label: String, secret: CharArray): Unit = throw VaultException(reason)
    override fun read(profileId: String): CharArray? = throw VaultException(reason)
    override fun forget(profileId: String) = inner.forget(profileId)
    override fun forgetAll() = inner.forgetAll()
}
