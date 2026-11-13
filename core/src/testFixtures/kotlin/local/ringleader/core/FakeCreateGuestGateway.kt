package local.ringleader.core

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred

/**
 * A scripted read for [FakeCreateGuestGateway]: it loads a value, fails, or waits until the test releases it (so a
 * test can look at the "loading" state). An empty list is `FakeRead.empty()`.
 */
sealed interface FakeRead<out T> {
    data class Loads<T>(val value: T) : FakeRead<T>
    data class Fails(val error: Exception) : FakeRead<Nothing>
    /** Stays in flight until [release] is called. */
    class Waits<T> : FakeRead<T> {
        internal val gate = CompletableDeferred<FakeRead<T>>()
        fun release(next: FakeRead<T>) { gate.complete(next) }
    }

    companion object {
        fun <T> loads(value: T): FakeRead<T> = Loads(value)
        fun <E> empty(): FakeRead<List<E>> = Loads(emptyList())
        /** A failure the workspace shows as its message ("Could not read …: <message>"). */
        fun failure(message: String = "The node did not answer."): FakeRead<Nothing> = Fails(IllegalStateException(message))
        /** The server could not be reached. */
        fun unreachable(): FakeRead<Nothing> = Fails(IOException("Connection refused"))
        /** HTTP 403: "This token may not read …". */
        fun denied(): FakeRead<Nothing> = Fails(GuestPermissionException())
        /** HTTP 401: the session ends. */
        fun expired(): FakeRead<Nothing> = Fails(SessionExpiredException())
    }
}

/** A scripted create POST and the task that follows it. */
sealed interface FakeCreate {
    /** Accepted; the task reports running for [runningPolls] status reads, then stops with `OK`. */
    data class Succeeds(val runningPolls: Int = 0, val log: List<String> = listOf("TASK OK")) : FakeCreate

    /**
     * Accepted; the task stops with `WARNINGS: N` where N is the number of [warnings] (D2: finished with warnings).
     * The log holds [warnings] as `WARN: …` lines, then `TASK WARNINGS: N`.
     */
    data class EndsWithWarnings(val warnings: List<String> = listOf("systemd 257 detected. You may need to enable nesting."), val runningPolls: Int = 0) : FakeCreate {
        init { require(warnings.isNotEmpty()) { "Ending with warnings needs at least one warning." } }
    }

    /** Accepted; the task stops with [exitStatus] (a failure), the log ending `TASK ERROR: <exitStatus>`. */
    data class TaskFails(val exitStatus: String = "unable to create VM - storage is full", val log: List<String> = emptyList(), val runningPolls: Int = 0) : FakeCreate

    /** Accepted; the task stops without an exit status (an unknown outcome). */
    data object TaskEndsUnknown : FakeCreate

    /** HTTP 400 or 403: nothing created. [fieldErrors] keys are Proxmox parameter names. */
    data class Rejected(
        val status: Int = 400,
        val message: String = "Parameter verification failed.",
        val fieldErrors: Map<String, String> = mapOf("scsi0" to "storage 'local-lvm' is full"),
    ) : FakeCreate

    /** The body was written and no complete answer came back: the outcome is unknown ([AmbiguousPowerResultException]). */
    data object LosesReply : FakeCreate

    /** HTTP 200 without a task for this guest ([PowerResponseUnclearException]). */
    data object UnclearReply : FakeCreate

    /** The server could not be reached before the body was written: nothing was sent. */
    data object NotSent : FakeCreate

    /** HTTP 401 ([SessionExpiredException]). */
    data object SessionExpires : FakeCreate

    /** The POST stays in flight until [release] is called (to look at the Sending state). */
    class Waits : FakeCreate {
        internal val gate = CompletableDeferred<FakeCreate>()
        fun release(next: FakeCreate) { gate.complete(next) }
    }

    companion object {
        fun fieldErrors(vararg errors: Pair<String, String>): FakeCreate = Rejected(fieldErrors = errors.toMap())
        fun forbidden(message: String = "Permission check failed (/storage/local-lvm, Datastore.AllocateSpace)"): FakeCreate =
            Rejected(status = 403, message = message, fieldErrors = emptyMap())
    }
}

/** What one submit received. It records only whether the secrets were set, never their characters. */
data class FakeSubmit(
    val kind: NewGuestKind,
    val node: String,
    val vmid: Int,
    val params: List<CreateParam>,
    val start: Boolean,
    val secrets: SecretState,
)

