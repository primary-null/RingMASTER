package local.ringleader.desktop

import java.util.UUID
import local.ringleader.core.VaultException
import local.ringleader.core.VaultStatus
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real Windows Credential Manager, under a test namespace only.
 * Invented secrets are never printed. Entries are removed in finally, including when a check fails.
 */
class WindowsCredentialVaultTest {
    @Test fun credentialStructureIsEightyBytesOn64Bit() {
        assumeTrue(isWindows(), "Windows Credential Manager is tested on Windows")
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        assumeTrue(arch == "amd64" || arch == "x86_64", "CREDENTIALW is 80 bytes on 64-bit")
        assertEquals(80, windowsCredentialStructureSize())
    }

    @Test fun aTestNamespaceRoundTripsAndForgetAllLeavesTheOtherNamespace() {
        assumeTrue(isWindows(), "Windows Credential Manager is tested on Windows")
        val vault = WindowsCredentialVault("RingMASTER-test-" + UUID.randomUUID())
        val other = WindowsCredentialVault("RingMASTER-test-" + UUID.randomUUID())
        val id = UUID.randomUUID().toString()
        val otherId = UUID.randomUUID().toString()
        val secret = "invented-café-鍵-9f3a".toCharArray()
        val otherSecret = "invented-other-namespace-22c".toCharArray()
        val tooLong = CharArray(3000) { 'x' }
        try {
            val status = vault.status()
            assumeTrue(
                status is VaultStatus.Available,
                "Windows credential store unavailable: ${(status as? VaultStatus.Unavailable)?.reason}",
            )
            vault.store(id, "tester@pve!read", "RingMASTER test", secret)
            val read = vault.read(id)
            try {
                assertSameSecret(secret, read)
            } finally {
                read?.fill('\u0000')
            }
            vault.forget(id)
            assertNull(vault.read(id))
            vault.store(id, "tester@pve!read", "RingMASTER test", secret)
            other.store(otherId, "tester@pve!read", "RingMASTER test other", otherSecret)
            vault.forgetAll()
            assertNull(vault.read(id))
            val survived = other.read(otherId)
            try {
                assertSameSecret(otherSecret, survived)
            } finally {
                survived?.fill('\u0000')
            }
            var refused = false
            try {
                vault.store(UUID.randomUUID().toString(), "tester@pve!read", "RingMASTER test", tooLong)
            } catch (e: VaultException) {
                refused = true
                val message = e.message.orEmpty()
                assertTrue(message.contains("too long"), "a 3000-byte secret is refused before it is stored")
                assertTrue('x' !in message, "the refusal does not repeat the secret")
                assertTrue(id !in message && otherId !in message, "the refusal does not name a profile")
            } finally {
                tooLong.fill('\u0000')
            }
            assertTrue(refused, "a 3000-byte secret is refused")
        } finally {
            secret.fill('\u0000')
            otherSecret.fill('\u0000')
            tooLong.fill('\u0000')
            runCatching { vault.forgetAll() }
            runCatching { other.forgetAll() }
        }
    }

    private fun assertSameSecret(expected: CharArray, actual: CharArray?) {
        assertTrue(actual != null && actual.size == expected.size && actual.contentEquals(expected), "the secret read back did not match")
    }

    private fun isWindows() = System.getProperty("os.name").orEmpty().startsWith("Windows")
}
