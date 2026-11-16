package local.ringleader.core

import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A connection that never got its first inventory is a failed one, whatever the reason: the pages show the failure and
 * what to do, never an empty workspace that claims to be connected (field report, 0.25.9: a second server opened with
 * normal TLS left a session "connected" with nothing in it and only a retry status).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionFailedTest {
    private val server = "https://pve-b.example.test:8006"
    private val overview = Overview(1, listOf(Guest(server, 100, "alpha", "node-a", "VM", "running")))

    private class Gateway(var read: suspend (Connection) -> Overview) : InventoryGateway {
        override suspend fun overview(connection: Connection) = read(connection)
        override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
    }

    @Test fun aRefusedCertificateOnTheFirstReadIsAFailedConnectionAndSaysWhatToDo() = runTest {
        // The chain the JDK builds for an untrusted server: handshake, the validator's CertificateException, the path builder.
        val gateway = Gateway { throw SSLHandshakeException("PKIX path building failed").apply {
            initCause(java.security.cert.CertificateException("PKIX path building failed", java.security.cert.CertPathBuilderException("unable to find valid certification path")))
        } }
        val workspace = Workspace(this, gateway)
        workspace.connect(Connection(server, "root@pam", "typed-password", ""))
        runCurrent()
        val state = workspace.state.value
        // The session is kept (it holds the typed secret and tries again), but it is not an open connection.
        assertTrue(state.connected); assertNull(state.overview)
        assertEquals(NetworkFailure.Untrusted, state.failure); assertEquals(NetworkFailure.Untrusted.sentence, state.error)
        assertFalse(sessionOpen(state)); assertTrue(connectionFailed(state))
        assertEquals(server, state.address); assertEquals("root@pam", state.account)
        val advice = assertNotNull(connectionFailureAdvice(state))
        assertTrue("Trust and connect" in advice && "normal TLS" in advice, advice)
        // Diagnostics no longer call it connected.
        val reported = assertNotNull(workspace.diagnostic())
        assertFalse(reported.connected); assertFalse(reported.overviewLoaded); assertEquals(NetworkFailure.Untrusted.sentence, reported.lastError)
        // The account is on screen only: never in the bundle.
        assertFalse(reported.toString().contains("root@pam"))
        // Try again is a plain refresh; once the server answers, the same session is open, with nothing left of the failure.
        gateway.read = { overview }
        workspace.refresh(force = true); runCurrent()
        val after = workspace.state.value
        assertTrue(sessionOpen(after)); assertFalse(connectionFailed(after)); assertNull(connectionFailureAdvice(after))
        assertTrue(assertNotNull(workspace.diagnostic()).connected)
        workspace.close()
    }

    @Test fun aRefusedPasswordClosesTheSessionAndTheCardCanSayWhichUserWasSent() = runTest {
        val workspace = Workspace(this, Gateway { throw LoginRefusedException() })
        workspace.connect(Connection(server, "root@pam", "typed-password", "ab".repeat(32)))
        runCurrent()
        val state = workspace.state.value
        assertFalse(state.connected); assertEquals(LOGIN_REFUSED, state.error)
        assertTrue(connectionFailed(state))
        assertEquals(server, state.failedAddress); assertEquals("root@pam", state.account)
        val advice = assertNotNull(connectionFailureAdvice(state))
        assertTrue("root@pam" in advice && "web page" in advice, advice)
        assertFalse(state.toString().contains("typed-password"))
        // Disconnect forgets the account with everything else.
        workspace.disconnect()
        assertEquals("", workspace.state.value.account); assertFalse(connectionFailed(workspace.state.value))
        workspace.close()
    }

    @Test fun anUnreachableServerOnTheFirstReadIsAFailedConnectionWithoutExtraAdvice() = runTest {
        val workspace = Workspace(this, Gateway { throw IOException("no route") })
        workspace.connect(Connection(server, "tester@pve!read", "token-secret", ""))
        runCurrent()
        val state = workspace.state.value
        assertTrue(connectionFailed(state)); assertTrue(state.connected)
        assertEquals("The server could not be reached. Check the network and try again.", state.error)
        assertNull(state.failure, "an IOException of no known kind is none of the eleven causes")
        assertNull(connectionFailureAdvice(state))
        workspace.close()
    }

    @Test fun aRefusedApiTokenOnANewConnectionSaysItIsTheToken() = runTest {
        val workspace = Workspace(this, Gateway { throw SessionExpiredException() })
        workspace.connect(Connection(server, "tester@pve!read", "token-secret", ""))
        runCurrent()
        val state = workspace.state.value
        assertFalse(state.connected); assertEquals(SESSION_REFUSED, state.error); assertTrue(connectionFailed(state))
        assertTrue("API token" in assertNotNull(connectionFailureAdvice(state)))
        workspace.close()
    }

    @Test fun anOpenConnectionWithALaterErrorAndTheDemoAreNotFailedConnections() = runTest {
        val gateway = Gateway { overview }
        val workspace = Workspace(this, gateway)
        workspace.connect(Connection(server, "tester@pve!read", "token-secret", ""))
        runCurrent()
        gateway.read = { throw IOException("later") }
        workspace.refresh(force = true); runCurrent()
        val stale = workspace.state.value
        assertNotNull(stale.error); assertTrue(stale.stale)
        assertTrue(sessionOpen(stale)); assertFalse(connectionFailed(stale)); assertNull(connectionFailureAdvice(stale))
        workspace.exploreDemo()
        assertFalse(connectionFailed(workspace.state.value)); assertEquals("", workspace.state.value.account)
        assertFalse(connectionFailed(WorkspaceState()))
        workspace.close()
    }
}
