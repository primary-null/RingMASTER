package local.ringleader.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/*
 * Linux: org.freedesktop.Notifications (Desktop Notifications Specification 1.3) on the session bus, through GLib's
 * GDBus (libgio-2.0, present wherever GTK is) loaded with the JNA RingMASTER already ships. No D-Bus protocol code
 * of our own and no new dependency. The daemon owns presentation and Do not disturb; RingMASTER never asks for
 * critical urgency unless the person turned that on, and never sets resident/transient hints.
 */

/** Errors from the bus, sorted into what the caller reports. */
internal class NotificationBusException(val unsupported: Boolean, message: String) : Exception(message)

/** The calls this backend makes. Implemented by [GioNotificationBus]; tests use a fake. */
internal interface NotificationBus : AutoCloseable {
    /** GetCapabilities. Throws [NotificationBusException]. */
    fun capabilities(): List<String>

    /** GetServerInformation name, or null. */
    fun serverName(): String?

    /** Notify; returns the server's notification id (u32 as Long). Throws [NotificationBusException]. */
    fun notify(
        appName: String, replacesId: Long, appIcon: String, summary: String, body: String,
        actions: List<String>, urgency: Int, desktopEntry: String?,
    ): Long

    fun closeNotification(id: Long)

    /** Subscribes to ActionInvoked and NotificationClosed. False when signals cannot be received. */
    fun listen(onAction: (id: Long, key: String) -> Unit, onClosed: (id: Long, reason: Int) -> Unit): Boolean

    override fun close() {}
}

