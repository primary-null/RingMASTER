package local.ringleader.core

import java.io.File
import java.net.InetAddress
import java.net.ProxySelector
import java.net.URI
import kotlin.system.exitProcess

/**
 * Shows that the two JVM-wide hooks load from the class path the way the app starts them. Run with the app's own runtime and jars,
 * for example in an unpacked install:
 *
 *     <app>/runtime/bin/java -cp "<every jar in <app>/app>" local.ringleader.core.NetworkSelfCheck [report-file]
 *
 * (a wildcard class path entry, the folder name followed by a star, selects all the jars of that folder). A jpackage launcher has no
 * console on Windows, so the optional [report-file] argument also receives every line.
 *
 * It runs [NetworkDefaults.install] as the app's `main` does, asks the JDK for `localhost` (answered by the hosts file) and asks the
 * proxy selector about two addresses without connecting. Nothing leaves this computer. Every line starts with `SELFCHECK|`; the exit
 * code is 0 when the name-lookup hook is the JVM's resolver (or the switch RINGMASTER_PLAIN_RESOLVER=1 says it must not be) and the
 * proxy policy is installed, 1 otherwise.
 */
object NetworkSelfCheck {
    @JvmStatic fun main(args: Array<String>) {
        val lines = ArrayList<String>()
        fun say(key: String, value: Any?) { val line = "SELFCHECK|$key=$value"; println(line); lines += line }
        NetworkDefaults.install()
        say("java", System.getProperty("java.version") + " " + System.getProperty("java.vm.name"))
        say("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"))
        say("classPathEntries", System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotBlank() }.size)
        say("hookClassFrom", runCatching { File(RingResolverProvider::class.java.protectionDomain.codeSource.location.toURI()).name }.getOrNull())
        say("localhost", InetAddress.getAllByName("localhost").joinToString(",") { it.hostAddress })
        val plainRequested = NameLookup.plainRequested()
        val status = NameLookup.status
        say("plainSwitch", plainRequested)
        say("resolver", status)
        say("resolverFailure", NameLookup.failure)
        val selector = ProxySelector.getDefault()
        say("proxySelector", selector.javaClass.name)
        say("useSystemProxies", System.getProperty("java.net.useSystemProxies"))
        say("proxy for a LAN name", selector.select(URI("https://nas.local:8006")))
        say("proxy for a loopback address", selector.select(URI("https://[::1]:8006")))
        val resolverOk = if (plainRequested) status == ResolverStatus.PlainBySwitch else status == ResolverStatus.Active
        val selectorOk = selector is LanProxySelector
        val problems = listOfNotNull(if (resolverOk) null else "resolver is $status", if (selectorOk) null else "proxy selector is ${selector.javaClass.name}")
        say("RESULT", if (problems.isEmpty()) "ok" else "FAILED: " + problems.joinToString("; "))
        args.firstOrNull()?.let { path -> runCatching { File(path).writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8) } }
        exitProcess(if (problems.isEmpty()) 0 else 1)
    }
}
