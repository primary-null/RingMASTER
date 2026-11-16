package local.ringleader.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import local.ringleader.core.cidrText
import local.ringleader.core.createOwnerOnlyDirectories
import local.ringleader.core.grantOwnerOnly
import local.ringleader.core.parseCidr
import local.ringleader.core.profileFile

/**
 * The person's tick or untick per network on the Scan page, kept between runs.
 *
 * It is the same kind of store as the other preferences ([MotionPreferences]): a small properties file next to the profiles, written
 * atomically, owner-only, and left alone when a newer RingMASTER wrote it ([ConfigSchemas]). It holds one yes or no per network and
 * nothing else: no host name, no address of a server, no certificate. A network is its swept CIDR (`10.20.30.0/24`); a line that is
 * not a canonical private-looking CIDR is ignored when read. At most [MAX_ENTRIES] choices are kept; the oldest go first.
 *
 * A null [file] keeps the choices in memory only, for tests.
 */
class ScanChoices private constructor(private val file: Path?) {
    private class Entry(val on: Boolean, val seq: Long)

    private var entries: MutableMap<String, Entry> = load()

    /** The remembered choices: network to on (true) or off (false). */
    fun all(): Map<String, Boolean> = entries.mapValues { it.value.on }

    val any: Boolean get() = entries.isNotEmpty()

    fun put(id: String, on: Boolean) {
        val canonical = parseCidr(id)?.let { (n, p) -> cidrText(n, p) }
        if (canonical == null || canonical != id) return
        val next = (entries.values.maxOfOrNull { it.seq } ?: 0L) + 1
        entries[id] = Entry(on, next)
        while (entries.size > MAX_ENTRIES) entries.remove(entries.minByOrNull { it.value.seq }!!.key)
        save()
    }

    /** Back to the defaults, for every network. */
    fun reset() {
        entries.clear()
        save()
    }

    private fun load(): MutableMap<String, Entry> {
        val path = file ?: return linkedMapOf()
        if (Files.isSymbolicLink(path)) return linkedMapOf()
        val size = runCatching { Files.size(path) }.getOrDefault(0L)
        if (size > MAX_BYTES) return linkedMapOf()
        val properties = ConfigSchemas.properties(path)
        val result = linkedMapOf<String, Entry>()
        for (name in properties.stringPropertyNames().sorted()) {
            if (result.size >= MAX_ENTRIES || !name.startsWith(PREFIX)) continue
            val id = name.removePrefix(PREFIX)
            val parsed = parseCidr(id) ?: continue
            if (cidrText(parsed.first, parsed.second) != id) continue
            val value = properties.getProperty(name) ?: continue
            val on = when (value.substringBefore(':')) { "on" -> true; "off" -> false; else -> continue }
            result[id] = Entry(on, value.substringAfter(':', "").toLongOrNull() ?: 0L)
        }
        return result
    }

    private fun save() {
        val path = file ?: return
        if (!ConfigSchemas.writable(path) || Files.isSymbolicLink(path)) return
        runCatching {
            val directory = path.parent ?: return
            createOwnerOnlyDirectories(directory)
            val properties = Properties().apply {
                setProperty("schema", "1")
                this@ScanChoices.entries.forEach { (id, e) -> setProperty(PREFIX + id, (if (e.on) "on" else "off") + ":" + e.seq) }
            }
            val temporary = Files.createTempFile(directory, "scan-", ".tmp")
            try {
                Files.newBufferedWriter(temporary).use { properties.store(it, null) }
                grantOwnerOnly(temporary)
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally { Files.deleteIfExists(temporary) }
        }.onFailure { SaveStatus.report("Scan network choices", it) }
    }

    companion object {
        const val MAX_ENTRIES = 256
        const val MAX_BYTES = 64L * 1024
        private const val PREFIX = "net."

        /** The app's own file, next to the profiles. */
        fun stored(file: Path = profileFile().resolveSibling("scan.txt")): ScanChoices = ScanChoices(file)

        /** Nothing is written anywhere. */
        fun inMemory(): ScanChoices = ScanChoices(null)
    }
}
