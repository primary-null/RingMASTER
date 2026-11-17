package local.ringleader.core

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/*
 * A workspace connected to an invented fleet, for tests and pictures that need a populated screen. It is a real
 * connection in every way the workspace can tell: the answers come from fake gateways below instead of a server, so
 * every read goes through the same code as against a real one, and nothing is sent anywhere.
 */

const val SAMPLE_ACCOUNT = "tester@pve!sample"

fun sampleConnection(): Connection = Connection(SAMPLE_ADDRESS, SAMPLE_ACCOUNT, "sample-secret", "")

class SampleInventory : InventoryGateway {
    override suspend fun overview(connection: Connection): Overview = sampleOverview(connection.remoteKey)
    override suspend fun details(connection: Connection, guest: Guest): GuestDetails = sampleDetails(guest)
}

class SampleTasks : TaskGateway {
    override suspend fun tasks(connection: Connection): List<ClusterTask> = sampleTasks(connection.remoteKey)
}

class SampleNodeStatus : NodeStatusGateway {
    override suspend fun status(connection: Connection, origin: String, node: String, scope: String): NodeLiveStatus {
        val gib = 1024L * 1024 * 1024
        return NodeLiveStatus(node, 0.18, 21 * gib, 64 * gib, 12 * gib, 94 * gib, 1_209_600, "9.2.23")
    }
}

class SampleInspection : GuestInspectionGateway {
    override suspend fun read(connection: Connection, guest: Guest, storages: List<String>, timeframe: String): GuestInspection =
        sampleInspection(guest, timeframe)
}

class SampleTaskLog : TaskLogGateway {
    private val lines = listOf("INFO: starting", "INFO: sample task running", "INFO: finished").mapIndexed { n, text -> TaskLogLine(n + 1, text) }

    override suspend fun status(connection: Connection, node: String, upid: String): TaskLogStatus {
        val started = Instant.parse("2026-11-07T15:04:00Z")
        return TaskLogStatus("stopped", "OK", started, started.plusSeconds(42), 4242, "vzdump", "121", SAMPLE_ACCOUNT, node)
    }

    override suspend fun page(connection: Connection, node: String, upid: String, start: Int, limit: Int): TaskLogPage =
        TaskLogPage(lines.drop(start).take(limit), lines.size)
}

/** The sample account may do everything, so a test of an action is not stopped by a missing right it did not ask about. */
class SamplePermissions : PermissionsGateway {
    override suspend fun read(connection: Connection): PermissionDump = PermissionDump(mapOf("/" to PVE_PRIVILEGES.associateWith { true }))
}

class SampleNodeTools : NodeToolsGateway {
    override suspend fun services(connection: Connection, node: String): List<NodeService> = sampleNodeServices

    override suspend fun syslog(connection: Connection, node: String, start: Int, limit: Int, service: String?): SyslogPage =
        sampleSyslogPage(node, start, limit, SAMPLE_SYSLOG_START_TOTAL)

    override suspend fun updates(connection: Connection, node: String): List<PackageUpdate> = samplePackageUpdates
    override suspend fun network(connection: Connection, node: String): NodeNetworkReport = NodeNetworkReport(sampleNodeNetwork, null)
}

class SampleStorage(private val now: () -> Instant = Instant::now) : StorageGateway {
    override suspend fun config(connection: Connection): List<StorageConfigRow> = sampleStorageConfig

    override suspend fun history(connection: Connection, node: String, storage: String, timeframe: String, cf: String): List<HistoryPoint> {
        val recorded = sampleOverview(connection.remoteKey).storage.first { it.node == node && it.id == storage }
        return sampleStorageHistory(recorded, timeframe, cf, now())
    }
}

/** Takes no action: a test that sends one supplies its own gateway, so a stray click in a picture test fails loudly. */
class SamplePower : PowerGateway {
    override suspend fun submit(connection: Connection, guest: Guest, action: PowerAction): String =
        error("The sample fleet takes no power action. Give the test its own PowerGateway.")

    override suspend fun status(connection: Connection, node: String, upid: String): PowerTaskStatus =
        error("The sample fleet has no task to check.")
}

/**
 * A create gateway whose ISO images and container templates are what [shelf] holds, as on a server, where the wizard and
 * the storage page read one list: a file a test uploads to a storage is then offered by the wizard.
 */
fun sampleCreateGateway(shelf: SampleStorageShelf = SampleStorageShelf()): FakeCreateGuestGateway = FakeCreateGuestGateway().apply {
    shelfVolumes = { connection, node, storage, content -> shelf.volumes(connection.remoteKey, node, storage).filter { it.content == content } }
}

/** Waits until the inventory has arrived. For a scope whose dispatcher queues the connect instead of running it at once. */
suspend fun Workspace.awaitInventory(): Workspace {
    state.first { it.overview != null }
    return this
}

/**
 * Waits until the state is [ready] and returns it: for what arrives a moment after it was asked for, such as a list the
 * wizard reads or the end of a task. Fails after 15 seconds instead of waiting for ever.
 */
suspend fun Workspace.awaitState(ready: (WorkspaceState) -> Boolean): WorkspaceState = withTimeout(15_000) { state.first(ready) }

/**
 * A workspace over the sample fleet, already told to connect. The inventory arrives on [scope]'s dispatcher: a test on a
 * test dispatcher advances it (runCurrent), a scene test waits with [awaitInventory].
 */
fun sampleWorkspace(
    scope: CoroutineScope,
    powerGateway: PowerGateway = SamplePower(),
    createGateway: CreateGuestGateway? = null,
    clock: () -> Instant = { Instant.now() },
    shelf: SampleStorageShelf = SampleStorageShelf(),
): Workspace {
    val workspace = Workspace(
        scope,
        gateway = SampleInventory(), taskGateway = SampleTasks(), powerGateway = powerGateway,
        nodeStatusGateway = SampleNodeStatus(), inspectionGateway = SampleInspection(), clock = clock,
        taskLogGateway = SampleTaskLog(), permissionsGateway = SamplePermissions(), nodeToolsGateway = SampleNodeTools(),
        storageContentGateway = SampleStorageContent(shelf), storageGateway = SampleStorage(clock), createGateway = createGateway ?: sampleCreateGateway(shelf),
        storageActionGateway = SampleStorageActions(shelf),
        trustSource = { null }, fleetRadioSource = SampleFleetRadioSource(), guestRadioGateway = SampleGuestRadioGateway(clock),
    )
    workspace.connect(sampleConnection())
    return workspace
}
