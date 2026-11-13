package local.ringleader.desktop

import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/*
 * Operating-system notifications (Windows app notifications, freedesktop notifications on Linux).
 *
 * This layer only delivers. The in-app notification center owns every event: it records the event first, then
 * may hand a copy here. A native failure, a closed notification or a dismissal never changes the in-app event.
 * Nothing here acknowledges or marks anything read; the only callback is [NativeActivation], when the person
 * selects a notification.
 *
 * Privacy: the payload [NativeNotification.id] and [NativeNotification.activationToken] never reach the operating
 * system. Windows gets a digest tag and a short handle ("n42"); Linux gets nothing but the text. Both map back
 * to the payload in memory. Title and body are shown as given, so the caller applies privacy mode before sending.
 */

/** How loud a notification is. Neither platform is ever asked to bypass Do not disturb or Focus. */
enum class NativeSeverity { Info, Warning, Critical }

/**
 * One notification to show.
 *
 * @property id the caller's stable key. Sending the same id again replaces the earlier notification where the
 *   platform supports it; [NativeNotifier.withdraw] removes it. Stays in this process.
 * @property activationToken opaque value returned in [NativeActivation]. Only `[A-Za-z0-9_-]`, at most 64
 *   characters, so URLs, UPIDs (they contain ':') and free text are rejected. It must not encode credentials or
 *   guest names; use an in-app event key. Stays in this process.
 */
