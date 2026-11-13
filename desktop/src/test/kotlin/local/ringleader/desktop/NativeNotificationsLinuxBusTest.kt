package local.ringleader.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Real GDBus round trip on Linux, fully isolated: the test starts its own dbus-daemon from a minimal config with
 * no service directories (so nothing can be auto-started, least of all the person's notification daemon), and a
 * fake org.freedesktop.Notifications server registered with GIO's introspection data, which makes GDBus reject a
 * Notify call whose arguments do not match the specification's signature. Skipped where there is no dbus-daemon
 * or GIO (Windows, minimal build runners).
 */
class NativeNotificationsLinuxBusTest {
    @Suppress("FunctionName")
    private interface ServerGio : Library {
        fun g_dbus_connection_new_for_address_sync(address: String, flags: Int, observer: Pointer?, cancellable: Pointer?, error: PointerByReference): Pointer?
        fun g_dbus_connection_call_sync(
            connection: Pointer, busName: String, objectPath: String, interfaceName: String, methodName: String,
            parameters: Pointer?, replyType: Pointer?, flags: Int, timeoutMsec: Int, cancellable: Pointer?, error: PointerByReference,
        ): Pointer?
        fun g_dbus_node_info_new_for_xml(xml: String, error: PointerByReference): Pointer?
        fun g_dbus_node_info_lookup_interface(info: Pointer, name: String): Pointer?
        fun g_dbus_connection_register_object(
            connection: Pointer, path: String, info: Pointer, vtable: VTable, userData: Pointer?, free: Pointer?, error: PointerByReference,
        ): Int
        fun g_dbus_method_invocation_return_value(invocation: Pointer, parameters: Pointer?)
        fun g_dbus_connection_emit_signal(
            connection: Pointer, destination: String?, path: String, iface: String, signal: String, parameters: Pointer?, error: PointerByReference,
        ): Boolean
    }

    @Suppress("FunctionName")
    private interface ServerGLib : Library {
        fun g_variant_new_string(utf8: ByteArray): Pointer
        fun g_variant_new_uint32(value: Int): Pointer
        fun g_variant_new_tuple(children: Array<Pointer>, n: Long): Pointer
        fun g_variant_new_array(childType: Pointer?, children: Array<Pointer>?, n: Long): Pointer
        fun g_variant_get_type_string(value: Pointer): String
        fun g_variant_get_child_value(value: Pointer, index: Long): Pointer
        fun g_variant_get_uint32(value: Pointer): Int
        fun g_variant_print(value: Pointer, typeAnnotate: Boolean): Pointer
        fun g_variant_unref(value: Pointer)
        fun g_free(memory: Pointer?)
        fun g_main_context_new(): Pointer
        fun g_main_context_push_thread_default(context: Pointer)
        fun g_main_context_iteration(context: Pointer, mayBlock: Boolean): Boolean
        fun g_main_context_wakeup(context: Pointer)
    }

    fun interface MethodCall : Callback {
        fun invoke(connection: Pointer?, sender: Pointer?, path: Pointer?, iface: Pointer?, method: Pointer?, parameters: Pointer?, invocation: Pointer?, userData: Pointer?)
    }

    /** GDBusInterfaceVTable: three function pointers and eight reserved pointers. */
    class VTable : Structure() {
        @JvmField var method_call: MethodCall? = null
        @JvmField var get_property: Pointer? = null
        @JvmField var set_property: Pointer? = null
        @JvmField var padding: Array<Pointer?> = arrayOfNulls(8)
        override fun getFieldOrder() = listOf("method_call", "get_property", "set_property", "padding")
    }

    private val introspection = """
        <node>
          <interface name="org.freedesktop.Notifications">
            <method name="GetCapabilities"><arg direction="out" type="as"/></method>
            <method name="Notify">
              <arg direction="in" type="s"/><arg direction="in" type="u"/><arg direction="in" type="s"/>
              <arg direction="in" type="s"/><arg direction="in" type="s"/><arg direction="in" type="as"/>
              <arg direction="in" type="a{sv}"/><arg direction="in" type="i"/><arg direction="out" type="u"/>
            </method>
            <method name="CloseNotification"><arg direction="in" type="u"/></method>
            <method name="GetServerInformation">
              <arg direction="out" type="s"/><arg direction="out" type="s"/><arg direction="out" type="s"/><arg direction="out" type="s"/>
            </method>
            <signal name="NotificationClosed"><arg type="u"/><arg type="u"/></signal>
            <signal name="ActionInvoked"><arg type="u"/><arg type="s"/></signal>
          </interface>
        </node>
    """.trimIndent()

    @Test fun notifyActivationAndCloseRoundTripOnAPrivateBus() {
        assumeTrue(System.getProperty("os.name").orEmpty().lowercase().contains("linux"), "Linux only")
        val daemon = System.getenv("PATH").orEmpty().split(':').map { File(it, "dbus-daemon") }.firstOrNull { it.canExecute() }
        assumeTrue(daemon != null, "no dbus-daemon")
        val gio = runCatching { Native.load("libgio-2.0.so.0", ServerGio::class.java) }.getOrNull()
        val glib = runCatching { Native.load("libglib-2.0.so.0", ServerGLib::class.java) }.getOrNull()
        assumeTrue(gio != null && glib != null, "no GLib/GIO")
        gio!!; glib!!

        val dir = Files.createTempDirectory("rm-notify-bus").toFile()
        val config = File(dir, "bus.conf")
        config.writeText(
            """
            <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN" "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
            <busconfig>
              <type>session</type>
              <listen>unix:dir=${dir.absolutePath}</listen>
              <auth>EXTERNAL</auth>
              <policy context="default"><allow send_destination="*" eavesdrop="true"/><allow eavesdrop="true"/><allow own="*"/></policy>
            </busconfig>
            """.trimIndent(),
        )
        val pb = ProcessBuilder(daemon!!.path, "--config-file=${config.absolutePath}", "--nofork", "--print-address")
        pb.environment().keys.retainAll(setOf("PATH", "HOME"))
        val process = pb.redirectError(ProcessBuilder.Redirect.DISCARD).start()
        var stopServer: (() -> Unit)? = null
        var client: GioNotificationBus? = null
        try {
            val address = process.inputStream.bufferedReader().readLine()?.trim()
            assertTrue(!address.isNullOrEmpty(), "dbus-daemon printed no address")

            fun str(s: String) = glib.g_variant_new_string(s.toByteArray(Charsets.UTF_8).copyOf(s.toByteArray().size + 1))
            fun print(v: Pointer): String { val p = glib.g_variant_print(v, true); try { return p.getString(0, "UTF-8") } finally { glib.g_free(p) } }

            client = GioNotificationBus.forAddress(address)
            assertNotNull(client, "client connection")
            // Nobody owns the name yet and the bus cannot start anything: Unsupported, not a hang or a crash.
            val missing = assertFailsWith<NotificationBusException> { client.capabilities() }
            assertTrue(missing.unsupported, missing.message)

            val err = PointerByReference()
            val server = gio.g_dbus_connection_new_for_address_sync(address, 9, null, null, err)
            assertNotNull(server, "server connection")
            val owner = gio.g_dbus_connection_call_sync(
                server, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName",
                glib.g_variant_new_tuple(arrayOf(str("org.freedesktop.Notifications"), glib.g_variant_new_uint32(4)), 2), null, 0, 3000, null, err,
            )
            assertNotNull(owner, "RequestName")
            glib.g_variant_unref(owner)

            val notifies = CopyOnWriteArrayList<String>()
            val closes = CopyOnWriteArrayList<Long>()
            val path = "/org/freedesktop/Notifications"
            val iface = "org.freedesktop.Notifications"
            val handler = MethodCall { conn, _, _, _, method, params, invocation, _ ->
                val name = method!!.getString(0)
                when (name) {
                    "GetCapabilities" -> gio.g_dbus_method_invocation_return_value(invocation!!, glib.g_variant_new_tuple(arrayOf(
                        glib.g_variant_new_array(null, arrayOf(str("actions"), str("body"), str("body-markup")), 3)), 1))
                    "GetServerInformation" -> gio.g_dbus_method_invocation_return_value(invocation!!, glib.g_variant_new_tuple(arrayOf(
                        str("FakeNotify"), str("test"), str("1"), str("1.3")), 4))
                    "Notify" -> {
                        notifies += glib.g_variant_get_type_string(params!!) + " " + print(params)
                        gio.g_dbus_method_invocation_return_value(invocation!!, glib.g_variant_new_tuple(arrayOf(glib.g_variant_new_uint32(7)), 1))
                        gio.g_dbus_connection_emit_signal(conn!!, null, path, iface, "ActionInvoked",
                            glib.g_variant_new_tuple(arrayOf(glib.g_variant_new_uint32(7), str("default")), 2), PointerByReference())
                    }
                    "CloseNotification" -> {
                        val id = glib.g_variant_get_child_value(params!!, 0)
                        closes += glib.g_variant_get_uint32(id).toLong()
                        glib.g_variant_unref(id)
                        gio.g_dbus_method_invocation_return_value(invocation!!, null)
                        gio.g_dbus_connection_emit_signal(conn!!, null, path, iface, "NotificationClosed",
                            glib.g_variant_new_tuple(arrayOf(glib.g_variant_new_uint32(7), glib.g_variant_new_uint32(3)), 2), PointerByReference())
                    }
                }
            }
            val vtable = VTable().apply { method_call = handler }
            val registered = CountDownLatch(1)
            val running = java.util.concurrent.atomic.AtomicBoolean(true)
            var context: Pointer? = null
            val loop = Thread({
                val ctx = glib.g_main_context_new()
                glib.g_main_context_push_thread_default(ctx)
                context = ctx
                val node = gio.g_dbus_node_info_new_for_xml(introspection, PointerByReference())!!
                val info = gio.g_dbus_node_info_lookup_interface(node, iface)!!
                val id = gio.g_dbus_connection_register_object(server, path, info, vtable, null, null, PointerByReference())
                if (id > 0) registered.countDown()
                while (running.get()) glib.g_main_context_iteration(ctx, true)
            }, "fake-notify-server").apply { isDaemon = true; start() }
            stopServer = { running.set(false); context?.let(glib::g_main_context_wakeup); loop.join(1000) }
            assertTrue(registered.await(3, TimeUnit.SECONDS), "fake server registered")

            val backend = FreedesktopBackend(NativeNotifierConfig(), client, desktopEntry = "ringmaster", appIcon = "")
            val activated = CopyOnWriteArrayList<Long>()
            val gotActivation = CountDownLatch(1)
            backend.onActivated { activated += it; gotActivation.countDown() }
            assertEquals(NativeAvailability.Ready("Notifications go to FakeNotify."), backend.availability())

            val tag = NativeNotificationText.tagFor("incident:cpu")
            val outcome = backend.deliver(PreparedNotification(11, "incident:cpu", tag, "CPU high", "<b>R&D</b>", NativeSeverity.Warning))
            assertIs<NativeDeliveryOutcome.Sent>(outcome)
            val sent = notifies.single()
            assertTrue(sent.startsWith("(susssasa{sv}i) "), sent)
            assertTrue(sent.contains("'&lt;b&gt;R&amp;D&lt;/b&gt;'"), sent)
            assertTrue(sent.contains("['default', 'Open']"), sent)
            assertTrue(sent.contains("'urgency': <byte 0x01>"), sent)
            assertTrue(sent.contains("'desktop-entry': <'ringmaster'>"), sent)
            assertTrue(sent.contains("-1"), sent)
            assertTrue(gotActivation.await(3, TimeUnit.SECONDS), "ActionInvoked reached the backend")
            assertEquals(listOf(11L), activated.toList())

            backend.withdraw(tag)
            assertEquals(listOf(7L), closes.toList())
        } finally {
            stopServer?.invoke()
            client?.close()
            process.destroy()
            process.waitFor(2, TimeUnit.SECONDS)
            dir.deleteRecursively()
        }
    }
}
