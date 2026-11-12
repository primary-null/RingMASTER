package local.ringleader.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import local.ringleader.core.SecretVault
import local.ringleader.core.VaultException
import local.ringleader.core.VaultStatus

/** CREDENTIALW is 80 bytes on 64-bit Windows. [windowsCredentialStructureSize] is what the test checks. */
internal fun windowsCredentialStructureSize(): Int = WindowsCredentialVault.Credential().size()

/**
 * Generic credentials in Windows Credential Manager, persisted for this computer only.
 * The target name is [namespace]/profileId. The blob is the secret; the user name is the non-secret login.
 * Any process running as the same user can read the blob. There is no file fallback.
 */
internal class WindowsCredentialVault(private val namespace: String = "RingMASTER") : SecretVault {
    override val name = "Windows Credential Manager"

    @Suppress("FunctionName")
    private interface Advapi32 : StdCallLibrary {
        fun CredWriteW(credential: Credential, flags: Int): Boolean
        fun CredReadW(target: WString, type: Int, flags: Int, credential: PointerByReference): Boolean
        fun CredDeleteW(target: WString, type: Int, flags: Int): Boolean
        fun CredEnumerateW(filter: WString?, flags: Int, count: IntByReference, credentials: PointerByReference): Boolean
        fun CredGetSessionTypes(maximumPersistCount: Int, maximumPersist: IntArray): Boolean
        fun CredFree(buffer: Pointer)
    }

    @Structure.FieldOrder("dwLowDateTime", "dwHighDateTime")
    class FileTime : Structure() {
        @JvmField var dwLowDateTime = 0
        @JvmField var dwHighDateTime = 0
    }

    /** CREDENTIALW. 80 bytes on x64: the test asserts [size]. */
    @Structure.FieldOrder(
        "Flags", "Type", "TargetName", "Comment", "LastWritten", "CredentialBlobSize", "CredentialBlob",
        "Persist", "AttributeCount", "Attributes", "TargetAlias", "UserName",
    )
    class Credential(p: Pointer? = null) : Structure(p) {
        @JvmField var Flags = 0
        @JvmField var Type = 0
        @JvmField var TargetName: WString? = null
        @JvmField var Comment: WString? = null
        @JvmField var LastWritten = FileTime()
        @JvmField var CredentialBlobSize = 0
        @JvmField var CredentialBlob: Pointer? = null
        @JvmField var Persist = 0
        @JvmField var AttributeCount = 0
        @JvmField var Attributes: Pointer? = null
        @JvmField var TargetAlias: WString? = null
        @JvmField var UserName: WString? = null
        init { if (p != null) read() }
    }

    private val api: Advapi32 by lazy { Native.load("Advapi32", Advapi32::class.java, W32APIOptions.DEFAULT_OPTIONS) }

    private fun target(profileId: String) = WString("$namespace/$profileId")

    override fun status(): VaultStatus = try {
        val maximumPersist = IntArray(CRED_TYPE_MAXIMUM)
        if (!api.CredGetSessionTypes(CRED_TYPE_MAXIMUM, maximumPersist)) {
            val code = Native.getLastError()
            if (code == ERROR_NO_SUCH_LOGON_SESSION) return VaultStatus.Unavailable("Windows has no credential store for this session.")
            return VaultStatus.Unavailable("The Windows credential store could not be loaded.")
        }
        if (maximumPersist[CRED_TYPE_GENERIC] >= CRED_PERSIST_LOCAL_MACHINE) VaultStatus.Available
        else VaultStatus.Unavailable("Windows does not allow saved credentials for this account.")
    } catch (_: UnsatisfiedLinkError) {
        VaultStatus.Unavailable("The Windows credential store could not be loaded.")
    } catch (_: NoClassDefFoundError) {
        VaultStatus.Unavailable("The Windows credential store could not be loaded.")
    }

