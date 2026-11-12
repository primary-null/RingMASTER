package local.ringleader.desktop

import local.ringleader.core.createOwnerOnlyDirectories
import local.ringleader.core.grantOwnerOnly
import local.ringleader.core.profileFile
import java.nio.file.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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

internal const val BACKUP_FAILURE = "Configuration backup could not be completed. RingMASTER will still start."
internal const val BACKUP_PRUNE_WARNING = "An old configuration backup could not be removed. The new backup is in place."

class ConfigGuard(private val config: Path = profileFile().parent, private val version: String = AppVersion.value,
    private val clock: Clock = Clock.systemDefaultZone(), private val report: (String) -> Unit = SaveStatus::notice) {
    companion object { fun backupNow(reason: String): Path? = ConfigGuard().backupNow(reason) }
    val backups: Path get() = config.resolve("backups")
    fun startup() {
        runCatching { createOwnerOnlyDirectories(config) }.onFailure { report(BACKUP_FAILURE); return }
        // A leftover staging folder is not a failed backup. Same-version startups still sweep.
        runCatching { sweepPending() }.onFailure { report(BACKUP_PRUNE_WARNING) }
        runCatching {
            val marker = config.resolve("last-version")
            val old = if (Files.isRegularFile(marker)) Files.readString(marker).trim() else "unknown"
            if (old == version) return
            val exists = Files.isDirectory(config) && Files.list(config).use { s -> s.anyMatch { it.fileName.toString() !in setOf("backups","plugins","last-version") } }
            if (exists && backupNow(old) == null) return
            val temp = Files.createTempFile(config,"version-",".tmp")
            try {
                grantOwnerOnly(temp)
                Files.writeString(temp,version)
                Files.move(temp,marker,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                grantOwnerOnly(marker)
            } finally { Files.deleteIfExists(temp) }
        }.onFailure { report(BACKUP_FAILURE) }
    }
    /** Copies the config aside. A later failure to delete an old copy still returns the new directory. */
    fun backupNow(reason: String): Path? {
        val target = runCatching { copyBackup(reason) }.getOrElse { report(BACKUP_FAILURE); return null }
        runCatching { prune() }.onFailure { report(BACKUP_PRUNE_WARNING) }
        runCatching { sweepPending() }.onFailure { report(BACKUP_PRUNE_WARNING) }
        return target
    }
    private fun copyBackup(reason: String): Path {
        createOwnerOnlyDirectories(backups)
        // Stamp in UTC so a clock in another zone cannot reorder the name sort.
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(clock.instant())
        val suffix = reason.replace(Regex("[^A-Za-z0-9._-]"),"_").take(60).ifBlank { "unknown" }
        var target = backups.resolve("$stamp-from-$suffix")
        var n = 0
        while (Files.exists(target)) target = backups.resolve("$stamp-from-$suffix-${++n}")
        // Build privately; incomplete copies are never counted among retained backups.
        val staging = Files.createTempDirectory(backups,".pending-")
        grantOwnerOnly(staging)
        try {
            val files = if (Files.isDirectory(config)) Files.walk(config).use { s -> s.filter { Files.isRegularFile(it,LinkOption.NOFOLLOW_LINKS) }
                .filter { config.relativize(it).getName(0).toString() !in setOf("backups","plugins") }.toList() } else emptyList()
            val backgrounds = files.filter { config.relativize(it).getName(0).toString() == "backgrounds" }
            val includeBackgrounds = backgrounds.sumOf { Files.size(it) } <= 20L * 1024 * 1024
            files.filter { includeBackgrounds || it !in backgrounds }.forEach {
                val dest = staging.resolve(config.relativize(it))
                if (Files.isSymbolicLink(dest.parent)) return@forEach
                createOwnerOnlyDirectories(dest.parent)
                Files.copy(it, dest)
                grantOwnerOnly(dest)
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
            grantOwnerOnly(target)
        } finally { if (Files.exists(staging)) removeTree(staging) }
        return target
    }
    private fun prune() {
        if (!Files.isDirectory(backups, LinkOption.NOFOLLOW_LINKS)) return
        Files.list(backups).use { s -> s.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !it.fileName.toString().startsWith('.') }
            .sorted(compareByDescending { it.fileName.toString() }).toList() }.drop(3).forEach(::removeTree)
    }
    /** Removes `.pending-*` directories whose modification time is strictly older than one day. */
    private fun sweepPending() {
        if (!Files.isDirectory(backups, LinkOption.NOFOLLOW_LINKS)) return
        val cutoff = clock.instant().minus(1, ChronoUnit.DAYS)
        val stale = Files.list(backups).use { s -> s.toList() }.filter {
            val name = it.fileName.toString()
            name.startsWith(".pending-") && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) &&
                Files.getLastModifiedTime(it, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)
        }
        stale.forEach(::removeTree)
    }
    private fun removeTree(path: Path) { Files.walk(path).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } } }
}
