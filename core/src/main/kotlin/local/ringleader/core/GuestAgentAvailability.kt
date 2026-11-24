package local.ringleader.core

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Session-owned evidence. A detail refresh or target change invalidates previous runtime refusals. */
class GuestAgentAvailability(
    private val state: () -> WorkspaceState,
    private val generation: () -> Long,
    private val readHardware: suspend (Connection, Guest) -> HardwareSummary = { connection, guest ->
        parseHardware(PveClient().guestRead(connection, guest, "/config?current=1"))
    },
    private val readGuest: suspend (Connection, Guest) -> Guest = { connection, guest ->
        LiveInventoryGateway().overview(connection).guests.singleOrNull { it.resource == guest.resource }
            ?: error("The guest identity is unreadable. Refresh and confirm again; nothing was sent.")
    },
) {
    private val changes = MutableStateFlow(0L)
    val version = changes.asStateFlow()
    private val refused = ConcurrentHashMap<Triple<Long, Long, Guest>, GuestRefusal>()

    @Volatile private var refreshGeneration = 0L
    fun invalidate() { refused.clear(); refreshGeneration++; changes.update { it + 1 } }
    fun revision() = state().connectionEpoch to (generation() + refreshGeneration)
    private fun identity(guest: Guest) = guest.copy(metrics = null, tags = emptyList(), haState = null, lock = null)
    private fun key(guest: Guest) = Triple(revision().first, revision().second, identity(guest))

    fun refusal(guest: Guest, hardware: HardwareSummary?): GuestRefusal? {
        val s = state()
        val configured = guestRadioRefusal(guest, s.kind != PDM_KIND && guest.scope.isEmpty(), hardware)
        if (configured != null) return configured
        if (!s.connected || s.stale || s.loading) return GuestRefusal.ConfigLoading
        return refused[key(guest)]
    }

    suspend fun <T> observe(guest: Guest, call: suspend () -> T): T {
        val captured = key(guest)
        try { return call() }
        catch (e: GuestAgentFailure) {
            if (captured == key(guest)) {
                refused.keys.removeIf {
                    it.first < captured.first || (it.first == captured.first && it.second < captured.second)
                }
                refused[captured] = e.refusal
                changes.update { it + 1 }
            }
            throw e
        }
    }

    /** Current config only: never infer enabled from a missing report or from pending edits. */
    suspend fun hardware(workspace: Workspace, guest: Guest): HardwareSummary = withContext(Dispatchers.IO) {
        workspace.onGuest(guest) { connection, live ->
            readHardware(connection, live)
        }
    }

    /** Fresh server inventory and incarnation evidence, bound to the configuration the user confirmed. */
    suspend fun <T> mutate(
        workspace: Workspace, guest: Guest, consent: Pair<Long, Long>, confirmedHardware: HardwareSummary,
        call: suspend (Connection, Guest) -> T,
    ): T = withContext(Dispatchers.IO) {
        check(consent == revision()) { "The connection or target changed. Confirm again." }
        refusal(guest, confirmedHardware)?.let { throw GuestAgentFailure(it) }
        val expected = checkNotNull(confirmedHardware.identity) { "The guest identity is unknown. Use manual setup or removal." }
        check(expected.best(false) != null) { "The guest incarnation is unknown. Use manual setup or removal." }
        val captured = key(guest)
        observe(guest) {
            workspace.onGuest(guest) { connection, cached ->
                check(identity(cached) == identity(guest) && captured == key(guest)) { "The guest changed. Confirm again." }
                val fresh = readGuest(connection, guest)
                check(identity(fresh) == identity(guest)) { "The guest changed on the server. Refresh and confirm again." }
                val config = readHardware(connection, fresh)
                check(captured == key(guest)) { "The guest or connection changed. Confirm again." }
                val actual = checkNotNull(config.identity) { "The server did not provide a readable guest identity. Nothing was sent." }
                for (method in IdentityMethod.entries) {
                    expected.with(method)?.let { confirmed ->
                        check(actual.with(method) == confirmed) { "The guest incarnation changed. Refresh and confirm again." }
                    }
                }
                check(actual.template == expected.template) { "The guest changed on the server. Refresh and confirm again." }
                refusal(fresh, config)?.let { throw GuestAgentFailure(it) }
                check(captured == key(guest)) { "The guest or connection changed. Confirm again." }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                call(connection, fresh)
            }
        }
    }
}