    override fun store(profileId: String, login: String, label: String, secret: CharArray) {
        val bytes = encodeSecretUtf8(secret)
        if (bytes.size > CRED_MAX_CREDENTIAL_BLOB_SIZE) {
            bytes.fill(0)
            throw VaultException("This secret is too long for Windows Credential Manager.")
        }
        val memory = Memory(bytes.size.coerceAtLeast(1).toLong())
        try {
            if (bytes.isNotEmpty()) memory.write(0, bytes, 0, bytes.size)
            val cred = Credential()
            cred.Type = CRED_TYPE_GENERIC
            cred.Persist = CRED_PERSIST_LOCAL_MACHINE
            cred.TargetName = target(profileId)
            cred.UserName = WString(login)
            cred.Comment = WString(label.take(CRED_MAX_COMMENT))
            cred.CredentialBlobSize = bytes.size
            cred.CredentialBlob = if (bytes.isEmpty()) null else memory
            if (!api.CredWriteW(cred, 0)) throw VaultException(failure("could not save the secret"))
        } finally {
            memory.clear()
            bytes.fill(0)
        }
    }

    override fun read(profileId: String): CharArray? {
        val ref = PointerByReference()
        if (!api.CredReadW(target(profileId), CRED_TYPE_GENERIC, 0, ref)) {
            val code = Native.getLastError()
            if (code == ERROR_NOT_FOUND) return null
            throw VaultException(failure("could not read the saved secret"))
        }
        val pointer = ref.value ?: throw VaultException(failure("could not read the saved secret"))
        try {
            val cred = Credential(pointer)
            val size = cred.CredentialBlobSize
            val blob = cred.CredentialBlob
            if (size == 0 || blob == null) return CharArray(0)
            if (size < 0 || size > CRED_MAX_CREDENTIAL_BLOB_SIZE) throw VaultException(failure("returned a secret that could not be read"))
            val bytes = blob.getByteArray(0, size)
            return try {
                blob.setMemory(0, size.toLong(), 0)
                decodeSecretUtf8(bytes)
            } finally {
                bytes.fill(0)
            }
        } finally {
            api.CredFree(pointer)
        }
    }

    override fun forget(profileId: String) {
        if (api.CredDeleteW(target(profileId), CRED_TYPE_GENERIC, 0)) return
        val code = Native.getLastError()
        if (code == ERROR_NOT_FOUND) return
        throw VaultException(failure("could not remove the saved secret"))
    }

    override fun forgetAll() {
        val count = IntByReference()
        val ref = PointerByReference()
        if (!api.CredEnumerateW(WString("$namespace/*"), 0, count, ref)) {
            val code = Native.getLastError()
            if (code == ERROR_NOT_FOUND) return
            throw VaultException(failure("could not list saved secrets"))
        }
        val pointer = ref.value ?: return
        try {
            val found = count.value
            if (found <= 0) return
            val pointers = pointer.getPointerArray(0, found)
            for (item in pointers) {
                if (item == null) continue
                val listed = Credential(item)
                // Enumeration hands back the secrets too. They are not needed here: clear them before the buffer is freed.
                val blob = listed.CredentialBlob
                if (blob != null && listed.CredentialBlobSize in 1..CRED_MAX_CREDENTIAL_BLOB_SIZE) blob.setMemory(0, listed.CredentialBlobSize.toLong(), 0)
                val name = listed.TargetName?.toString() ?: continue
                if (!name.startsWith("$namespace/")) continue
                if (api.CredDeleteW(WString(name), CRED_TYPE_GENERIC, 0)) continue
                val code = Native.getLastError()
                if (code != ERROR_NOT_FOUND) throw VaultException(failure("could not remove a saved secret"))
            }
        } finally {
            api.CredFree(pointer)
        }
    }

    /** Win32 codes only. The target name holds the profile id, so it stays out of the message. */
    private fun failure(what: String): String {
        val code = Native.getLastError()
        return "Windows Credential Manager $what (code $code)."
    }

    private companion object {
        const val CRED_TYPE_GENERIC = 1
        const val CRED_PERSIST_LOCAL_MACHINE = 2
        const val CRED_TYPE_MAXIMUM = 7
        const val ERROR_NOT_FOUND = 1168
        const val ERROR_NO_SUCH_LOGON_SESSION = 1312
        const val CRED_MAX_CREDENTIAL_BLOB_SIZE = 2560
        const val CRED_MAX_COMMENT = 256
    }
}