/**
 * A [CreateGuestGateway] for core and desktop tests. Every list loads sample data by default (the demo lists); set a
 * property to script it, for example:
 *
 * ```
 * val fake = FakeCreateGuestGateway().apply {
 *     pools = FakeRead.denied()                   // "This token may not read pools."
 *     bridges = FakeRead.empty()                  // "No bridges you may use on this node."
 *     volumes["local" to "iso"] = FakeRead.failure("storage 'local' is not online")
 *     create = FakeCreate.EndsWithWarnings(listOf("nesting is off"))
 * }
 * val workspace = Workspace(scope, inventory, createGateway = fake)
 * ...
 * assertEquals(1, fake.submits.size)              // exactly one POST
 * assertTrue(fake.lastSecrets?.password?.closed == true)   // wiped after the send
 * ```
 *
 * The fake never contacts a server. Task status and log reads follow the scripted [create] by UPID.
 */
class FakeCreateGuestGateway : CreateGuestGateway {
    var nextId: FakeRead<NextIdReply> = FakeRead.loads(NextIdReply.Free(DEMO_CREATE_NEXT_ID))
    /** Typed-ID checks by ID. An ID not listed is free. */
    val idChecks: MutableMap<Int, FakeRead<NextIdReply>> = mutableMapOf()
    var pools: FakeRead<List<PoolChoice>> = FakeRead.loads(listOf(PoolChoice("lab", "Sample pool")))
    var bridges: FakeRead<List<BridgeChoice>> = FakeRead.loads(demoCreateBridges)
    var cpuModels: FakeRead<List<CpuModelChoice>> = FakeRead.loads(demoCreateCpuModels)
    var cpuFlags: FakeRead<List<CpuFlagChoice>> = FakeRead.loads(demoCreateCpuFlags)
    var clusterOptions: FakeRead<ClusterTagRules> = FakeRead.loads(ClusterTagRules(userAllow = "free"))
    /** By (storage, content). A pair not listed loads [demoCreateVolumes] or, failing that, an empty list. */
    val volumes: MutableMap<Pair<String, String>, FakeRead<List<StorageVolume>>> = mutableMapOf()
    var create: FakeCreate = FakeCreate.Succeeds()
    /** When set, every task status read fails with it (the follow keeps the earlier result). */
    var statusFailure: Exception? = null

    private val callLog = mutableListOf<String>()
    private val submitLog = mutableListOf<FakeSubmit>()
    private val tasks = mutableMapOf<String, FakeCreate>()
    private val polls = mutableMapOf<String, Int>()

    /** Every call in order, such as `nextId`, `nextId 105`, `pools`, `bridges node-a`, `volumes node-a local iso`, `submit 105`, `status`, `taskLog`. */
    val calls: List<String> get() = synchronized(this) { callLog.toList() }

    /** Every create POST received. A test asserts its size is 1: nothing is ever retried. */
    val submits: List<FakeSubmit> get() = synchronized(this) { submitLog.toList() }

    /** The secrets passed to the last submit, so a test can check they were wiped afterwards. */
    @Volatile var lastSecrets: CreateSecrets? = null
        private set

    private fun record(call: String) = synchronized(this) { callLog += call }

    private suspend fun <T> resolve(read: FakeRead<T>): T = when (read) {
        is FakeRead.Loads -> read.value
        is FakeRead.Fails -> throw read.error
        is FakeRead.Waits -> resolve(read.gate.await())
    }

    override suspend fun nextId(connection: Connection, vmid: Int?): NextIdReply {
        record(if (vmid == null) "nextId" else "nextId $vmid")
        return if (vmid == null) resolve(nextId)
        else resolve(synchronized(this) { idChecks[vmid] } ?: FakeRead.loads(NextIdReply.Free(vmid)))
    }

    override suspend fun pools(connection: Connection): List<PoolChoice> { record("pools"); return resolve(pools) }

    override suspend fun bridges(connection: Connection, node: String): List<BridgeChoice> { record("bridges $node"); return resolve(bridges) }

    override suspend fun cpuModels(connection: Connection, node: String, arch: String?): List<CpuModelChoice> {
        record("cpuModels $node ${arch ?: "host"}"); return resolve(cpuModels)
    }

    override suspend fun cpuFlags(connection: Connection, node: String, arch: String?, accel: String): List<CpuFlagChoice> {
        record("cpuFlags $node ${arch ?: "host"} $accel"); return resolve(cpuFlags)
    }

