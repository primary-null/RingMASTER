package local.ringleader.core

import java.io.OutputStreamWriter
import java.net.URI
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.*
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.time.*
import java.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

const val UPDATE_FEED = "https://github.com/primary-null/RingMASTER/releases/latest/download/ringmaster-update.json"
const val RELEASE_PAGE = "https://github.com/primary-null/RingMASTER/releases/latest"
const val MANIFEST_LIMIT = 256 * 1024
const val SIGNATURE_LIMIT = 1024
private val platforms = setOf(
    "windows-x64-msi", "debian-13-amd64-deb", "ubuntu-26.04-amd64-deb",
    "fedora-45-x86_64-rpm", "arch-x86_64-pkg",
)
private val platformId = Regex("^[a-z0-9][a-z0-9_]*(?:[.-][a-z0-9_]+)*$")
data class UpdateAsset(val platform: String, val url: String, val sha256: String, val size: Long)
data class UpdateManifest(val version: String, val published: Instant, val notes: String, val notesUrl: String?, val minVersion: String?, val assets: List<UpdateAsset>)
data class OfferedUpdate(val manifest: UpdateManifest, val asset: UpdateAsset)
fun updateHttps(value: String): URI = URI(value).also {
    require(it.scheme == "https" && !it.host.isNullOrBlank() && it.rawUserInfo == null && value.none(Char::isISOControl))
}
fun versionFields(value: String): List<java.math.BigInteger> {
    require(value.matches(Regex("[0-9]+(?:\\.[0-9]+)*")))
    return value.split('.').take(3).map { it.toBigInteger() }.let { it + List(3 - it.size) { java.math.BigInteger.ZERO } }
}
fun compareUpdateVersions(a: String, b: String): Int {
    val x = versionFields(a); val y = versionFields(b)
    return (0..2).firstNotNullOfOrNull { x[it].compareTo(y[it]).takeIf { n -> n != 0 } } ?: 0
}
private fun noControls(e: JsonElement) {
    when (e) {
        is JsonObject -> e.forEach { (k,v) -> require(k.none(Char::isISOControl)); noControls(v) }
        is JsonArray -> e.forEach(::noControls)
        is JsonPrimitive -> require(e.content.none(Char::isISOControl))
    }
}
fun parseUpdateManifest(bytes: ByteArray): UpdateManifest {
    require(bytes.size <= MANIFEST_LIMIT)
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    val root = Json.parseToJsonElement(text).jsonObject
    // Release notes may have line breaks (shown as plain text); nothing else may carry any control character.
    noControls(JsonObject(root - "notes"))
    fun str(key: String) = root[key]?.jsonPrimitive?.also { require(it.isString) }?.content ?: error("Missing $key")
    require(root["schema"]?.jsonPrimitive?.let { !it.isString && it.intOrNull == 1 } == true)
    require(str("channel") == "stable")
    val version = str("version").also(::versionFields)
    val published = Instant.parse(str("published"))
    val notes = str("notes").replace("\r\n", "\n").also { require(it.length <= 8000 && it.none { c -> c.isISOControl() && c != '\n' }) }
    val notesUrl = root["notesUrl"]?.let { str("notesUrl").also(::updateHttps) }
    val min = root["minVersion"]?.let { str("minVersion").also(::versionFields) }
    val seen = mutableSetOf<String>()
    val assets = root.getValue("assets").jsonArray.mapNotNull { e ->
        val a = e.jsonObject
        fun s(k: String) = a.getValue(k).jsonPrimitive.also { require(it.isString) }.content
        val id = s("platform").also { require(it.length in 1..64 && it.matches(platformId) && seen.add(it)) }
        val asset = UpdateAsset(id, s("url").also(::updateHttps),
            s("sha256").also { require(it.matches(Regex("[0-9a-fA-F]{64}"))) },
            a.getValue("size").jsonPrimitive.also { require(!it.isString) }.long.also { require(it in 1_048_576L..1_073_741_824L) })
        asset.takeIf { it.platform in platforms }
    }
    return UpdateManifest(version,published,notes,notesUrl,min,assets)
}
fun verifyUpdateSignature(bytes: ByteArray, signature: ByteArray, keys: List<String>): Boolean {
    if (bytes.size > MANIFEST_LIMIT || signature.size > SIGNATURE_LIMIT) return false
    return runCatching {
        val sig = Base64.getDecoder().decode(signature.toString(Charsets.US_ASCII).trim())
        keys.any { key -> runCatching {
            val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(key)))
            Signature.getInstance("Ed25519").run { initVerify(publicKey); update(bytes); verify(sig) }
        }.getOrDefault(false) }
    }.getOrDefault(false)
}
fun updatePlatform(os: String, osRelease: String, arch: String = System.getProperty("os.arch")): String? {
    val amd64 = arch.equals("amd64", ignoreCase = true) || arch.equals("x86_64", ignoreCase = true)
    if (!amd64) return null
    if (os.lowercase().startsWith("windows")) return "windows-x64-msi"
    if (!os.lowercase().contains("linux")) return null
    val p = osRelease.lineSequence().filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().trim('"', '\'') }
    return when (p["ID"]) {
        "debian" -> if (p["VERSION_ID"] == "13") "debian-13-amd64-deb" else null
        "ubuntu" -> if (p["VERSION_ID"] == "26.04") "ubuntu-26.04-amd64-deb" else null
        "fedora" -> if (p["VERSION_ID"] == "45") "fedora-45-x86_64-rpm" else null
        "arch" -> "arch-x86_64-pkg"
        else -> null
    }
}
data class UpdateResponse(val status: Int, val bytes: ByteArray = byteArrayOf(), val location: String? = null)
fun interface UpdateTransport { fun get(url: String, limit: Int, userAgent: String): UpdateResponse }
class HttpsUpdateTransport : UpdateTransport {
    override fun get(url: String, limit: Int, userAgent: String): UpdateResponse {
        val c = updateHttps(url).toURL().openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false; c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", userAgent)
        try {
            val status = c.responseCode
            if (status != 200) return UpdateResponse(status, location = c.getHeaderField("Location"))
            val bytes = c.inputStream.use { it.readNBytes(limit + 1) }
            require(bytes.size <= limit)
            return UpdateResponse(status, bytes)
        } finally { c.disconnect() }
    }
}
sealed interface UpdateState {
    data object Disabled : UpdateState
    data object NotConfigured : UpdateState
    data object NeedsConsent : UpdateState
    data class Idle(val lastChecked: Instant?) : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val message: String = "You are up to date.") : UpdateState
    data class Available(val update: OfferedUpdate) : UpdateState
    data class Skipped(val version: String) : UpdateState
    data class Failed(val reason: String, val releasePage: String? = null) : UpdateState
}
private class UpdateHttpStatus(val status: Int) : Exception()
private class UpdateFetchFailure(message: String) : Exception(message)
private const val UPDATE_CORRUPT = "updates.properties could not be read and is left untouched."
private const val UPDATE_NEWER = "updates.properties was written by a newer RingMASTER and is left untouched."
private const val UPDATE_UNVERIFIED = "The update feed could not be verified."
private const val UPDATE_UNREACHABLE = "The update feed could not be reached. Check the network and try again."
private const val UPDATE_INSECURE = "The update feed redirected to an address that is not HTTPS. This can happen on a captive portal."

