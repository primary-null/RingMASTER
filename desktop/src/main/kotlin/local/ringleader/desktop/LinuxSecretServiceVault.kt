package local.ringleader.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File
import local.ringleader.core.SecretVault
import local.ringleader.core.VaultException
import local.ringleader.core.VaultStatus

/**
 * libsecret's simple password API. The schema attribute "profile" is the profile id.
 * libsecret itself adds xdg:schema = [schemaName]. Sync calls run a loop and can show an unlock prompt, so callers
 * stay off the UI thread. One lock per instance keeps those calls off each other.
 *
 * forgetAll uses secret_password_clear_sync with no attributes. libsecret 0.21 secret_password_clear_sync builds the
 * attribute table with secret_attributes_buildv and then secret_password_clearv_sync, which validates with matching
 * allowed. An empty table is accepted when the schema does not set SECRET_SCHEMA_DONT_MATCH_NAME (this schema uses
 * SECRET_SCHEMA_NONE). secret_attributes_validate's own comment says an empty table matches everything in that case.
 * The service search adds xdg:schema, so the clear matches every item stored with this schema. The clear deletes
 * unlocked items only; that is libsecret's rule ("All unlocked items that match the attributes will be deleted"),
 * not a file fallback.
 */
internal class LinuxSecretServiceVault(private val schemaName: String = "local.ringleader.RingMASTER") : SecretVault {
    override val name = "your password safe (Secret Service)"

    @Suppress("FunctionName")
    private interface LibSecret : Library {
        fun secret_schema_new(name: String, flags: Int, vararg nameTypePairsThenNull: Any?): Pointer?
        fun secret_password_store_sync(
            schema: Pointer, collection: String?, label: String, password: Pointer,
            cancellable: Pointer?, error: PointerByReference, vararg attributesThenNull: Any?,
        ): Boolean
        fun secret_password_lookup_sync(
            schema: Pointer, cancellable: Pointer?, error: PointerByReference, vararg attributesThenNull: Any?,
        ): Pointer?
        fun secret_password_clear_sync(
            schema: Pointer, cancellable: Pointer?, error: PointerByReference, vararg attributesThenNull: Any?,
        ): Boolean
        fun secret_password_free(password: Pointer?)
        fun secret_service_get_sync(flags: Int, cancellable: Pointer?, error: PointerByReference): Pointer?
    }

    private interface GLib : Library {
        fun g_error_free(error: Pointer)
    }

    private interface GObject : Library {
        fun g_object_unref(obj: Pointer)
    }

    private val gate = Any()
    private var secret: LibSecret? = null
    private var glib: GLib? = null
    private var gobject: GObject? = null
    private var schema: Pointer? = null
    private var loadFailure: String? = null

    override fun status(): VaultStatus {
        if (!hasSessionBus()) return VaultStatus.Unavailable("This session has no desktop message bus, so there is no password safe.")
        if (!ensure()) return VaultStatus.Unavailable(loadFailure ?: MISSING)
        val err = PointerByReference()
        // Opening a session is what proves a safe answers. A bare proxy is created even when nothing owns
        // org.freedesktop.secrets, so the plain call reported a safe on a desktop that had none (seen on Debian 13 with
        // Xfce and no keyring). Opening a session does not unlock anything and shows no prompt.
        val service = synchronized(gate) { secret!!.secret_service_get_sync(SECRET_SERVICE_OPEN_SESSION, null, err) }
        val message = consumeError(err)
        if (message != null || service == null) {
            val detail = message?.trim()?.take(120).orEmpty()
            val reason = "No password safe (Secret Service) is running in this session."
            return VaultStatus.Unavailable(if (detail.isEmpty()) reason else "$reason $detail")
        }
        synchronized(gate) { gobject!!.g_object_unref(service) }
        return VaultStatus.Available
    }

    override fun store(profileId: String, login: String, label: String, secretChars: CharArray) {
        if (!ensure()) throw VaultException(loadFailure ?: MISSING)
        val bytes = encodeSecretUtf8(secretChars)
        val memory = Memory((bytes.size + 1).toLong())
        val err = PointerByReference()
        try {
            if (bytes.isNotEmpty()) memory.write(0, bytes, 0, bytes.size)
            memory.setByte(bytes.size.toLong(), 0)
            val schemaPointer = schema ?: throw VaultException(MISSING)
            val stored = synchronized(gate) {
                secret!!.secret_password_store_sync(
                    schemaPointer, null, label.take(256), memory, null, err, "profile", profileId, null,
                )
            }
            if (!stored) throw vaultError(err, profileId)
        } finally {
            memory.clear()
            bytes.fill(0)
            consumeError(err)
        }
    }

