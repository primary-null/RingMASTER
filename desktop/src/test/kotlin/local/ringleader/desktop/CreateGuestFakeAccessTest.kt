package local.ringleader.desktop

import kotlinx.coroutines.runBlocking
import local.ringleader.core.Connection
import local.ringleader.core.FakeCreate
import local.ringleader.core.FakeCreateGuestGateway
import local.ringleader.core.FakeRead
import local.ringleader.core.GuestPermissionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The core's shared create fake (core test fixtures) is on the desktop test classpath, ready for the wizard tests. */
class CreateGuestFakeAccessTest {
    @Test fun desktopTestsCanScriptTheCreateFake() = runBlocking {
        val fake = FakeCreateGuestGateway().apply {
            pools = FakeRead.denied()
            create = FakeCreate.LosesReply
        }
        val connection = Connection("https://192.0.2.10:8006", "ring@pve!test", "test-only-secret", "")
        assertFailsWith<GuestPermissionException> { fake.pools(connection) }
        assertEquals(listOf("pools"), fake.calls)
    }
}
