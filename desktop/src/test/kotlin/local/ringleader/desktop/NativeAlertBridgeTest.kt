package local.ringleader.desktop

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import local.ringleader.core.*
import kotlin.test.*

class NativeAlertBridgeTest {
    private class Fake : NativeNotifier {
        override val platform = "Test"
        val sent = CopyOnWriteArrayList<NativeNotification>()
        val withdrawn = CopyOnWriteArrayList<String>()
        var response = CompletableFuture.completedFuture<NativeDeliveryOutcome>(NativeDeliveryOutcome.Sent())
        var listener: ((NativeActivation) -> Unit)? = null
        override fun prepare() = CompletableFuture.completedFuture<NativeAvailability>(NativeAvailability.Ready("Test"))
        override fun send(notification: NativeNotification) = response.also { sent += notification }
        override fun withdraw(id: String) { withdrawn += id }
        override fun setActivationListener(listener: ((NativeActivation) -> Unit)?) { this.listener = listener }
        override fun close() {}
    }

    private suspend fun until(test: () -> Boolean) = withTimeout(5_000) { while (!test()) delay(10) }
    private fun event() = NotificationEvent(NotificationIdentity("watch", "scope", "resource", "hot"),
        NotificationCategory.WatchStress, NotificationSeverity.Warning, "Private guest name", "Private node name")

    @Test fun nativeFailureKeepsInboxAndActivationDoesNotReadIt() = runBlocking {
        val center = NotificationCenter(NotificationStore(Files.createTempDirectory("bridge").resolve("notifications.json"))).also { it.load() }
        val fake = Fake().also { it.response = CompletableFuture.completedFuture(NativeDeliveryOutcome.Failed("No service")) }
        val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val opened = CopyOnWriteArrayList<String>()
        val bridge = NativeAlertBridge(center, fake, io, opened::add)
        try {
            center.post(event()); until { fake.sent.size == 1 }
            val id = fake.sent.single().id
            fake.listener?.invoke(NativeActivation(id, "wrong"))
            assertTrue(opened.isEmpty())
            fake.listener?.invoke(NativeActivation(id, id))
            assertEquals(listOf(id), opened.toList())
            assertFalse(center.state.value.items.single().read)
            center.dismiss(id); until { id in fake.withdrawn }
            fake.listener?.invoke(NativeActivation(id, id))
            assertEquals(1, opened.size)
        } finally { bridge.close(); io.cancel() }
    }

    @Test fun privacyChangeWithdrawsAnInFlightIdentifyingNotification() = runBlocking {
        val center = NotificationCenter(NotificationStore(Files.createTempDirectory("bridge").resolve("notifications.json"))).also { it.load() }
        val fake = Fake().also { it.response = CompletableFuture() }
        val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bridge = NativeAlertBridge(center, fake, io) {}
        try {
            center.post(event()); until { fake.sent.size == 1 }
            val id = fake.sent.single().id
            center.setPreferences { it.copy(privacy = true) }
            fake.response.complete(NativeDeliveryOutcome.Sent())
            until { id in fake.withdrawn }
            assertEquals(1, center.state.value.items.size)
        } finally { bridge.close(); io.cancel() }
    }

    @Test fun backgroundObservationIsOptInAndPreservesManualPause() = runBlocking {
        val sessions = Sessions(this) { Workspace(this) }
        try {
            val workspace = sessions.state.value.active.workspace
            workspace.exploreDemo()
            configureAlertReads(sessions, true, false, true, false)
            assertFalse(workspace.state.value.live)
            configureAlertReads(sessions, true, true, true, false)
            assertTrue(workspace.state.value.live)
            workspace.setLive(false)
            configureAlertReads(sessions, true, true, true, false)
            assertFalse(workspace.state.value.live)
        } finally { sessions.close() }
    }
}