    override fun read(profileId: String): CharArray? {
        if (!ensure()) throw VaultException(loadFailure ?: MISSING)
        val schemaPointer = schema ?: throw VaultException(MISSING)
        val err = PointerByReference()
        val found = synchronized(gate) {
            secret!!.secret_password_lookup_sync(schemaPointer, null, err, "profile", profileId, null)
        }
        val message = consumeError(err)
        if (message != null) throw VaultException(plain(message, profileId))
        if (found == null) return null
        try {
            return copyCString(found)
        } finally {
            synchronized(gate) { secret!!.secret_password_free(found) }
        }
    }

    override fun forget(profileId: String) {
        if (!ensure()) throw VaultException(loadFailure ?: MISSING)
        val schemaPointer = schema ?: throw VaultException(MISSING)
        val err = PointerByReference()
        val removed = synchronized(gate) {
            secret!!.secret_password_clear_sync(schemaPointer, null, err, "profile", profileId, null)
        }
        val message = consumeError(err)
        if (message != null) throw VaultException(plain(message, profileId))
        if (!removed) return
    }

    override fun forgetAll() {
        if (!ensure()) throw VaultException(loadFailure ?: MISSING)
        val schemaPointer = schema ?: throw VaultException(MISSING)
        val err = PointerByReference()
        synchronized(gate) { secret!!.secret_password_clear_sync(schemaPointer, null, err, null) }
        val message = consumeError(err)
        if (message != null) throw VaultException(plain(message, ""))
    }

    private fun ensure(): Boolean = synchronized(gate) {
        if (secret != null && schema != null) return true
        if (loadFailure != null) return false
        try {
            secret = loadFirst("secret-1", "libsecret-1.so.0")
            glib = loadFirst("glib-2.0", "libglib-2.0.so.0")
            gobject = loadFirst("gobject-2.0", "libgobject-2.0.so.0")
            schema = secret!!.secret_schema_new(schemaName, SECRET_SCHEMA_NONE, "profile", SECRET_SCHEMA_ATTRIBUTE_STRING, null)
            if (schema == null) {
                loadFailure = MISSING
                return false
            }
            true
        } catch (_: UnsatisfiedLinkError) {
            loadFailure = MISSING
            false
        } catch (_: NoClassDefFoundError) {
            loadFailure = MISSING
            false
        }
    }

    private inline fun <reified T : Library> loadFirst(vararg names: String): T {
        return GnomeLibraries.load(T::class.java, *names)
    }

    /** GError is { domain, code, message } with the message pointer at offset 8 on 64-bit. The text is not a secret. */
    private fun consumeError(err: PointerByReference): String? {
        val pointer = err.value ?: return null
        err.value = null
        return try {
            val message = pointer.getPointer(8)?.getString(0, "UTF-8")
            message
        } finally {
            glib?.let { lib -> synchronized(gate) { lib.g_error_free(pointer) } }
        }
    }

    private fun vaultError(err: PointerByReference, profileId: String): VaultException =
        VaultException(plain(consumeError(err).orEmpty(), profileId))

    private fun plain(raw: String, profileId: String): String {
        val cleaned = raw.replace(profileId, "a profile").trim().take(120)
        val locked = cleaned.contains("cancel", true) || cleaned.contains("dismiss", true) || cleaned.contains("lock", true)
        if (locked) return "The password safe stayed locked."
        if (cleaned.isEmpty()) return "The password safe refused the request."
        return "The password safe refused the request. $cleaned"
    }

    /** Bytes up to the NUL, decoded, then the heap copy is wiped. secret_password_free zeroes the C string. */
    private fun copyCString(pointer: Pointer): CharArray {
        var length = 0
        while (length < MAX_SECRET_BYTES && pointer.getByte(length.toLong()) != 0.toByte()) length++
        val bytes = pointer.getByteArray(0, length)
        return try {
            decodeSecretUtf8(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun hasSessionBus(): Boolean {
        if (!System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) return true
        val uid = readUid() ?: return false
        return File("/run/user/$uid/bus").exists()
    }

    private fun readUid(): String? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Uid:") }?.trim()?.split(Regex("\\s+"))?.getOrNull(1)
        }
    }.getOrNull()

    private companion object {
        const val SECRET_SCHEMA_NONE = 0
        const val SECRET_SCHEMA_ATTRIBUTE_STRING = 0
        const val SECRET_SERVICE_OPEN_SESSION = 1 shl 1
        const val MAX_SECRET_BYTES = 64 * 1024
        const val MISSING = "libsecret is not installed. Install libsecret and a password safe such as GNOME Keyring or KeePassXC."
    }
}
