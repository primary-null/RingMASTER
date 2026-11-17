package local.ringleader.core.guesthelper

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/*
 * Where the Engine's files come from. The release workflow builds one bundle per architecture and the app's build packs
 * them into the app's resources with the SHA256SUMS the workflow wrote. A build without them (a developer's) carries none:
 * then a developer may point at a bundle file, which is always shown as not a release file. Nothing is ever downloaded.
 */

/** One Engine bundle: `ringmaster-engine-<version>-linux-<arch>.tar.gz`, its SHA-256, and whether it came with this app. */
data class GuestHelperBundle(
    val arch: String,
    val version: String,
    val file: Path,
    val sha256: String,
    /** True for a bundle packed into this app by its build; false for a file a developer chose. */
    val packaged: Boolean,
) {
    val size: Long get() = Files.size(file)
}

private val BUNDLE_NAME = Regex("ringmaster-engine-([0-9]+(?:\\.[0-9]+){1,3})-linux-(x86_64|aarch64)\\.tar\\.gz")
const val GUEST_HELPER_NO_BUNDLE =
    "This build of RingMASTER does not carry the $GUEST_HELPER_NAME. Release builds do. A developer can choose a bundle file."

fun sha256Of(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * The bundles in [dir] (the app's resources folder `ringmaster-engine`), each checked against the SHA256SUMS beside it.
 * A bundle that is not listed there, or whose hash differs, is left out, so a damaged package offers nothing rather
 * than a wrong file.
 */
fun packagedGuestHelperBundles(dir: Path?): List<GuestHelperBundle> {
    if (dir == null || !Files.isDirectory(dir)) return emptyList()
    val sums = Files.list(dir).use { files -> files.filter { it.fileName.toString().endsWith("SHA256SUMS") }.toList() }
        .flatMap { readSums(it).entries }
        .associate { it.key to it.value }
    return Files.list(dir).use { files -> files.toList() }.mapNotNull { file ->
        val match = BUNDLE_NAME.matchEntire(file.fileName.toString()) ?: return@mapNotNull null
        val expected = sums[file.fileName.toString()] ?: return@mapNotNull null
        val actual = sha256Of(file)
        if (actual != expected) return@mapNotNull null
        GuestHelperBundle(match.groupValues[2], match.groupValues[1], file, actual, packaged = true)
    }.sortedBy { it.arch }
}

/** `<hash>  <name>` lines, as sha256sum writes them. */
internal fun readSums(file: Path): Map<String, String> = Files.readAllLines(file).mapNotNull { line ->
    val parts = line.trim().split(Regex("\\s+"), limit = 2)
    val hash = parts.getOrNull(0)?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    val name = parts.getOrNull(1)?.removePrefix("*")
    if (hash != null && name != null) name to hash else null
}.toMap()

/** A bundle file a developer chose. Its name must be a bundle's; its hash is computed here and shown, never trusted from a list. */
fun chosenGuestHelperBundle(file: Path): Result<GuestHelperBundle> {
    val match = BUNDLE_NAME.matchEntire(file.fileName.toString())
        ?: return Result.failure(IllegalArgumentException("Choose a file named ringmaster-engine-<version>-linux-<arch>.tar.gz."))
    return try {
        Result.success(GuestHelperBundle(match.groupValues[2], match.groupValues[1], file, sha256Of(file), packaged = false))
    } catch (e: IOException) {
        Result.failure(IllegalArgumentException("The bundle file could not be read.", e))
    }
}

/** The bundle for a guest whose `uname -m` is [machine], or null when none fits. */
fun bundleFor(bundles: List<GuestHelperBundle>, machine: String?): GuestHelperBundle? {
    val arch = when (machine?.trim()) {
        "x86_64", "amd64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        null, "" -> return bundles.firstOrNull { it.arch == "x86_64" } ?: bundles.firstOrNull()
        else -> return null
    }
    return bundles.firstOrNull { it.arch == arch }
}
