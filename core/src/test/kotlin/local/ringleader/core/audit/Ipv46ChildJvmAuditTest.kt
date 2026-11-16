package local.ringleader.core.audit

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * IPv4/IPv6 audit, evidence 3: behaviour that needs its own JVM, either because JVM flags decide it
 * (java.net.preferIPv6Addresses, https.proxyHost) or because a scripted name resolver must be installed
 * (java.net.spi.InetAddressResolverProvider, JDK 18+). The child is [AuditChild]; everything is loopback.
 * `defect...` tests assert the correct behaviour and fail today; `ok...` tests pin checked, correct behaviour.
 * Run: `./gradlew.bat :core:test --tests "local.ringleader.core.audit.Ipv46ChildJvmAuditTest" --console=plain -i`
 */
class Ipv46ChildJvmAuditTest {
    private fun millis(value: String?) = value?.substringBefore("ms")?.toLongOrNull() ?: Long.MAX_VALUE

    /**
     * The caller asks for a 300 ms bound. The name takes 2.5 s to resolve. `new InetSocketAddress(host, port)` resolves
     * inside its constructor, so the connect timeout never covers it, and a blocked lookup cannot be cancelled.
     * The bound of `connectPort` is now its own (see NameResolverJvmTest); the two clients below still wait for the hook's 5 s default
     * until their call sites give the lookup their bound (NameLookup.within), which is addressing part 2.
     */
    private fun slowLookupReturnedWithinTheCallersBound(key: String) {
        val r = ChildJvm.run("slow-dns", withResolver = true)
        println("CHILD|slow-dns\n$r")
        assertTrue(r.values.containsKey(key) && millis(r.values[key]) < 1500, "$key returned after ${r.values[key]} although the caller bounded it to 300 ms (the resolver needs ${AuditResolverProvider.SLOW_MILLIS} ms)")
    }

    @Ignore("audit finding #12: addressing part 2 (NetworkScan.kt probeTlsFingerprint must run its lookup inside NameLookup.within(timeout))")
    @Test fun defectCertificateProbeLookupIsNotCoveredByItsTimeout() = slowLookupReturnedWithinTheCallersBound("probeTlsFingerprint, 300 ms timeout")

    @Ignore("audit finding #12: addressing part 2 (PveClient.kt must run its connection inside NameLookup.within(connectTimeout))")
    @Test fun defectApiClientLookupIsNotCoveredByItsTimeouts() = slowLookupReturnedWithinTheCallersBound("PveClient.overview, 300 ms connect and read timeouts")

    /**
     * The app sets no networking flag (checked in the build files and packaging). If the environment sets this one
     * (JAVA_TOOL_OPTIONS, _JAVA_OPTIONS, a .cfg edit), every IPv6 literal stops working. Pinned as a characterization.
     */
    @Test fun characterizationPreferIpv4StackFlagMakesIpv6LiteralsUnusable() {
        val normal = ChildJvm.run("ipv4stack")
        val flagged = ChildJvm.run("ipv4stack", jvmArgs = listOf("-Djava.net.preferIPv4Stack=true"))
        println("CHILD|ipv4stack default"); println(normal)
        println("CHILD|ipv4stack -Djava.net.preferIPv4Stack=true"); println(flagged)
        assertTrue(normal.values.getValue("ServerSocket on ::1").contains("ok(bound)"), "sanity: IPv6 loopback works by default: ${normal.values}")
        assertTrue(!flagged.values.getValue("ServerSocket on ::1").contains("ok(bound)"), "with the flag IPv6 sockets are unavailable: ${flagged.values}")
    }

    /** The JVM keeps an answer for a while: nothing in the app caches a resolved address of its own. */
    @Test fun okResolvedAddressIsCachedByTheJvmOnlyAndForAWhile() {
        val r = ChildJvm.run("ttl", withResolver = true)
        println("CHILD|ttl\n$r")
        assertEquals("1", r.values["resolverCalls"], "two lookups 1.5 s apart reached the resolver once")
        assertEquals(r.values["first"], r.values["second"])
    }