class NativeNotification(
    val id: String,
    val title: String,
    val body: String,
    val severity: NativeSeverity = NativeSeverity.Info,
    val activationToken: String? = null,
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_ID) { "id must be 1..$MAX_ID characters" }
        require(title.isNotBlank()) { "title must not be blank" }
        require(activationToken == null || TOKEN.matches(activationToken)) { "activationToken must match ${TOKEN.pattern}" }
    }

    override fun toString(): String = "NativeNotification(severity=$severity)"

    companion object {
        const val MAX_ID = 200
        val TOKEN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

/**
 * What the platform did with one notification. Only [Sent] means the operating system's notification service
 * accepted it. It does not mean the person saw it: Focus Assist, Do not disturb or the notification daemon may
 * hold or hide it, and that is their decision.
 */
sealed interface NativeDeliveryOutcome {
    val detail: String

    /** Accepted by the OS notification service (Windows: Show succeeded and no failure was reported). */
    data class Sent(override val detail: String = "") : NativeDeliveryOutcome

    /** This system, session or build cannot show OS notifications (no daemon, no helper, not installed). */
    data class Unsupported(override val detail: String) : NativeDeliveryOutcome

    /** The person or a policy turned notifications off for RingMASTER. Never worked around. */
    data class Blocked(override val detail: String) : NativeDeliveryOutcome

    /** Tried and failed, timed out, or the queue was full. */
    data class Failed(override val detail: String) : NativeDeliveryOutcome
}

/** Whether OS notifications can work right now, without showing anything. */
sealed interface NativeAvailability {
    val detail: String
    data class Ready(override val detail: String) : NativeAvailability
    data class Unsupported(override val detail: String) : NativeAvailability
    data class Blocked(override val detail: String) : NativeAvailability
    data class Unknown(override val detail: String) : NativeAvailability
}

/**
 * The person selected a notification (body click or its default action). Delivered on a background thread;
 * switch to the UI thread before touching state. This is "open", never "acknowledge".
 */
data class NativeActivation(val notificationId: String, val activationToken: String?)

data class NativeNotifierConfig(
    val appName: String = "RingMASTER",
    /** AppUserModelID placed on RingMASTER's Start menu shortcut. Changing it orphans the earlier registration. */
    val windowsAppId: String = "RingMASTER.Desktop",
    /**
     * Allows the Windows helper to add [windowsAppId] to the installed RingMASTER Start menu shortcut when that
     * shortcut has no ID yet. Without it Windows has no identity to show RingMASTER's notifications under.
     * Only shortcuts that point at the installed launcher are touched, and a different existing ID is never replaced.
     */
    val registerWindowsShortcutIdentity: Boolean = true,
    /**
     * Linux only. Off: Critical is sent with normal urgency. On: critical urgency, which many daemons keep on
     * screen and some show through Do not disturb, so it stays the person's explicit choice.
     */
    val linuxCriticalUrgency: Boolean = false,
    val queueCapacity: Int = 16,
    val timeoutMillis: Long = 8_000,
)

/** Small, explicit surface for the parent integration. Every method returns quickly and never throws. */
interface NativeNotifier : AutoCloseable {
    /** Plain name for settings text, e.g. "Windows notifications". */
    val platform: String

    /**
     * Checks availability. On Windows this may add RingMASTER's ID to its Start menu shortcut when
     * [NativeNotifierConfig.registerWindowsShortcutIdentity] is on; call it once when OS notifications are turned on
     * or at start-up, not before every send. Never shows anything.
     */
    fun prepare(): CompletableFuture<NativeAvailability>

    /** Queues one notification. The future always completes, at the latest after [NativeNotifierConfig.timeoutMillis]. */
    fun send(notification: NativeNotification): CompletableFuture<NativeDeliveryOutcome>

    /** Removes the notification with this id from the OS (best effort), e.g. after it was handled in the app. */
    fun withdraw(id: String)

    /** One listener; null removes it. */
    fun setActivationListener(listener: ((NativeActivation) -> Unit)?)

    override fun close()
}

object NativeNotifications {
    /** The notifier for this operating system, or one that answers Unsupported everywhere else. */
    fun forCurrentPlatform(config: NativeNotifierConfig = NativeNotifierConfig()): NativeNotifier {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val backend: NativeNotificationBackend = when {
            os.startsWith("windows") -> WindowsToastBackend(config)
            os.contains("linux") -> FreedesktopBackend(config, GioNotificationBus.load())
            else -> UnsupportedBackend("This system has no supported notification service.")
        }
        return QueuedNativeNotifier(backend, config)
    }
}

/** A notification after cleaning, with the in-process handle the platform reports back. */
internal class PreparedNotification(
    val handle: Long,
    val id: String,
    /** Lowercase hex digest of [id]: safe to give the OS as a replace/withdraw key. */
    val tag: String,
    val title: String,
    val body: String,
    val severity: NativeSeverity,
)

/**
 * Blocking platform work. Runs only on the notifier's single worker thread, so implementations need no locking
 * for their own state except what their native callbacks touch. Every call must be bounded in time.
 */
internal interface NativeNotificationBackend : AutoCloseable {
    val platform: String
    fun availability(): NativeAvailability
    fun deliver(notification: PreparedNotification): NativeDeliveryOutcome
    fun withdraw(tag: String)
    /** The backend reports a selected notification by its handle. Called from any thread. */
    fun onActivated(callback: (Long) -> Unit)
    override fun close() {}
}

internal class UnsupportedBackend(private val reason: String) : NativeNotificationBackend {
    override val platform = "No system notifications"
    override fun availability() = NativeAvailability.Unsupported(reason)
    override fun deliver(notification: PreparedNotification) = NativeDeliveryOutcome.Unsupported(reason)
    override fun withdraw(tag: String) {}
    override fun onActivated(callback: (Long) -> Unit) {}
}

/** Text the platforms receive: no control characters, bounded, never empty title. */
internal object NativeNotificationText {
    const val MAX_TITLE = 120
    const val MAX_BODY = 600
    const val MAX_BODY_LINES = 8

    fun title(raw: String): String = bound(clean(raw).replace('\n', ' ').replace(Regex(" {2,}"), " ").trim(), MAX_TITLE)

    fun body(raw: String): String {
        val lines = clean(raw).split('\n').map { it.trimEnd() }
        val joined = lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        val limited = joined.split('\n').let { if (it.size > MAX_BODY_LINES) it.take(MAX_BODY_LINES).joinToString("\n") + "…" else joined }
        return bound(limited, MAX_BODY)
    }

    /** Line breaks become \n, tabs a space; other control characters, noncharacters and lone surrogates go. */
    fun clean(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\n' -> out.append(c)
                c == '\t' -> out.append(' ')
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    out.append(c).append(text[i + 1]); i++
                }
                Character.isSurrogate(c) -> {}
                Character.isISOControl(c) -> {}
                c == '￾' || c == '￿' || c in '﷐'..'﷯' -> {}
                // Bidirectional overrides and isolates could make shown text read differently from what was sent.
                c in '‪'..'‮' || c in '⁦'..'⁩' -> {}
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** At most [max] code points, ending in "…" when shortened. */
    fun bound(text: String, max: Int): String {
        if (text.codePointCount(0, text.length) <= max) return text
        val end = text.offsetByCodePoints(0, max - 1)
        return text.substring(0, end).trimEnd() + "…"
    }

    /** Freedesktop body markup is XML-based: escape the characters that would start markup or entities. */
    fun escapeMarkup(text: String): String = buildString(text.length + 16) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(c)
        }
    }

    fun tagFor(id: String): String =
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
}

/** Recent handles mapped back to payload id and token. Bounded; the oldest are forgotten first. */
internal class ActivationBook(private val capacity: Int = 256) {
    private val entries = object : LinkedHashMap<Long, NativeActivation>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, NativeActivation>?) = size > capacity
    }
    private val next = AtomicLong(1)

    @Synchronized
    fun register(notification: NativeNotification): Long {
        val handle = next.getAndIncrement()
        entries[handle] = NativeActivation(notification.id, notification.activationToken)
        return handle
    }

    @Synchronized
    fun lookup(handle: Long): NativeActivation? = entries[handle]

    @Synchronized
    fun size(): Int = entries.size
}

