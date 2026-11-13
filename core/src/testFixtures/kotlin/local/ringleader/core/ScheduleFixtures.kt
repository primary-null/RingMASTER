package local.ringleader.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred

/** Invented scheduler fixtures: documentation-style host names, a test-only secret and synthetic identities. */
object ScheduleFixtures {
    const val ORIGIN = "https://pve.example.test:8006"
    const val PROFILE_ID = "11111111-2222-4333-8444-555555555555"
    const val SECRET = "test-only-scheduler-secret"
    const val UUID_A = "0f0e0d0c-0b0a-4908-8706-050403020100"
    const val PIN = "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd"

    fun profile(pin: String? = PIN, token: String = "sched@pve!runner", kind: String = DIRECT_PVE_KIND, id: String = PROFILE_ID, name: String = "Lab cluster") =
        ConnectionProfile(id, name, kind, ORIGIN, token, pin)

    fun facts(uuid: String? = UUID_A, ctime: Long? = 1_700_000_000, lock: String? = null, template: Boolean = false) =
        GuestIdentityFacts(uuid, ctime, "VM|alpha|l26|local-lvm:vm-104-disk-0", lock, template)

    fun target(vmid: Int = 104, kind: String = "VM", identity: GuestIdentity = facts().best(false)!!, profile: ConnectionProfile = profile()) =
        ScheduleTarget(profile.id, profileBinding(profile), "node-a", kind, vmid, "alpha", identity)

    fun plan(
        time: LocalTime = LocalTime.of(7, 0), action: ScheduleAction = ScheduleAction.Start, vmid: Int = 104,
        repeat: ScheduleRepeat = ScheduleRepeat(RepeatKind.Daily), zone: ZoneId = ZoneId.of("Europe/Berlin"),
        notBefore: Instant = Instant.parse("2026-11-01T00:00:00Z"), id: String = UUID.randomUUID().toString(), kind: String = "VM",
    ) = SchedulePlan(id, "Plan $vmid ${time}", 1, action, repeat, time, zone, target(vmid, kind), true, notBefore, notBefore)

    fun once(date: LocalDate, time: LocalTime) = ScheduleRepeat(RepeatKind.Once, date = date)

    fun upid(node: String = "node-a", vmid: Int = 104, type: String = "qmstart") = "UPID:$node:00000010:00000011:65ABCDEF:$type:$vmid:sched@pve!runner:"

    fun guest(vmid: Int = 104, status: String = "stopped", node: String = "node-a", template: Boolean = false, kind: String = "VM") =
        Guest(ORIGIN, vmid, "alpha", node, kind, status, template)
}

class FakeScheduleSecrets(var secret: String? = ScheduleFixtures.SECRET, var remembered: Boolean = true, var status: VaultStatus = VaultStatus.Available) : ScheduleSecrets {
    var reads = 0
    override val vaultName = "the test vault"
    override fun status() = status
    override fun remembered(profileId: String) = remembered
    override fun read(profileId: String): CharArray? { reads++; return secret?.toCharArray() }
}

/** Scriptable gateway. Every call is counted; [guestsGate] and [dispatchGate] can hold a run at a precise point. */
class FakeScheduleGateway : ScheduleGateway {
    var guests = mutableListOf(ScheduleFixtures.guest())
    var facts = ScheduleFixtures.facts()
    var state = ScheduledGuestState("stopped", null, null, false)
    var dump = PermissionDump(mapOf("/vms" to mapOf("VM.PowerMgmt" to true, "VM.Audit" to true)))
    var dispatch: suspend (ScheduleAction) -> String = { ScheduleFixtures.upid() }
    val statuses = ArrayDeque<suspend () -> PowerTaskStatus>()
    var defaultStatus: suspend () -> PowerTaskStatus = { PowerTaskStatus(PowerPhase.Succeeded, "Stopped with exit status OK.") }
    var guestsGate: CompletableDeferred<Unit>? = null
    var dispatchGate: CompletableDeferred<Unit>? = null
    var stateGate: CompletableDeferred<Unit>? = null
    var guestReads = 0; var identityReads = 0; var stateReads = 0; var permissionReads = 0; var dispatches = 0; var statusReads = 0; var logReads = 0
    val secretsSeen = mutableListOf<String>()

    override suspend fun guests(connection: Connection): List<Guest> {
        guestReads++
        connection.withSecret { secretsSeen += it }
        guestsGate?.await()
        return guests.toList()
    }
    override suspend fun identity(connection: Connection, node: String, kind: String, vmid: Int): GuestIdentityFacts { identityReads++; return facts }
    override suspend fun state(connection: Connection, node: String, kind: String, vmid: Int): ScheduledGuestState { stateReads++; stateGate?.await(); return state }
    override suspend fun permissions(connection: Connection): PermissionDump { permissionReads++; return dump }
    override suspend fun dispatch(connection: Connection, node: String, kind: String, vmid: Int, action: ScheduleAction): String {
        dispatches++
        dispatchGate?.await()
        return dispatch(action)
    }
    override suspend fun taskStatus(connection: Connection, node: String, upid: String): PowerTaskStatus {
        statusReads++
        return (statuses.removeFirstOrNull() ?: defaultStatus)()
    }
    override suspend fun taskLog(connection: Connection, node: String, upid: String, start: Int, limit: Int): TaskLogPage {
        logReads++
        return TaskLogPage(listOf(TaskLogLine(1, "starting guest"), TaskLogLine(2, "TASK OK")), 2)
    }
}
