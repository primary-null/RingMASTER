package local.ringleader.desktop

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import local.ringleader.core.NotificationCenter
import local.ringleader.core.NotificationSeverity

/** Native delivery is a projection of the inbox; a platform failure never removes the event. */
internal class NativeAlertBridge(
    private val center: NotificationCenter,
    private val notifier: NativeNotifier,
    private val scope: CoroutineScope,
    private val activate: (String) -> Unit,
) : AutoCloseable {
    private val offered = ConcurrentHashMap.newKeySet<String>()
    private val mutable = MutableStateFlow(NativeDeliveryView(false, true, "Checking desktop notification support."))
    val state = mutable.asStateFlow()
    @Volatile private var showcase = false
    private val jobs = mutableListOf<Job>()

    init {
        notifier.setActivationListener { click ->
            val id = click.activationToken
            if (id != null && id == click.notificationId && center.state.value.items.any { it.id == id }) activate(id)
        }
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            center.native.collect { queued ->
                val request = center.prepareNative(queued, showcase) ?: return@collect
                offered += request.id
                val payload = NativeNotification(request.id, request.title, request.body, when (request.severity) {
                    NotificationSeverity.Error -> NativeSeverity.Critical
                    NotificationSeverity.Warning -> NativeSeverity.Warning
                    else -> NativeSeverity.Info
                }, request.id)
                val outcome = runInterruptible(Dispatchers.IO) { notifier.send(payload).get() }
                mutable.value = when (outcome) {
                    is NativeDeliveryOutcome.Sent -> NativeDeliveryView(true, true)
                    else -> NativeDeliveryView(false, true, outcome.detail)
                }
                // A preference change may race a native call already in progress. Withdraw its result too.
                val current = center.prepareNative(queued, showcase)
                if (current == null || current.private != request.private) notifier.withdraw(request.id)
            }
        }
        jobs += scope.launch {
            var oldPrivacy: Boolean? = null
            center.state.filter { it.loaded }.map { it.preferences }.distinctUntilChanged().collect { prefs ->
                if (!prefs.master || !prefs.native || (oldPrivacy != null && oldPrivacy != prefs.privacy)) withdrawAll()
                oldPrivacy = prefs.privacy
                if (prefs.master && prefs.native) {
                    val availability = runInterruptible(Dispatchers.IO) { notifier.prepare().get() }
                    mutable.value = NativeDeliveryView(availability is NativeAvailability.Ready, true,
                        availability.detail.takeUnless { availability is NativeAvailability.Ready })
                }
            }
        }
        jobs += scope.launch {
            center.state.collect { state ->
                val retained = state.items.filter { !it.read && state.preferences.enabled(it.category) && !state.snoozed(it, java.time.Instant.now()) }.map { it.id }.toSet()
                offered.filter { it !in retained }.forEach { notifier.withdraw(it); offered.remove(it) }
            }
        }
    }

    fun setShowcase(value: Boolean) {
        if (value != showcase) { showcase = value; withdrawAll() }
    }

    /** Explicit user test: generic copy, no server data or incident is invented. */
    fun testDelivery() {
        val prefs = center.state.value.preferences
        if (!prefs.master || !prefs.native) return
        scope.launch {
            runInterruptible(Dispatchers.IO) { notifier.prepare().get() }
            val outcome = runInterruptible(Dispatchers.IO) {
                notifier.send(NativeNotification("ringmaster-delivery-test", "RingMASTER", "This is a test desktop notification.")).get()
            }
            mutable.value = when (outcome) {
                is NativeDeliveryOutcome.Sent -> NativeDeliveryView(true, true)
                else -> NativeDeliveryView(false, true, outcome.detail)
            }
        }
    }

    private fun withdrawAll() { offered.toList().forEach(notifier::withdraw) }

    override fun close() {
        jobs.forEach { it.cancel() }
        notifier.setActivationListener(null)
        notifier.close()
    }
}
