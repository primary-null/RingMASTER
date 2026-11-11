package local.ringleader.desktop

import local.ringleader.core.profileFile
import java.nio.file.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Properties
import kotlinx.serialization.json.*

/** Per-path session latch. Reading defaults does not grant permission to overwrite a newer file. */
internal object ConfigSchemas {
    private val blocked = mutableSetOf<Path>()
    @Synchronized fun newer(file: Path, schema: Int, supported: Int = 1): Boolean {
        val key = file.toAbsolutePath().normalize()
        if (key in blocked) return true
        if (schema <= supported) return false
        blocked.add(key)
        SaveStatus.notice("${file.fileName} was written by a newer RingMASTER and is left untouched.")
        return true
    }
    @Synchronized fun writable(file: Path): Boolean {
        if (file.toAbsolutePath().normalize() in blocked) return false
        val schema = runCatching {
            if (!Files.exists(file)) 1
            else if (file.fileName.toString().endsWith(".json")) Json.parseToJsonElement(Files.readString(file)).jsonObject["version"]?.jsonPrimitive?.intOrNull ?: 1
            else Properties().apply { Files.newBufferedReader(file).use { load(it) } }.getProperty("schema")?.toIntOrNull() ?: 1
        }.getOrDefault(1)
        return !newer(file, schema)
    }
    fun properties(file: Path): Properties = runCatching {
        val p = Properties().apply { Files.newBufferedReader(file).use { load(it) } }
        if (newer(file,p.getProperty("schema")?.toIntOrNull() ?: 1)) Properties() else p
    }.getOrDefault(Properties())
}

class ConfigGuard(private val config: Path = profileFile().parent, private val version: String = AppVersion.value,
    private val clock: Clock = Clock.systemDefaultZone(), private val report: (String) -> Unit = SaveStatus::notice) {
    companion object { fun backupNow(reason: String): Path? = ConfigGuard().backupNow(reason) }
    val backups: Path get() = config.resolve("backups")
    fun startup() {
        runCatching {
            val marker = config.resolve("last-version")
            val old = if (Files.isRegularFile(marker)) Files.readString(marker).trim() else "unknown"
            if (old == version) return
            val exists = Files.isDirectory(config) && Files.list(config).use { s -> s.anyMatch { it.fileName.toString() !in setOf("backups","plugins","last-version") } }
            if (exists && backupNow(old) == null) return
            Files.createDirectories(config)
            val temp = Files.createTempFile(config,"version-",".tmp")
            try { Files.writeString(temp,version); Files.move(temp,marker,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
            finally { Files.deleteIfExists(temp) }
        }.onFailure { report("Configuration backup could not be completed. RingMASTER will still start.") }
    }
    fun backupNow(reason: String): Path? = runCatching {
        Files.createDirectories(backups)
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(clock.zone).format(clock.instant())
        val suffix = reason.replace(Regex("[^A-Za-z0-9._-]"),"_").take(60).ifBlank { "unknown" }
        var target = backups.resolve("$stamp-from-$suffix")
        var n = 0
        while (Files.exists(target)) target = backups.resolve("$stamp-from-$suffix-${++n}")
        // Build privately; incomplete copies are never counted among retained backups.
        val staging = Files.createTempDirectory(backups,".pending-")
        try {
            val files = if (Files.isDirectory(config)) Files.walk(config).use { s -> s.filter { Files.isRegularFile(it,LinkOption.NOFOLLOW_LINKS) }
                .filter { config.relativize(it).getName(0).toString() !in setOf("backups","plugins") }.toList() } else emptyList()
            val backgrounds = files.filter { config.relativize(it).getName(0).toString() == "backgrounds" }
            val includeBackgrounds = backgrounds.sumOf { Files.size(it) } <= 20L * 1024 * 1024
            files.filter { includeBackgrounds || it !in backgrounds }.forEach {
                val dest = staging.resolve(config.relativize(it)); Files.createDirectories(dest.parent); Files.copy(it,dest)
            }
            Files.move(staging,target,StandardCopyOption.ATOMIC_MOVE)
        } finally { if (Files.exists(staging)) removeTree(staging) }
        Files.list(backups).use { s -> s.filter { Files.isDirectory(it,LinkOption.NOFOLLOW_LINKS) && !it.fileName.toString().startsWith('.') }
            .sorted(compareByDescending<Path> { Files.getLastModifiedTime(it).toMillis() }.thenByDescending { it.fileName.toString() }).toList() }.drop(3).forEach(::removeTree)
        target
    }.onFailure { report("Configuration backup could not be completed. RingMASTER will still start.") }.getOrNull()
    private fun removeTree(path: Path) { Files.walk(path).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } } }
}