    override suspend fun clusterOptions(connection: Connection): ClusterTagRules { record("clusterOptions"); return resolve(clusterOptions) }

    override suspend fun volumes(connection: Connection, node: String, storage: String, content: String): List<StorageVolume> {
        record("volumes $node $storage $content")
        val key = storage to content
        val script = synchronized(this) { volumes[key] } ?: FakeRead.loads(demoCreateVolumes[key].orEmpty())
        return resolve(script)
    }

    override suspend fun submit(connection: Connection, request: CreateRequest, secrets: CreateSecrets): CreateReply {
        record("submit ${request.vmid}")
        synchronized(this) { submitLog += FakeSubmit(request.kind, request.node, request.vmid, request.params, request.start, secrets.state) }
        lastSecrets = secrets
        return reply(create, request)
    }

    private suspend fun reply(script: FakeCreate, request: CreateRequest): CreateReply = when (script) {
        is FakeCreate.Waits -> reply(script.gate.await(), request)
        is FakeCreate.Rejected -> CreateReply.Rejected(script.status, script.message, script.fieldErrors)
        FakeCreate.LosesReply -> throw AmbiguousPowerResultException()
        FakeCreate.UnclearReply -> throw PowerResponseUnclearException("The server response did not identify a task for this guest. It was not sent again.")
        FakeCreate.NotSent -> throw IOException("Connection refused")
        FakeCreate.SessionExpires -> throw SessionExpiredException()
        is FakeCreate.Succeeds, is FakeCreate.EndsWithWarnings, is FakeCreate.TaskFails, FakeCreate.TaskEndsUnknown -> {
            val upid = fakeCreateUpid(request.node, request.vmid, request.kind, synchronized(this) { tasks.size })
            synchronized(this) { tasks[upid] = script }
            CreateReply.Accepted(upid)
        }
    }

    override suspend fun status(connection: Connection, node: String, upid: String): PowerTaskStatus {
        record("status")
        statusFailure?.let { throw it }
        val (script, poll) = synchronized(this) {
            val script = tasks[upid] ?: throw PowerResponseUnclearException("The task status response did not match this task. That is not success.")
            val poll = polls.getOrDefault(upid, 0)
            polls[upid] = poll + 1
            script to poll
        }
        val running = PowerTaskStatus(PowerPhase.Accepted, "The server accepted this action. It is still running.")
        return when (script) {
            is FakeCreate.Succeeds -> if (poll < script.runningPolls) running else PowerTaskStatus(PowerPhase.Succeeded, "Stopped with exit status OK.")
            is FakeCreate.EndsWithWarnings -> if (poll < script.runningPolls) running else PowerTaskStatus(PowerPhase.Unsuccessful, "WARNINGS: ${script.warnings.size}")
            is FakeCreate.TaskFails -> if (poll < script.runningPolls) running else PowerTaskStatus(PowerPhase.Unsuccessful, script.exitStatus)
            else -> PowerTaskStatus(PowerPhase.Unknown, "The task stopped without an exit status. That is not success.")
        }
    }

    override suspend fun taskLog(connection: Connection, node: String, upid: String, start: Int, limit: Int): TaskLogPage {
        record("taskLog")
        val script = synchronized(this) { tasks[upid] }
        val text = when (script) {
            is FakeCreate.Succeeds -> script.log
            is FakeCreate.EndsWithWarnings -> listOf("Creating guest") + script.warnings.map { "WARN: $it" } + "TASK WARNINGS: ${script.warnings.size}"
            is FakeCreate.TaskFails -> script.log + "TASK ERROR: ${script.exitStatus}"
            else -> emptyList()
        }
        val lines = text.mapIndexed { i, line -> TaskLogLine(i + 1, line) }
        return TaskLogPage(lines.drop(start.coerceAtLeast(0)).take(limit.coerceAtLeast(0)), lines.size)
    }
}

/** A UPID that `parseServerUpid` and `parseAcceptedUpid` accept for this node and ID. */
fun fakeCreateUpid(node: String, vmid: Int, kind: NewGuestKind, sequence: Int = 0): String {
    val type = if (kind == NewGuestKind.Vm) "qmcreate" else "vzcreate"
    return "UPID:$node:%08X:%08X:%08X:$type:$vmid:ringmaster@pve!fake:".format(0x1000 + sequence, 0x2000 + sequence, 0x66000000 + sequence)
}
