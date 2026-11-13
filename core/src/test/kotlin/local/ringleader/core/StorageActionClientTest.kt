package local.ringleader.core

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The storage view's two reads (`GET /storage` and a storage's `rrddata`) against a local HTTPS fixture with a pinned
 * self-signed certificate on 127.0.0.1. Bodies are handwritten with invented names and documentation addresses. Every call is
 * one GET at an exact path; every refusal happens before a request is sent.
 */
class StorageActionClientTest {
    private class Seen(val method: String, val target: String, val authorization: String?, val bodyBytes: Int, val contentType: String?, val length: String?)
    private class Reply(val status: Int = 200, val body: String = """{"data":[]}""", val location: String? = null)

    private object Tls {
        val pin: String
        val context: SSLContext
        init {
            val dir = Files.createTempDirectory("ringmaster-storage-tls")
            val key = dir.resolve("fixture.p12")
            val executable = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
            val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-dname", "CN=fixture.invalid", "-storetype", "PKCS12", "-keystore", key.toString(),
                "-storepass", "fixture-only", "-keypass", "fixture-only", "-noprompt").redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            check(process.waitFor() == 0)
            val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(key).use { load(it, "fixture-only".toCharArray()) } }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "fixture-only".toCharArray()) }
            context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            pin = MessageDigest.getInstance("SHA-256").digest(store.getCertificate("fixture").encoded).joinToString("") { "%02x".format(it) }
            Files.deleteIfExists(key)
            Files.deleteIfExists(dir)
        }
    }

    private fun withServer(reply: (Seen) -> Reply, block: (address: String, pin: String, seen: MutableList<Seen>) -> Unit) {
        val seen = java.util.Collections.synchronizedList(mutableListOf<Seen>())
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(Tls.context)
        server.createContext("/") { exchange ->
            val target = exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: "")
            val request = Seen(exchange.requestMethod, target, exchange.requestHeaders.getFirst("Authorization"), exchange.requestBody.readAllBytes().size, exchange.requestHeaders.getFirst("Content-Type"), exchange.requestHeaders.getFirst("Content-Length"))
            seen += request
            val answer = reply(request)
            val bytes = answer.body.toByteArray()
            answer.location?.let { exchange.responseHeaders.add("Location", it) }
            if(answer.status == 0) { exchange.close(); return@createContext }
            exchange.sendResponseHeaders(answer.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { block("https://127.0.0.1:${server.address.port}", Tls.pin, seen) } finally { server.stop(0) }
    }


    @Test fun uploadFixedLengthProgressAndNoReplayAcrossHttpOutcomes() {
        val mode=java.util.concurrent.atomic.AtomicInteger(200)
        withServer({ Reply(mode.get(), "{\"data\":\"UPID:worker-b:00000010:00000011:65ABCDEF:imgcopy::test@pve:\"}") }) { address,pin,seen ->
            val p=Files.createTempFile("storage-upload", ".iso")
            try {
                Files.newOutputStream(p).use { out -> repeat(80) { out.write(ByteArray(65536)) } }
                val request=StorageRequest.Upload(UploadSource.capture(p),"iso","a.iso")
                Connection(address,"test@pve!api","fixture",pin).use { c ->
                    val key=StorageKey(c.remoteKey,"node-a","local"); val client=PveClient(1000,1500)
                    val progress=mutableListOf<Long>()
                    val upid=client.submitStorage(c,key,request,{progress+=it})
                    assertTrue(upid!!.contains("worker-b")); assertEquals(Files.size(p),progress.last())
                    val got=seen.last(); assertEquals(got.bodyBytes.toString(),got.length); assertTrue(got.contentType!!.startsWith("multipart/form-data; boundary=")); assertTrue(got.bodyBytes > Files.size(p))
                    for(code in listOf(400,403,401,302,500,404,201,0)) {
                        mode.set(code); val count=seen.size
                        val e=runCatching { client.submitStorage(c,key,request) }.exceptionOrNull()
                        assertTrue(when(code) { 400,403 -> e is StorageRejected; 401 -> e is SessionExpiredException; else -> e is StorageUnclear }, "$code: ${e?.javaClass}")
                        assertEquals(count+1,seen.size)
                    }
                    val count=seen.size
                    val cancelled=UploadCancel().apply { cancel() }
                    assertFailsWith<java.util.concurrent.CancellationException> { client.submitStorage(c,key,request,cancel=cancelled) }; assertEquals(count,seen.size)
                    Files.write(p,byteArrayOf(1)); assertFailsWith<IllegalArgumentException> { client.submitStorage(c,key,request) }; assertEquals(count,seen.size)
                }
                Connection(address,"test@pve!api","fixture","00".repeat(32)).use { c ->
                    val before=seen.size
                    assertFailsWith<java.io.IOException> { PveClient(1000,1000).submitStorage(c,StorageKey(c.remoteKey,"node-a","local"),StorageRequest.Upload(UploadSource.capture(p),"iso","a.iso")) }
                    assertEquals(before,seen.size)
                }
            } finally { Files.deleteIfExists(p) }
        }
    }
    @Test fun passwordPost401NeverRenewsOrReplaysMutation() {
        withServer({ seen -> if(seen.target.endsWith("/access/ticket")) Reply(body="{\"data\":{\"ticket\":\"PVE:fixture:ticket\",\"CSRFPreventionToken\":\"fixture-csrf\",\"username\":\"fixture@pve\"}}") else Reply(401) }) { address,pin,seen ->
            Connection(address,"fixture@pve","test-only",pin).use { c ->
                val r=StorageRequest.UrlDownload("https://example.test/a?secret=yes","iso","a.iso")
                assertFailsWith<SessionExpiredException> { PveClient(1000,1500).submitStorage(c,StorageKey(c.remoteKey,"node-a","local"),r) }
                assertEquals(1,seen.count { it.target.endsWith("/access/ticket") }); assertEquals(1,seen.count { it.target.endsWith("/download-url") })
            }
        }
    }
    @Test fun cancellationClosesAConnectionWaitingForThePostBodyReply() {
        val received=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        withServer({ received.countDown(); release.await(5,java.util.concurrent.TimeUnit.SECONDS); Reply(body="{\"data\":null}") }) { address,pin,_ ->
            val p=Files.createTempFile("storage-wait-reply", ".iso"); Files.write(p,ByteArray(65536))
            val cancel=UploadCancel(); val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
            Connection(address,"test@pve!api","fixture",pin).use { c ->
                val thread=Thread { try { PveClient(1000,1500).submitStorage(c,StorageKey(c.remoteKey,"node-a","local"),StorageRequest.Upload(UploadSource.capture(p),"iso","a.iso"),cancel=cancel) } catch(t:Throwable) { failure.set(t) } }.apply { isDaemon=true; start() }
                try {
                    assertTrue(received.await(3,java.util.concurrent.TimeUnit.SECONDS))
                    val closer=Thread { cancel.cancel() }.apply { isDaemon=true; start() }
                    thread.join(2000)
                    val stopped = !thread.isAlive
                    release.countDown(); closer.join(2000); thread.join(2000)
                    assertTrue(stopped,"Cancellation must close the connection while waiting for the reply.")
                    assertTrue(failure.get() is StorageUnclear)
                } finally { release.countDown(); Files.deleteIfExists(p) }
            }
        }
    }
    @Test fun cancellingDuringOrAfterFileBytesIsUnknown() {
        withServer({ Reply(body="{\"data\":null}") }) { address,pin,_ ->
            val p=Files.createTempFile("storage-cancel", ".iso")
            try {
                Files.newOutputStream(p).use { out -> repeat(64) { out.write(ByteArray(65536)) } }
                Connection(address,"test@pve!api","fixture",pin).use { c ->
                    val key=StorageKey(c.remoteKey,"node-a","local"); val r=StorageRequest.Upload(UploadSource.capture(p),"iso","a.iso")
                    for(threshold in listOf(65536L, Files.size(p))) {
                        val cancel=UploadCancel()
                        assertFailsWith<StorageUnclear> { PveClient(1000,1500).submitStorage(c,key,r,{ if(it >= threshold) cancel.cancel() },cancel) }
                    }
                }
            } finally { Files.deleteIfExists(p) }
        }
    }
    @Test fun deleteHasNoBodyAndPutRequiresNullAndMetadataIsExplicitGet() {
        var body="{\"data\":\"UPID:node-a:00000010:00000011:65ABCDEF:imgdel:local:test@pve:\"}"
        withServer({ Reply(body=body) }) { address,pin,seen ->
            Connection(address,"test@pve!api","fixture",pin).use { c ->
                val client=PveClient(1000,1500); val key=StorageKey(c.remoteKey,"node-a","local")
                val row=StorageVolume("local:iso/a.iso","iso",null,1,null,null,null,null,null,false,null)
                client.submitStorage(c,key,StorageRequest.RemoveVolume(row))
                val del=seen.single(); assertEquals("DELETE",del.method); assertEquals(0,del.bodyBytes); assertEquals("0",del.length); assertFalse(del.target.contains("?")); assertTrue(del.target.endsWith("local%3Aiso%2Fa.iso"))
                body="{\"data\":null}"; assertEquals(null,client.submitStorage(c,key,StorageRequest.SetProtection(row.copy(content="backup",volid="local:backup/a"),true))); assertEquals("PUT",seen.last().method)
                body="{\"data\":{\"filename\":\"a.iso\",\"size\":12}}"
                assertEquals("a.iso",client.storageUrlMetadata(c,"node-a","https://example.test/a?secret=yes",true).fileName)
                assertEquals("GET",seen.last().method); assertTrue(seen.last().target.contains("url=https%3A"))
            }
        }
    }
    @Test fun failureAfterDeleteHeadersBeforeBodyAcquisitionIsUnknownAndNeverReplayed() {
        val arrived=java.util.concurrent.CountDownLatch(1)
        withServer({ arrived.countDown(); Reply(body="{\"data\":null}") }) { address,pin,seen ->
            Connection(address,"test@pve!api","fixture",pin).use { c ->
                val transport=ProxmoxTransport(1000,1500,storageAfterHeaders={
                    check(arrived.await(2,java.util.concurrent.TimeUnit.SECONDS))
                    throw java.io.IOException("Injected failure after complete DELETE headers")
                })
                assertFailsWith<StorageUnclear> { transport.storageExchange(c,"/api2/json/nodes/node-a/storage/local/content/local%3Aiso%2Fa.iso","DELETE") }
                assertEquals(1,seen.size)
                assertEquals("DELETE",seen.single().method)
                assertEquals("0",seen.single().length)
                assertEquals(0,seen.single().bodyBytes)
            }
        }
    }
    @Test fun untrustedTlsIsProvenPreSendAndSameSizeMutationDuringUploadIsUnknown() {
        withServer({ Reply(body="{\"data\":null}") }) { address,pin,seen ->
            Connection(address,"test@pve!api","fixture","").use { c ->
                val failure=assertFailsWith<java.io.IOException> { ProxmoxTransport(1000,1000).storageExchange(c,"/api2/json/nodes/node-a/storage/local/content/local%3Aiso%2Fa.iso","DELETE") }
                assertFalse(failure is StorageUnclear)
                assertEquals(0,seen.size)
            }
            val file=Files.createTempFile("storage-writer", ".iso")
            Files.write(file,ByteArray(131072))
            val source=UploadSource.capture(file)
            val modified=Files.getLastModifiedTime(file)
            try {
                Connection(address,"test@pve!api","fixture",pin).use { c ->
                    assertFailsWith<StorageUnclear> {
                        PveClient(1000,1500).submitStorage(c,StorageKey(c.remoteKey,"node-a","local"),StorageRequest.Upload(source,"iso","a.iso"),progress={ count ->
                            if(count==65536L) {
                                java.io.RandomAccessFile(file.toFile(),"rw").use { it.seek(100000); it.write(1) }
                                Files.setLastModifiedTime(file,java.nio.file.attribute.FileTime.fromMillis(modified.toMillis()+2000))
                            }
                        })
                    }
                }
            } finally {Files.deleteIfExists(file)}
        }
    }
}