    /** About 30 s, the HotSpot default when networkaddress.cache.ttl is unset. Takes up to 70 s, so it runs only when asked. */
    @Test fun okCachedAddressExpiresAboutThirtySecondsLater() {
        assumeTrue(System.getenv("RINGMASTER_AUDIT_SLOW") == "1", "set RINGMASTER_AUDIT_SLOW=1 to wait out the cache")
        val r = ChildJvm.run("ttl-expiry", withResolver = true, timeoutSeconds = 150)
        println("CHILD|ttl-expiry\n$r")
        val seconds = r.values["changedAfterSeconds"]?.toIntOrNull()
        assertTrue(seconds != null && seconds in 25..40, "changed after ${r.values["changedAfterSeconds"]} s")
    }

    private fun keytool(vararg args: String) {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
        val process = ProcessBuilder(listOf(java.nio.file.Path.of(System.getProperty("java.home"), "bin", executable).toString()) + args).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor() == 0) { "keytool ${args.toList()} failed: $output" }
    }

    /** A TLS server on ::1 whose certificate carries [san], reached by the three client paths with normal TLS (no pin). */
    private fun normalTlsToIpv6Literal(san: String): ChildJvm.Result {
        val dir = java.nio.file.Files.createTempDirectory("ringmaster-audit-names")
        try {
            val server = dir.resolve("server.p12"); val trust = dir.resolve("trust.p12"); val certificate = dir.resolve("server.cer")
            keytool("-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=fixture.invalid",
                "-ext", "san=$san", "-storetype", "PKCS12", "-keystore", server.toString(), "-storepass", "fixture-only", "-keypass", "fixture-only", "-noprompt")
            keytool("-exportcert", "-alias", "fixture", "-file", certificate.toString(), "-keystore", server.toString(), "-storetype", "PKCS12", "-storepass", "fixture-only", "-rfc")
            keytool("-importcert", "-alias", "fixture", "-file", certificate.toString(), "-keystore", trust.toString(), "-storetype", "PKCS12", "-storepass", "trust-only", "-noprompt")
            val store = java.security.KeyStore.getInstance("PKCS12").apply { java.nio.file.Files.newInputStream(server).use { load(it, "fixture-only".toCharArray()) } }
            val managers = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "fixture-only".toCharArray()) }
            val context = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            val https = com.sun.net.httpserver.HttpsServer.create(java.net.InetSocketAddress(java.net.InetAddress.getByName("::1"), 0), 0)
            https.httpsConfigurator = com.sun.net.httpserver.HttpsConfigurator(context)
            https.createContext("/") { exchange ->
                exchange.requestBody.readAllBytes()
                val body = """{"data":[{"type":"node","node":"fixture"}]}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            }
            https.start()
            try {
                return ChildJvm.run("tls-names", listOf(https.address.port.toString()), jvmArgs = listOf(
                    "-Djavax.net.ssl.trustStore=$trust", "-Djavax.net.ssl.trustStorePassword=trust-only", "-Djavax.net.ssl.trustStoreType=PKCS12"))
            } finally { https.stop(0) }
        } finally { dir.toFile().deleteRecursively() }
    }

    /** Normal TLS, certificate names the IPv6 literal: API client, storage socket and console client must all accept it. */
    @Test fun okNormalTlsMatchesAnIpv6LiteralToTheCertificateIpName() {
        val r = normalTlsToIpv6Literal("ip:0:0:0:0:0:0:0:1")
        println("CHILD|tls-names (certificate has IP ::1)\n$r")
        for (key in listOf("api PveClient", "storage socket (storageWire)", "console HttpClient")) assertTrue(r.values[key]?.contains("ok(") == true, "$key: ${r.values[key]}")
    }

    /** Normal TLS, certificate names only a DNS name: the same three paths must refuse the literal, none may skip the name check. */
    @Test fun okNormalTlsRefusesAnIpv6LiteralTheCertificateDoesNotName() {
        val r = normalTlsToIpv6Literal("dns:pve.example.test")
        println("CHILD|tls-names (certificate names only pve.example.test)\n$r")
        for (key in listOf("api PveClient", "storage socket (storageWire)", "console HttpClient")) {
            val value = r.values[key].orEmpty()
            assertTrue(value.contains("SSL", ignoreCase = true) || value.contains("subject alternative", ignoreCase = true) || value.contains("No name matching", ignoreCase = true), "$key must fail the name check, got: $value")
        }
    }

}
