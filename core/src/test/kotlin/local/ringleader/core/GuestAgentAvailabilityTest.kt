package local.ringleader.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlin.test.*

class GuestAgentAvailabilityTest {
    private val guest = Guest("https://pve.example.test:8006", 991, "fixture", "node", "VM", "running")
    private val enabled = parseHardware("""{"data":{"agent":"1","ostype":"l26","meta":"ctime=1"}}""")
    private var state = WorkspaceState(connected = true)
    private var generation = 0L
    private val access = GuestAgentAvailability({ state }, { generation })

    @Test fun denialEnablesFallbackOnlyForExactGuestAndSession() = runBlocking {
        assertNull(access.refusal(guest, enabled))
        assertFailsWith<GuestAgentFailure> {
            access.observe(guest) { throw GuestAgentFailure(GuestRefusal.NoPermission) }
        }
        assertEquals(GuestRefusal.NoPermission, access.refusal(guest, enabled))
        assertTrue(access.refusal(guest, enabled)!!.engineCouldHelp)
        assertNull(access.refusal(guest.copy(id = 992), enabled))
        val other = GuestAgentAvailability({ state }, { generation })
        assertNull(other.refusal(guest, enabled))
        state = state.copy(connectionEpoch = state.connectionEpoch + 1)
        assertNull(access.refusal(guest, enabled))
    }

    @Test fun targetRefreshAndLateAnswersCannotRestoreStaleDenial() = runBlocking {
        assertFailsWith<GuestAgentFailure> {
            access.observe(guest) {
                generation++
                throw GuestAgentFailure(GuestRefusal.NoPermission)
            }
        }
        assertNull(access.refusal(guest, enabled))
        assertFailsWith<GuestAgentFailure> {
            access.observe(guest) { throw GuestAgentFailure(GuestRefusal.NoPermission) }
        }
        access.invalidate()
        assertNull(access.refusal(guest, enabled))
    }

    @Test fun unknownDisabledStoppedAndRemoteNeverAuthorizeMutation() {
        assertEquals(GuestRefusal.ConfigLoading, access.refusal(guest, null))
        assertEquals(GuestRefusal.AgentOff, access.refusal(guest, parseHardware("""{"data":{"agent":"0"}}""")))
        assertEquals(GuestRefusal.NotRunning, access.refusal(guest.copy(status = "stopped"), enabled))
        assertEquals(GuestRefusal.Container, access.refusal(guest.copy(kind = "Container"), enabled))
        state = state.copy(kind = PDM_KIND)
        assertEquals(GuestRefusal.PdmSession, access.refusal(guest, enabled))
        state = state.copy(kind = "", connected = false)
        assertEquals(GuestRefusal.ConfigLoading, access.refusal(guest, enabled))
    }
    @Test fun enabledGuestDispatchesOnceAndDenialBlocksAnotherMutation() = runBlocking {
        val workspace = sampleWorkspace(this)
        try {
            workspace.state.first { it.overview != null }
            val live = workspace.state.value.overview!!.guests.first { it.kind == "VM" && it.status == "running" }
            val access = GuestAgentAvailability({ workspace.state.value }, { 0L }, { _, _ -> enabled }, { _, g -> g })
            var calls = 0
            assertEquals("removed", access.mutate(workspace, live, access.revision(), enabled) { _, _ -> calls++; "removed" })
            assertEquals(1, calls)
            assertFailsWith<GuestAgentFailure> {
                access.mutate(workspace, live, access.revision(), enabled) { _, _ -> calls++; throw GuestAgentFailure(GuestRefusal.NoPermission) }
            }
            assertFailsWith<GuestAgentFailure> {
                access.mutate(workspace, live, access.revision(), enabled) { _, _ -> calls++ }
            }
            assertEquals(2, calls)
        } finally { workspace.close() }
    }

    @Test fun configReadCannotCarryConsentAcrossRefresh() = runBlocking {
        val workspace = sampleWorkspace(this)
        try {
            workspace.state.first { it.overview != null }
            val live = workspace.state.value.overview!!.guests.first { it.kind == "VM" && it.status == "running" }
            var revision = 0L
            val access = GuestAgentAvailability({ workspace.state.value }, { revision }, { _, _ -> revision++; enabled }, { _, g -> g })
            var calls = 0
            assertFailsWith<IllegalStateException> { access.mutate(workspace, live, access.revision(), enabled) { _, _ -> calls++ } }
            assertEquals(0, calls)
        } finally { workspace.close() }
    }

    @Test fun anotherPairingsDenialDoesNotForgetThisGuestsRefusal() = runBlocking {
        val other = guest.copy(id = 992)
        for (target in listOf(guest, other)) {
            assertFailsWith<GuestAgentFailure> {
                access.observe(target) { throw GuestAgentFailure(GuestRefusal.NoPermission) }
            }
        }
        assertEquals(GuestRefusal.NoPermission, access.refusal(guest, enabled))
        assertEquals(GuestRefusal.NoPermission, access.refusal(other, enabled))
        access.invalidate()
        assertNull(access.refusal(guest, enabled))
        assertNull(access.refusal(other, enabled))
    }

    @Test fun freshServerChangesCannotUseUnchangedCachedInventoryOrUnpair() = runBlocking {
        val workspace = sampleWorkspace(this)
        try {
            workspace.state.first { it.overview != null }
            val cached = workspace.state.value.overview!!.guests.first { it.kind == "VM" && it.status == "running" }
            val wire = local.ringleader.core.integrations.ServiceWire("RingMASTER/test")
            val pairings = local.ringleader.core.guesthelper.GuestHelperPairings(
                local.ringleader.core.guesthelper.GuestHelperStore(null), NoVault("Test"), wire)
            val link = local.ringleader.core.guesthelper.GuestHelperLink("fixture",
                local.ringleader.core.guesthelper.guestHelperPlace(cached), "192.0.2.7", 8696,
                "a".repeat(64), "0.1.0", "fixture", java.time.Instant.EPOCH, null)
            pairings.save(link, "AAAAA-BBBBB-CCCCC-DDDDD-EEEEE".toCharArray()).getOrThrow()
            var calls = 0
            for (mode in listOf("renamed", "replaced", "identityMissing", "unreadable")) {
                val changed = parseHardware("""{"data":{"agent":"1","ostype":"l26","meta":"ctime=2"}}""")
                val access = GuestAgentAvailability({ workspace.state.value }, { 0L },
                    { _, _ -> when (mode) {
                        "replaced" -> changed
                        "identityMissing" -> parseHardware("""{"data":{"agent":"1","ostype":"l26"}}""")
                        else -> enabled
                    } },
                    { _, g ->
                        if (mode == "unreadable") error("Unreadable identity")
                        if (mode == "renamed") g.copy(name = "replacement") else g
                    })
                assertFailsWith<IllegalStateException> {
                    access.mutate(workspace, cached, access.revision(), enabled) { _, _ -> calls++ }
                    pairings.unpair(link.id).getOrThrow()
                }
                assertEquals(0, calls)
                assertNotNull(pairings.link(link.id))
                assertEquals(cached, workspace.state.value.overview!!.guests.first { it.resource == cached.resource })
                val token = assertNotNull(pairings.token(link.id))
                try { assertEquals("AAAAA-BBBBB-CCCCC-DDDDD-EEEEE", token.concatToString()) }
                finally { token.fill('\u0000') }
            }
        } finally { workspace.close() }
    }

}