/**
 * Bounded, asynchronous front for a backend: one worker thread, at most [NativeNotifierConfig.queueCapacity]
 * waiting jobs, and a deadline per job. A job still waiting at its deadline is dropped, so a slow platform never
 * shows a stale notification later.
 */
internal class QueuedNativeNotifier(
    private val backend: NativeNotificationBackend,
    private val config: NativeNotifierConfig,
    private val clock: () -> Long = System::nanoTime,
) : NativeNotifier {
    private class Job(val deadline: Long, val run: () -> Unit, val expire: () -> Unit)

    private val queue = LinkedBlockingQueue<Job>(config.queueCapacity.coerceIn(1, 256))
    // Withdrawals must not compete with a full delivery queue (privacy changes can withdraw the whole inbox).
    private val withdrawals = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private val book = ActivationBook()
    private val closed = AtomicBoolean(false)
    @Volatile private var listener: ((NativeActivation) -> Unit)? = null
    private val worker = Thread({ work() }, "native-notify").apply { isDaemon = true; start() }

    override val platform: String get() = backend.platform

    init {
        backend.onActivated { handle ->
            val activation = book.lookup(handle) ?: return@onActivated
            runCatching { listener?.invoke(activation) }
        }
    }

    override fun prepare(): CompletableFuture<NativeAvailability> {
        val result = CompletableFuture<NativeAvailability>()
        val timedOut = NativeAvailability.Unknown("The notification service did not answer in time.")
        if (closed.get()) return CompletableFuture.completedFuture(NativeAvailability.Unknown("Notifications are closed."))
        val job = Job(deadline(), run = {
            result.complete(runCatching { backend.availability() }.getOrElse { NativeAvailability.Unknown(plainError(it)) })
        }, expire = { result.complete(timedOut) })
        if (!queue.offer(job)) return CompletableFuture.completedFuture(NativeAvailability.Unknown("Too many notification requests are waiting."))
        return result.completeOnTimeout(timedOut, config.timeoutMillis, TimeUnit.MILLISECONDS)
    }

    override fun send(notification: NativeNotification): CompletableFuture<NativeDeliveryOutcome> {
        if (closed.get()) return CompletableFuture.completedFuture(NativeDeliveryOutcome.Failed("Notifications are closed."))
        val prepared = PreparedNotification(
            handle = book.register(notification),
            id = notification.id,
            tag = NativeNotificationText.tagFor(notification.id),
            title = NativeNotificationText.title(notification.title).ifBlank { config.appName },
            body = NativeNotificationText.body(notification.body),
            severity = notification.severity,
        )
        val result = CompletableFuture<NativeDeliveryOutcome>()
        val timedOut = NativeDeliveryOutcome.Failed("The notification service did not answer in time.")
        val job = Job(deadline(), run = {
            result.complete(runCatching { backend.deliver(prepared) }.getOrElse { NativeDeliveryOutcome.Failed(plainError(it)) })
        }, expire = { result.complete(timedOut) })
        if (!queue.offer(job)) return CompletableFuture.completedFuture(NativeDeliveryOutcome.Failed("Too many notifications are waiting."))
        return result.completeOnTimeout(timedOut, config.timeoutMillis, TimeUnit.MILLISECONDS)
    }

    override fun withdraw(id: String) {
        if (closed.get() || id.isBlank()) return
        val tag = NativeNotificationText.tagFor(id)
        synchronized(withdrawals) {
            withdrawals += tag
            if (withdrawals.size > 256) withdrawals.remove(withdrawals.first())
        }
    }

    override fun setActivationListener(listener: ((NativeActivation) -> Unit)?) {
        this.listener = listener
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listener = null
        worker.interrupt()
        runCatching { backend.close() }
        generateSequence { queue.poll() }.forEach { it.expire() }
    }

    private fun deadline() = clock() + TimeUnit.MILLISECONDS.toNanos(config.timeoutMillis)

    private fun work() {
        while (!closed.get()) {
            val pending = synchronized(withdrawals) { withdrawals.toList().also { withdrawals.clear() } }
            pending.forEach { runCatching { backend.withdraw(it) } }
            val job = try { queue.poll(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
                ?: continue
            if (clock() - job.deadline > 0) job.expire() else job.run()
        }
        generateSequence { queue.poll() }.forEach { it.expire() }
    }

    private fun plainError(t: Throwable): String = (t.message ?: t.javaClass.simpleName).take(160)
}