/** Session latch also protects a newer file if it is replaced or removed after loading. */
class UpdatePreferences(val file: Path, private val report: (String) -> Unit = {}) {
    private var blocked = false
    private val props = readInitial()
    val consent get() = props.getProperty("consent")
    val automatic get() = props.getProperty("automatic", "true") == "true"
    val lastChecked get() = runCatching { Instant.parse(props.getProperty("lastChecked")) }.getOrNull()
    val skippedVersion get() = props.getProperty("skippedVersion")
    val feedPublished get() = runCatching { Instant.parse(props.getProperty("feedPublished")) }.getOrNull()
    private fun readInitial(): Properties {
        if (!Files.exists(file)) return Properties()
        val loaded = runCatching { Properties().apply { Files.newBufferedReader(file).use { load(it) } } }
        // A throw, an empty file, or a non-empty file with no keys (a truncated or comment-only write) is not a blank consent.
        if (loaded.isFailure || Files.size(file) == 0L || loaded.getOrNull()?.isEmpty == true) {
            blocked = true; report(UPDATE_CORRUPT); return Properties()
        }
        val it = loaded.getOrThrow()
        if ((it.getProperty("schema")?.toIntOrNull() ?: 1) > 1) {
            blocked = true; report(UPDATE_NEWER); return Properties()
        }
        return it
    }
    fun put(key: String, value: String) {
        props.setProperty(key,value)
        if (blocked) return
        if (Files.exists(file)) {
            val loaded = runCatching { Properties().apply { Files.newBufferedReader(file).use { load(it) } } }
            if (loaded.isFailure || Files.size(file) == 0L || loaded.getOrNull()?.isEmpty == true) {
                blocked = true; report(UPDATE_CORRUPT); return
            }
            if ((loaded.getOrThrow().getProperty("schema")?.toIntOrNull() ?: 1) > 1) {
                blocked = true; report(UPDATE_NEWER); return
            }
        }
        runCatching {
            props.setProperty("schema","1")
            val parent = file.parent ?: error("updates.properties needs a directory")
            createOwnerOnlyDirectories(parent)
            val temp = Files.createTempFile(parent,"updates-",".tmp")
            try {
                grantOwnerOnly(temp)
                FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
                    val writer = OutputStreamWriter(Channels.newOutputStream(channel), Charsets.UTF_8).buffered()
                    props.store(writer, null)
                    writer.flush()
                    channel.force(true)
                }
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                grantOwnerOnly(file)
            } finally { Files.deleteIfExists(temp) }
        }.onFailure { report("Update preferences could not be saved. Changes apply until RingMASTER closes.") }
    }
}
class UpdateChecker(val version: String, val preferences: UpdatePreferences, private val keys: List<String> = ReleaseKeys.publicKeys,
    private val transport: UpdateTransport = HttpsUpdateTransport(), private val clock: Clock = Clock.systemUTC(),
    private val platform: () -> String? = { updatePlatform(System.getProperty("os.name"), runCatching { Files.readString(Path.of("/etc/os-release")) }.getOrDefault("")) },
    private val disabled: Boolean = System.getenv("RINGMASTER_DISABLE_UPDATES") == "1",
    private val attemptTimeout: Long = 90_000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private fun resting(): UpdateState = when { disabled -> UpdateState.Disabled; keys.isEmpty() -> UpdateState.NotConfigured; preferences.consent == null -> UpdateState.NeedsConsent; else -> UpdateState.Idle(preferences.lastChecked) }
    private val mutableState = MutableStateFlow(resting())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    fun consent(yes: Boolean) { if (disabled) return; preferences.put("consent",if(yes) "yes" else "no"); mutableState.value = resting() }
    fun automatic(on: Boolean) { if (!disabled) preferences.put("automatic",on.toString()) }
    fun skip(version: String) { preferences.put("skippedVersion",version); mutableState.value = UpdateState.Skipped(version) }
    /** Forget the skipped version; the next check offers it again. */
    fun unskip() { preferences.put("skippedVersion",""); mutableState.value = resting() }
    private fun https(value: String): URI = try { updateHttps(value) } catch (_: IllegalArgumentException) { throw UpdateFetchFailure(UPDATE_INSECURE) }
    private fun fetch(url: String, limit: Int): UpdateResponse {
        var current = url
        repeat(6) {
            https(current)
            val r = transport.get(current,limit,"RingMASTER/$version")
            if (r.bytes.size > limit) throw UpdateFetchFailure(UPDATE_UNVERIFIED)
            if (r.status !in setOf(301,302,303,307,308)) return r
            val location = r.location ?: throw UpdateFetchFailure(UPDATE_UNVERIFIED)
            current = https(current).resolve(location).toString()
            https(current)
        }
        throw UpdateFetchFailure(UPDATE_UNVERIFIED)
    }
    private fun recordFailure(state: UpdateState.Failed, span: Trace.Span, error: Throwable) {
        preferences.put("lastChecked", clock.instant().toString())
        mutableState.value = state
        span.end(error = error)
    }
    suspend fun check() = mutex.withLock {
        if (disabled || keys.isEmpty() || preferences.consent != "yes") { mutableState.value = resting(); return@withLock }
        mutableState.value = UpdateState.Checking
        val span = Trace.begin(TraceSource.App,"Update check",mapOf("url" to UPDATE_FEED))
        try {
            var missingFeed = false
            // The deadline runs on ioDispatcher. A virtual clock around it would cancel the fetch before a real request returns.
            // withTimeout ignores that deadline when the body never suspends, and the feed read blocks, so check the job afterwards.
            val result = withContext(ioDispatcher) { withTimeout(attemptTimeout) {
                try {
                val m = fetch(UPDATE_FEED,MANIFEST_LIMIT)
                if (m.status == 404) {
                    missingFeed = true
                    return@withTimeout UpdateState.Failed(
                        "The update feed was not found. Its address may have changed."
                    )
                }
                if (m.status != 200) throw UpdateHttpStatus(m.status)
                val s = fetch("$UPDATE_FEED.sig",SIGNATURE_LIMIT)
                if (s.status != 200) throw UpdateHttpStatus(s.status)
                if (!verifyUpdateSignature(m.bytes,s.bytes,keys)) throw UpdateFetchFailure(UPDATE_UNVERIFIED)
                val manifest = parseUpdateManifest(m.bytes)
                val savedPublished = preferences.feedPublished
                if (savedPublished != null && manifest.published < savedPublished) {
                    return@withTimeout UpdateState.Failed(UPDATE_UNVERIFIED)
                }
                val clampedPublished = minOf(manifest.published, clock.instant())
                preferences.put("feedPublished", (if (savedPublished != null) maxOf(savedPublished, clampedPublished) else clampedPublished).toString())
                when {
                    compareUpdateVersions(manifest.version,version) < 0 -> UpdateState.Failed("The update feed is older than this version.", RELEASE_PAGE)
                    compareUpdateVersions(manifest.version,version) == 0 -> UpdateState.UpToDate()
                    manifest.minVersion != null && compareUpdateVersions(version,manifest.minVersion) < 0 -> UpdateState.Failed("An in-between update is needed before this version.", RELEASE_PAGE)
                    manifest.version == preferences.skippedVersion -> UpdateState.Skipped(manifest.version)
                    else -> platform()?.let { p -> manifest.assets.find { it.platform == p } }?.let { UpdateState.Available(OfferedUpdate(manifest,it)) }
                        ?: UpdateState.Failed("No build for this system.",RELEASE_PAGE)
                }
                } finally { ensureActive() }
            } }
            preferences.put("lastChecked",clock.instant().toString()); mutableState.value = result
            // A missing feed is not a successful read. A feed that answered, then had no suitable build, still read successfully.
            if (missingFeed) span.end(error=IllegalStateException("The update feed was not found.")) else span.end(status=200)
            Trace.emit(TraceSource.App,"update-result", result.javaClass.simpleName)
        } catch (e: TimeoutCancellationException) { recordFailure(UpdateState.Failed(UPDATE_UNREACHABLE), span, e) }
        catch (e: CancellationException) { mutableState.value = resting(); span.end(); throw e }
        // A network failure is not a trust failure: say which one it was, and do not retry every minute.
        catch (e: java.io.IOException) { recordFailure(UpdateState.Failed(UPDATE_UNREACHABLE), span, e) }
        catch (e: UpdateHttpStatus) { recordFailure(UpdateState.Failed("The update feed answered with HTTP ${e.status}."), span, e) }
        catch (e: UpdateFetchFailure) { recordFailure(UpdateState.Failed(e.message ?: UPDATE_UNVERIFIED), span, e) }
        catch (e: Exception) { recordFailure(UpdateState.Failed(UPDATE_UNVERIFIED), span, e) }
    }
    /** Clock and coroutine delay are independently injectable through Clock and the caller's scope. */
    fun schedule(scope: CoroutineScope): Job = scope.launch {
        delay(30_000)
        if (!disabled && preferences.consent == "yes" && preferences.automatic) check()
        while (isActive) {
            if (!disabled && preferences.consent == "yes" && preferences.automatic && (preferences.lastChecked == null || Duration.between(preferences.lastChecked,clock.instant()).toHours() >= 24)) check()
            delay(60_000)
        }
    }
}