internal class FreedesktopBackend(
    private val config: NativeNotifierConfig,
    private val bus: NotificationBus?,
    private val desktopEntry: String? = detectDesktopEntry(),
    private val appIcon: String = detectAppIcon(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : NativeNotificationBackend {
    override val platform = "Desktop notifications"

    private var capabilities: List<String>? = null
    private var capabilitiesAt = 0L
    private var listening = false
    @Volatile private var activated: (Long) -> Unit = {}

    // Server id -> our handle, and payload tag -> server id (for replace and withdraw). Bounded; touched from the
    // worker thread and the signal thread.
    private val lock = Any()
    private val handles = bounded<Long, Long>()
    private val serverIds = bounded<String, Long>()

    override fun onActivated(callback: (Long) -> Unit) {
        activated = callback
    }

    override fun availability(): NativeAvailability {
        val bus = bus ?: return NativeAvailability.Unsupported(MISSING)
        return try {
            capabilities(refresh = true)
            val name = runCatching { bus.serverName() }.getOrNull()?.let(NativeNotificationText::title)?.takeIf { it.isNotBlank() }
            NativeAvailability.Ready(if (name != null) "Notifications go to $name." else "A notification service is running.")
        } catch (e: NotificationBusException) {
            if (e.unsupported) NativeAvailability.Unsupported(e.message.orEmpty()) else NativeAvailability.Unknown(e.message.orEmpty())
        }
    }

    override fun deliver(notification: PreparedNotification): NativeDeliveryOutcome {
        val bus = bus ?: return NativeDeliveryOutcome.Unsupported(MISSING)
        return try {
            val caps = capabilities(refresh = false)
            val canAct = "actions" in caps
            if (canAct && !listening) listening = bus.listen(::onAction, ::onClosed)
            val body = when {
                "body" !in caps -> ""
                "body-markup" in caps -> NativeNotificationText.escapeMarkup(notification.body)
                else -> notification.body
            }
            val replaces = synchronized(lock) { serverIds[notification.tag] } ?: 0L
            val id = bus.notify(
                appName = config.appName,
                replacesId = replaces,
                appIcon = appIcon,
                summary = notification.title,
                body = body,
                actions = if (canAct && listening) listOf("default", "Open") else emptyList(),
                urgency = urgencyFor(notification.severity, config),
                desktopEntry = desktopEntry,
            )
            synchronized(lock) {
                handles[id] = notification.handle
                serverIds[notification.tag] = id
            }
            NativeDeliveryOutcome.Sent("Handed to the notification service.")
        } catch (e: NotificationBusException) {
            if (e.unsupported) NativeDeliveryOutcome.Unsupported(e.message.orEmpty()) else NativeDeliveryOutcome.Failed(e.message.orEmpty())
        }
    }

    override fun withdraw(tag: String) {
        val id = synchronized(lock) { serverIds.remove(tag) } ?: return
        runCatching { bus?.closeNotification(id) }
    }

    override fun close() {
        runCatching { bus?.close() }
    }

    private fun capabilities(refresh: Boolean): List<String> {
        val cached = capabilities
        if (!refresh && cached != null && nowMillis() - capabilitiesAt < CAPS_MILLIS) return cached
        val fresh = bus!!.capabilities()
        capabilities = fresh
        capabilitiesAt = nowMillis()
        return fresh
    }

    private fun onAction(id: Long, key: String) {
        if (key != "default") return
        val handle = synchronized(lock) { handles[id] } ?: return
        activated(handle)
    }

    /** Closed by expiry, by the person or by us. Only the mapping goes; nothing is acknowledged. */
    @Suppress("UNUSED_PARAMETER")
    private fun onClosed(id: Long, reason: Int) {
        synchronized(lock) {
            handles.remove(id)
            serverIds.entries.removeIf { it.value == id }
        }
    }

    internal companion object {
        const val CAPS_MILLIS = 60_000L
        const val MISSING = "This session has no desktop notification service (D-Bus or GLib is missing)."

        /** Spec urgency: 0 low, 1 normal, 2 critical. */
        fun urgencyFor(severity: NativeSeverity, config: NativeNotifierConfig): Int = when (severity) {
            NativeSeverity.Info -> 0
            NativeSeverity.Warning -> 1
            NativeSeverity.Critical -> if (config.linuxCriticalUrgency) 2 else 1
        }

        private fun <K, V> bounded(capacity: Int = 256) = object : LinkedHashMap<K, V>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > capacity
        }

        /** The installed .desktop basename: jpackage .deb/.rpm write ringmaster-ringmaster, the Arch package ringmaster. */
        fun detectDesktopEntry(env: (String) -> String? = System::getenv, exists: (File) -> Boolean = File::isFile): String? {
            val home = env("HOME")
            val dataHome = env("XDG_DATA_HOME")?.takeIf { it.startsWith("/") } ?: home?.let { "$it/.local/share" }
            val dataDirs = (env("XDG_DATA_DIRS")?.takeIf { it.isNotBlank() } ?: "/usr/local/share:/usr/share").split(':').filter { it.startsWith("/") }
            val dirs = listOfNotNull(dataHome) + dataDirs
            return listOf("ringmaster", "ringmaster-ringmaster").firstOrNull { name ->
                dirs.any { exists(File(it, "applications/$name.desktop")) }
            }
        }

        /** file:// URI of the packaged icon next to the launcher (/opt/ringmaster/lib/ringmaster.png), or "". */
        fun detectAppIcon(launcher: File? = installedLauncher()): String {
            val icon = launcher?.parentFile?.parentFile?.let { File(it, "lib/ringmaster.png") }?.takeIf { it.isFile } ?: return ""
            return Path.of(icon.absolutePath).toUri().toString()
        }
    }
}

/**
 * GDBus through JNA. Strings cross as NUL-terminated UTF-8 byte arrays (valid UTF-8 by construction), every
 * GVariant built here is floating and consumed by its container or by the call, and every call has a timeout.
 * Signals are received on one daemon thread that owns its own GMainContext.
 */
internal class GioNotificationBus private constructor(
    private val gio: Gio,
    private val glib: GLib,
    private val connection: Pointer,
    /** True for a private connection made by [forAddress]; it is closed with this object. */
    private val owned: Boolean = false,
) : NotificationBus {
    @Suppress("FunctionName")
    internal interface Gio : Library {
        fun g_bus_get_sync(busType: Int, cancellable: Pointer?, error: PointerByReference): Pointer?
        fun g_dbus_connection_call_sync(
            connection: Pointer, busName: String, objectPath: String, interfaceName: String, methodName: String,
            parameters: Pointer?, replyType: Pointer?, flags: Int, timeoutMsec: Int, cancellable: Pointer?, error: PointerByReference,
        ): Pointer?
        fun g_dbus_connection_signal_subscribe(
            connection: Pointer, sender: String?, interfaceName: String?, member: String?, objectPath: String?, arg0: String?,
            flags: Int, callback: SignalCallback, userData: Pointer?, userDataFreeFunc: Pointer?,
        ): Int
        fun g_dbus_connection_signal_unsubscribe(connection: Pointer, subscriptionId: Int)
        fun g_dbus_error_get_remote_error(error: Pointer): Pointer?
        fun g_dbus_connection_new_for_address_sync(
            address: String, flags: Int, observer: Pointer?, cancellable: Pointer?, error: PointerByReference,
        ): Pointer?
        fun g_dbus_connection_close_sync(connection: Pointer, cancellable: Pointer?, error: PointerByReference): Boolean
    }

    @Suppress("FunctionName")
    internal interface GLib : Library {
        fun g_variant_new_string(utf8: ByteArray): Pointer?
        fun g_variant_new_uint32(value: Int): Pointer?
        fun g_variant_new_int32(value: Int): Pointer?
        fun g_variant_new_byte(value: Byte): Pointer?
        fun g_variant_new_variant(value: Pointer): Pointer?
        fun g_variant_new_dict_entry(key: Pointer, value: Pointer): Pointer?
        fun g_variant_new_array(childType: Pointer?, children: Array<Pointer>?, nChildren: Long): Pointer?
        fun g_variant_new_tuple(children: Array<Pointer>, nChildren: Long): Pointer?
        fun g_variant_get_type_string(value: Pointer): String
        fun g_variant_n_children(value: Pointer): Long
        fun g_variant_get_child_value(value: Pointer, index: Long): Pointer
        fun g_variant_get_uint32(value: Pointer): Int
        fun g_variant_get_string(value: Pointer, length: Pointer?): Pointer
        fun g_variant_unref(value: Pointer)
        fun g_error_free(error: Pointer)
        fun g_free(memory: Pointer?)
        fun g_main_context_new(): Pointer
        fun g_main_context_push_thread_default(context: Pointer)
        fun g_main_context_pop_thread_default(context: Pointer)
        fun g_main_context_iteration(context: Pointer, mayBlock: Boolean): Boolean
        fun g_main_context_wakeup(context: Pointer)
        fun g_main_context_unref(context: Pointer)
    }

    internal fun interface SignalCallback : Callback {
        fun invoke(connection: Pointer?, sender: Pointer?, path: Pointer?, iface: Pointer?, signal: Pointer?, parameters: Pointer?, userData: Pointer?)
    }

    private val gate = Any()
    @Volatile private var running = false
    @Volatile private var loopContext: Pointer? = null
    private var signalThread: Thread? = null
    // Held for the lifetime of the subscription; JNA would otherwise let the native trampoline be collected.
    private var callback: SignalCallback? = null
    private val stringType = Memory(2).apply { setByte(0, 's'.code.toByte()); setByte(1, 0) }

    override fun capabilities(): List<String> {
        val reply = call("GetCapabilities", null)
        try {
            requireType(reply, "(as)")
            val array = glib.g_variant_get_child_value(reply, 0)
            try {
                val n = glib.g_variant_n_children(array).coerceAtMost(64)
                return (0 until n).map { i ->
                    val item = glib.g_variant_get_child_value(array, i)
                    try { glib.g_variant_get_string(item, null).getString(0, "UTF-8").take(64) } finally { glib.g_variant_unref(item) }
                }
            } finally {
                glib.g_variant_unref(array)
            }
        } finally {
            glib.g_variant_unref(reply)
        }
    }

    override fun serverName(): String? {
        val reply = call("GetServerInformation", null)
        try {
            if (glib.g_variant_get_type_string(reply) != "(ssss)") return null
            val name = glib.g_variant_get_child_value(reply, 0)
            try { return glib.g_variant_get_string(name, null).getString(0, "UTF-8").take(80) } finally { glib.g_variant_unref(name) }
        } finally {
            glib.g_variant_unref(reply)
        }
    }

    override fun notify(
        appName: String, replacesId: Long, appIcon: String, summary: String, body: String,
        actions: List<String>, urgency: Int, desktopEntry: String?,
    ): Long {
        val hints = mutableListOf(entry("urgency", glib.g_variant_new_byte(urgency.toByte())))
        if (desktopEntry != null) hints += entry("desktop-entry", str(desktopEntry))
        val actionItems = actions.map { str(it) }.toTypedArray()
        val parameters = glib.g_variant_new_tuple(
            arrayOf(
                str(appName),
                glib.g_variant_new_uint32(replacesId.toInt()) ?: fail("uint32"),
                str(appIcon),
                str(summary),
                str(body),
                glib.g_variant_new_array(stringType, actionItems.takeIf { it.isNotEmpty() }, actionItems.size.toLong()) ?: fail("as"),
                glib.g_variant_new_array(null, hints.toTypedArray(), hints.size.toLong()) ?: fail("a{sv}"),
                glib.g_variant_new_int32(-1) ?: fail("int32"),
            ),
            8,
        ) ?: fail("tuple")
        val reply = call("Notify", parameters)
        try {
            requireType(reply, "(u)")
            val id = glib.g_variant_get_child_value(reply, 0)
            try { return glib.g_variant_get_uint32(id).toLong() and 0xFFFF_FFFFL } finally { glib.g_variant_unref(id) }
        } finally {
            glib.g_variant_unref(reply)
        }
    }

    override fun closeNotification(id: Long) {
        val parameters = glib.g_variant_new_tuple(arrayOf(glib.g_variant_new_uint32(id.toInt()) ?: fail("uint32")), 1) ?: fail("tuple")
        glib.g_variant_unref(call("CloseNotification", parameters))
    }

    override fun listen(onAction: (Long, String) -> Unit, onClosed: (Long, Int) -> Unit): Boolean = synchronized(gate) {
        if (running) return true
        val ready = CountDownLatch(1)
        var subscribed = false
        val cb = SignalCallback { _, _, _, _, signal, parameters, _ ->
            if (signal == null || parameters == null) return@SignalCallback
            runCatching {
                when (signal.getString(0, "UTF-8")) {
                    "ActionInvoked" -> if (glib.g_variant_get_type_string(parameters) == "(us)") {
                        val id = child(parameters, 0) { glib.g_variant_get_uint32(it).toLong() and 0xFFFF_FFFFL }
                        val key = child(parameters, 1) { glib.g_variant_get_string(it, null).getString(0, "UTF-8").take(64) }
                        onAction(id, key)
                    }
                    "NotificationClosed" -> if (glib.g_variant_get_type_string(parameters) == "(uu)") {
                        val id = child(parameters, 0) { glib.g_variant_get_uint32(it).toLong() and 0xFFFF_FFFFL }
                        val reason = child(parameters, 1) { glib.g_variant_get_uint32(it) }
                        onClosed(id, reason)
                    }
                }
            }
        }
        callback = cb
        running = true
        val thread = Thread({
            val context = glib.g_main_context_new()
            glib.g_main_context_push_thread_default(context)
            loopContext = context
            // Callbacks run in the thread-default context of the subscribing thread, i.e. on this thread.
            val ids = listOf("ActionInvoked", "NotificationClosed").map { member ->
                gio.g_dbus_connection_signal_subscribe(connection, SERVICE, INTERFACE, member, PATH, null, 0, cb, null, null)
            }
            subscribed = ids.all { it > 0 }
            ready.countDown()
            try {
                while (running) glib.g_main_context_iteration(context, true)
            } finally {
                ids.filter { it > 0 }.forEach { gio.g_dbus_connection_signal_unsubscribe(connection, it) }
                loopContext = null
                glib.g_main_context_pop_thread_default(context)
                glib.g_main_context_unref(context)
            }
        }, "native-notify-dbus")
        thread.isDaemon = true
        thread.start()
        signalThread = thread
        if (!ready.await(2, TimeUnit.SECONDS) || !subscribed) {
            running = false
            loopContext?.let(glib::g_main_context_wakeup)
            return false
        }
        true
    }

    override fun close() {
        synchronized(gate) {
            running = false
            loopContext?.let(glib::g_main_context_wakeup)
            signalThread?.join(1_000)
            signalThread = null
            if (owned) {
                val err = PointerByReference()
                gio.g_dbus_connection_close_sync(connection, null, err)
                err.value?.let(glib::g_error_free)
            }
        }
    }

    private inline fun <T> child(parent: Pointer, index: Long, read: (Pointer) -> T): T {
        val value = glib.g_variant_get_child_value(parent, index)
        try { return read(value) } finally { glib.g_variant_unref(value) }
    }

    private fun str(text: String): Pointer {
        val bytes = text.replace("\u0000", "").toByteArray(Charsets.UTF_8)
        return glib.g_variant_new_string(bytes.copyOf(bytes.size + 1)) ?: fail("string")
    }

    private fun entry(key: String, value: Pointer?): Pointer =
        glib.g_variant_new_dict_entry(str(key), glib.g_variant_new_variant(value ?: fail("hint")) ?: fail("variant")) ?: fail("entry")

    private fun fail(what: String): Nothing = throw NotificationBusException(false, "Could not build the notification ($what).")

    private fun requireType(reply: Pointer, type: String) {
        if (glib.g_variant_get_type_string(reply) != type) throw NotificationBusException(false, "The notification service gave an unexpected answer.")
    }

    private fun call(method: String, parameters: Pointer?): Pointer {
        val err = PointerByReference()
        val reply = gio.g_dbus_connection_call_sync(connection, SERVICE, PATH, INTERFACE, method, parameters, null, 0, CALL_MILLIS, null, err)
        val error = err.value
        if (error != null) {
            val remote = gio.g_dbus_error_get_remote_error(error)
            val remoteName = remote?.getString(0, "UTF-8").orEmpty()
            glib.g_free(remote)
            val message = runCatching { error.getPointer(8)?.getString(0, "UTF-8") }.getOrNull().orEmpty()
            glib.g_error_free(error)
            reply?.let(glib::g_variant_unref)
            throw classify(remoteName, message)
        }
        return reply ?: throw NotificationBusException(false, "The notification service did not answer.")
    }

    companion object {
        private const val SERVICE = "org.freedesktop.Notifications"
        private const val PATH = "/org/freedesktop/Notifications"
        private const val INTERFACE = "org.freedesktop.Notifications"
        private const val CALL_MILLIS = 3_000
        private const val G_BUS_TYPE_SESSION = 2

        internal fun classify(remoteName: String, message: String): NotificationBusException = when {
            remoteName.endsWith(".ServiceUnknown") || remoteName.endsWith(".NameHasNoOwner") || remoteName.endsWith(".UnknownMethod") ||
                remoteName.endsWith(".UnknownObject") || remoteName.endsWith(".UnknownInterface") ->
                NotificationBusException(true, "No notification service is running in this session.")
            remoteName.endsWith(".NoReply") || message.contains("timed out", ignoreCase = true) || message.contains("Timeout was reached") ->
                NotificationBusException(false, "The notification service did not answer in time.")
            else -> NotificationBusException(false, "The notification service refused the notification.")
        }

        /**
         * A private connection to the bus at [address]. Used by the isolated test that runs its own dbus-daemon, so no
         * test ever reaches the person's real notification service. Null when GLib/GIO is missing or it fails.
         */
        internal fun forAddress(address: String): GioNotificationBus? = try {
            val glib = loadFirst<GLib>("glib-2.0", "libglib-2.0.so.0")
            val gio = loadFirst<Gio>("gio-2.0", "libgio-2.0.so.0")
            val err = PointerByReference()
            val connection = gio.g_dbus_connection_new_for_address_sync(address, AUTH_CLIENT or MESSAGE_BUS, null, null, err)
            err.value?.let(glib::g_error_free)
            connection?.let { GioNotificationBus(gio, glib, it, owned = true) }
        } catch (_: UnsatisfiedLinkError) {
            null
        }

        private const val AUTH_CLIENT = 1
        private const val MESSAGE_BUS = 8

        /** Null when there is no session bus, or GLib/GIO cannot be loaded. Never starts a bus. */
        fun load(): GioNotificationBus? {
            if (!hasSessionBus()) return null
            return try {
                val glib = loadFirst<GLib>("glib-2.0", "libglib-2.0.so.0")
                val gio = loadFirst<Gio>("gio-2.0", "libgio-2.0.so.0")
                val err = PointerByReference()
                val connection = gio.g_bus_get_sync(G_BUS_TYPE_SESSION, null, err)
                err.value?.let(glib::g_error_free)
                connection?.let { GioNotificationBus(gio, glib, it) }
            } catch (_: UnsatisfiedLinkError) {
                null
            } catch (_: NoClassDefFoundError) {
                null
            }
        }

        private inline fun <reified T : Library> loadFirst(vararg names: String): T {
            return GnomeLibraries.load(T::class.java, *names)
        }

        /**
         * Only an existing session bus counts. Without this check GLib could try X11 autolaunch and start a new
         * dbus-daemon, which is not this app's business.
         */
        private fun hasSessionBus(): Boolean {
            if (!System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) return true
            val runtime = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.startsWith("/") } ?: return false
            return File(runtime, "bus").exists()
        }
    }
}
