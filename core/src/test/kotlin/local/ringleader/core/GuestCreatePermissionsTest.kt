package local.ringleader.core

import kotlin.test.*

class GuestCreatePermissionsTest {
    private fun request(vararg params: CreateParam) = CreateRequest(NewGuestKind.Vm, "node-a", 105, "fixture", listOf(CreateParam("vmid", CreateValue.Int(105))) + params)
    @Test fun fullPropagatedPrivilegesNeedNoHints() {
        val dump = PermissionDump(mapOf("/" to PVE_PRIVILEGES.associateWith { true }))
        assertTrue(createPrivilegeHints(dump, request(CreateParam("scsi0", CreateValue.Text("local:32")), CreateParam("net0", CreateValue.Text("virtio,bridge=vmbr0"))), PermissionIdentity.Token).isEmpty())
    }
    @Test fun bridgeHintNamesChosenBridge() {
        val privileges = PVE_PRIVILEGES.filterNot { it == "SDN.Use" }.associateWith { true }
        val hints = createPrivilegeHints(PermissionDump(mapOf("/" to privileges)), request(CreateParam("net0", CreateValue.Text("virtio,bridge=vmbr0,tag=20"))), PermissionIdentity.Token)
        assertEquals(listOf("May lack SDN.Use on /sdn/zones/localnetwork/vmbr0/20."), hints)
    }
    @Test fun poolAllocationAndStartupAreCheckedSeparately() {
        val p = PVE_PRIVILEGES.filterNot { it in listOf("VM.Allocate", "Sys.Modify") }.associateWith { true }
        val hints = createPrivilegeHints(PermissionDump(mapOf("/" to p, "/pool/fixture" to mapOf("VM.Allocate" to false))), request(CreateParam("pool", CreateValue.Text("fixture")), CreateParam("startup", CreateValue.Text("order=1"))), PermissionIdentity.Token)
        assertEquals(listOf("May lack Sys.Modify on /."), hints)
    }
    @Test fun availabilityHandlesDisconnectedPdmStaleAndDenied() {
        assertFalse(createAvailability(WorkspaceState(), NewGuestKind.Vm).available)
        val state = WorkspaceState(demo = true, overview = demoOverview)
        assertTrue(createAvailability(state, NewGuestKind.Container).available)
        assertFalse(createAvailability(state.copy(kind = PDM_KIND), NewGuestKind.Container).available)
        assertFalse(createAvailability(state.copy(stale = true), NewGuestKind.Vm).available)
        assertFalse(createAvailability(state.copy(permissions = ReadSection(value = PermissionsView(PermissionDump(emptyMap()), PermissionIdentity.Token))), NewGuestKind.Vm).available)
        assertTrue(FEATURE_REQUIREMENTS.any { it.id == "create-vm" }); assertTrue(FEATURE_REQUIREMENTS.any { it.id == "create-ct" })
    }
}
