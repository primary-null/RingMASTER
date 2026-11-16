package local.ringleader.core

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Certificates for the trust tests, made with the JDK's keytool as the other TLS tests do. Every name is under
 * example.test and every server listens on loopback. A [Keys] keeps its PKCS12 file, so the same key can be issued a new
 * certificate ([Keys.reissue]) the way Proxmox renews its node certificate.
 */
internal object TrustFixtures {
    const val PASS = "fixture-only"
    private val keytool = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool").toString()

    fun keytool(vararg args: String) {
        val process = ProcessBuilder(listOf(keytool) + args).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { "keytool failed: $output" }
    }

    class Keys internal constructor(val dir: Path, val alias: String) : Closeable {
        val file: Path = dir.resolve("$alias.p12")
        fun store(): KeyStore = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(file).use { load(it, PASS.toCharArray()) } }
        fun certificate(): X509Certificate = store().getCertificate(alias) as X509Certificate
        fun pin(): String = sha256(certificate().encoded)
        fun spki(): String = sha256(certificate().publicKey.encoded)
        fun serverContext(): SSLContext {
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store(), PASS.toCharArray()) }
            return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        }
        /** A new self-signed certificate for the SAME key, as `pvecm updatecerts` or pveupdate issue one. */
        fun reissue(vararg extra: String) {
            keytool("-selfcert", "-alias", alias, "-keystore", file.toString(), "-storepass", PASS, "-keypass", PASS, "-validity", "30", *extra)
        }
        override fun close() { dir.toFile().deleteRecursively() }
    }

    /** One self-signed RSA leaf for [cn].example.test. [extra] goes to keytool, e.g. `-startdate -10d` or `-ext SAN=ip:127.0.0.1`. */
    fun selfSigned(cn: String = "pve", vararg extra: String): Keys {
        val keys = Keys(Files.createTempDirectory("ring-trust"), cn)
        keytool("-genkeypair", "-alias", cn, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=$cn.example.test", "-storetype", "PKCS12", "-keystore", keys.file.toString(),
            "-storepass", PASS, "-keypass", PASS, "-noprompt", *extra)
        return keys
    }

    /** A private CA and a leaf it signed for 127.0.0.1 (as a Proxmox cluster CA signs its nodes). The leaf's store holds the chain. */
    class Signed(val ca: X509Certificate, val leaf: Keys) : Closeable { override fun close() = leaf.close() }

    fun caSigned(san: String = "ip:127.0.0.1"): Signed {
        val dir = Files.createTempDirectory("ring-trust-ca")
        val ca = dir.resolve("ca.p12").toString()
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-validity", "5", "-dname", "CN=Fixture CA.example.test",
            "-ext", "bc:c", "-storetype", "PKCS12", "-keystore", ca, "-storepass", PASS, "-keypass", PASS, "-noprompt")
        val leaf = Keys(dir, "leaf")
        keytool("-genkeypair", "-alias", "leaf", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=pve.example.test",
            "-storetype", "PKCS12", "-keystore", leaf.file.toString(), "-storepass", PASS, "-keypass", PASS, "-noprompt")
        keytool("-certreq", "-alias", "leaf", "-keystore", leaf.file.toString(), "-storepass", PASS, "-file", dir.resolve("leaf.csr").toString())
        keytool("-gencert", "-alias", "ca", "-keystore", ca, "-storepass", PASS, "-infile", dir.resolve("leaf.csr").toString(),
            "-outfile", dir.resolve("leaf.cer").toString(), "-ext", "SAN=$san", "-validity", "2")
        keytool("-exportcert", "-alias", "ca", "-keystore", ca, "-storepass", PASS, "-file", dir.resolve("ca.cer").toString())
        keytool("-importcert", "-alias", "ca", "-file", dir.resolve("ca.cer").toString(), "-keystore", leaf.file.toString(), "-storepass", PASS, "-noprompt")
        keytool("-importcert", "-alias", "leaf", "-file", dir.resolve("leaf.cer").toString(), "-keystore", leaf.file.toString(), "-storepass", PASS, "-noprompt")
        val caCert = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(Files.newInputStream(dir.resolve("ca.cer"))) as X509Certificate
        return Signed(caCert, leaf)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun pem(certificate: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n" + java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded) + "\n-----END CERTIFICATE-----\n"
}

/** A loopback HTTPS server answering `{}` to everything with the context it is given. [requests] counts HTTP requests. */
internal class FixtureHttps(context: SSLContext, status: Int = 200, body: String = "{\"data\":[]}") : Closeable {
    val requests = AtomicInteger()
    private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        httpsConfigurator = HttpsConfigurator(context)
        createContext("/") { exchange ->
            requests.incrementAndGet()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val port get() = server.address.port
    val address get() = "https://127.0.0.1:$port"
    override fun close() = server.stop(0)
}
