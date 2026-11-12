package local.ringleader.core

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal

/**
 * Restrict [file] to the current user on NTFS-like stores by replacing its ACL with one ALLOW entry granting
 * every [AclEntryPermission] to its owner. On a POSIX file system, or on a store with no [AclFileAttributeView],
 * returns false and changes nothing so the caller can rely on POSIX 0600 instead. The owner comes from
 * [Files.getOwner] with [LinkOption.NOFOLLOW_LINKS]; if that principal is a [GroupPrincipal] (for example, a
 * file owned by the Administrators group on Windows), the current `user.name` is looked up and used instead so
 * the ACL grants the human user rather than every group member. Underlying I/O failures propagate.
 */
fun restrictToCurrentUser(file: Path): Boolean {
    val fs = file.fileSystem
    if ("posix" in fs.supportedFileAttributeViews()) return false
    val view = Files.getFileAttributeView(file, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) ?: return false
    val fileOwner: UserPrincipal = Files.getOwner(file, LinkOption.NOFOLLOW_LINKS)
    val principal: UserPrincipal = if (fileOwner is GroupPrincipal) {
        fs.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
    } else fileOwner
    val entry = AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(principal)
        .setPermissions(AclEntryPermission.values().toSet())
        .build()
    view.setAcl(listOf(entry))
    return true
}

/** `rwx------`. Callers apply this to directories they create, never to a parent such as `~/.config`. */
fun ownerDirectoryPermissions(): Set<PosixFilePermission> = setOf(
    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
)

/** `rw-------`. */
fun ownerFilePermissions(): Set<PosixFilePermission> = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

/**
 * Makes [path] owner-only. Missing paths and symbolic links are left alone, and a link is not followed.
 * POSIX gets 0700 for a directory and 0600 for a file. Other stores use [restrictToCurrentUser].
 */
fun grantOwnerOnly(path: Path) {
    if (Files.isSymbolicLink(path) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    if ("posix" in path.fileSystem.supportedFileAttributeViews()) {
        val perms = if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) ownerDirectoryPermissions() else ownerFilePermissions()
        Files.setPosixFilePermissions(path, perms)
    } else restrictToCurrentUser(path)
}

/**
 * Creates [directory] if needed and restricts that directory to the current user.
 * A symbolic link at [directory] is not followed and nothing is created through it.
 * Parent directories that have to be created keep the process umask; only [directory] itself is tightened.
 */
fun createOwnerOnlyDirectories(directory: Path) {
    if (Files.isSymbolicLink(directory)) return
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(directory)
    grantOwnerOnly(directory)
}
