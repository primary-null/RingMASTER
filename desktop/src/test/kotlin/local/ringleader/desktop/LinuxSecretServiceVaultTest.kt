package local.ringleader.desktop

import java.util.UUID
import local.ringleader.core.VaultStatus
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * libsecret. On a machine with no session bus or password safe, status is Unavailable and nothing is stored.
 * A full round trip runs only when RINGMASTER_LIVE_VAULT=1, under a test schema, and is removed in finally.
 */
class LinuxSecretServiceVaultTest {
    @Test fun withoutALiveVaultStatusIsUnavailable() {
        assumeTrue(isLinux(), "Secret Service is tested on Linux")
        assumeTrue(System.getenv("RINGMASTER_LIVE_VAULT") != "1", "the live round trip covers a running password safe")
        val vault = LinuxSecretServiceVault("local.ringleader.RingMASTER.test-" + UUID.randomUUID())
        val status = vault.status()
        if (status !is VaultStatus.Unavailable) {
            assumeTrue(false, "A password safe is running. This check is the no-provider case. Set RINGMASTER_LIVE_VAULT=1 for the round trip.")
            return
        }
        assertTrue(status.reason.isNotBlank())
    }

    @Test fun aLiveTestSchemaRoundTripsWhenAsked() {
        assumeTrue(isLinux(), "Secret Service is tested on Linux")
        assumeTrue(System.getenv("RINGMASTER_LIVE_VAULT") == "1", "set RINGMASTER_LIVE_VAULT=1 on a machine with a password safe")
        val vault = LinuxSecretServiceVault("local.ringleader.RingMASTER.test-" + UUID.randomUUID())
        val id = UUID.randomUUID().toString()
        val secret = "invented-café-鍵-9f3a".toCharArray()
        try {
            val status = vault.status()
            assertTrue(status is VaultStatus.Available, "the live password safe did not answer: ${(status as? VaultStatus.Unavailable)?.reason}")
            vault.store(id, "tester@pve!read", "RingMASTER test", secret)
            val read = vault.read(id)
            try {
                assertTrue(read != null && read.size == secret.size && read.contentEquals(secret), "the secret read back did not match")
            } finally {
                read?.fill('\u0000')
            }
            vault.forget(id)
            assertNull(vault.read(id))
            // Clear everything must remove this schema's items only. An item under another schema stands in for the
            // user's other passwords in the same safe: it has to survive.
            val other = LinuxSecretServiceVault("local.ringleader.RingMASTER.test-other-" + UUID.randomUUID())
            val otherId = UUID.randomUUID().toString()
            try {
                other.store(otherId, "someone", "RingMASTER test (other schema)", secret)
                vault.store(id, "tester@pve!read", "RingMASTER test", secret)
                val second = UUID.randomUUID().toString()
                vault.store(second, "tester@pve!read", "RingMASTER test", secret)
                vault.forgetAll()
                assertNull(vault.read(id))
                assertNull(vault.read(second), "forgetAll removes every item of this schema")
                val kept = other.read(otherId)
                try {
                    assertTrue(kept != null && kept.contentEquals(secret), "forgetAll removed an item of another schema")
                } finally {
                    kept?.fill('\u0000')
                }
                println("LIVE| round trip, forget, forgetAll and the other-schema item all behaved")
            } finally {
                runCatching { other.forgetAll() }
            }
        } finally {
            secret.fill('\u0000')
            runCatching { vault.forgetAll() }
        }
    }

    private fun isLinux() = System.getProperty("os.name").orEmpty().lowercase().contains("linux")
}
