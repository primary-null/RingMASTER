package local.ringleader.core

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnerOnlyTest {
    @Test fun returnsFalseOnPosixFileSystem() {
        val directory = tempDirectory()
        try {
            if (!directory.fileSystem.supportedFileAttributeViews().contains("posix")) return
            val file = directory.resolve("probe")
            Files.writeString(file, "data")
            assertFalse(restrictToCurrentUser(file))
        } finally { delete(directory) }
    }

    @Test fun setsOneAllowEntryForTheCurrentUserOnAnAclStore() {
        val directory = tempDirectory()
        try {
            if (directory.fileSystem.supportedFileAttributeViews().contains("posix")) return
            if (Files.getFileAttributeView(directory, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) == null) return
            val fresh = directory.resolve("fresh")
            Files.writeString(fresh, "fresh")
            assertTrue(restrictToCurrentUser(fresh))
            val entries = Files.getFileAttributeView(fresh, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)!!.acl
            assertEquals(1, entries.size)
            val entry = entries.single()
            assertEquals(AclEntryType.ALLOW, entry.type())
            assertEquals(AclEntryPermission.values().toSet(), entry.permissions())
        } finally { delete(directory) }
    }

    @Test fun repeatedRestrictionLeavesTheUserAbleToReadWriteAndDelete() {
        val directory = tempDirectory()
        try {
            if (directory.fileSystem.supportedFileAttributeViews().contains("posix")) return
            if (Files.getFileAttributeView(directory, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) == null) return
            val file = directory.resolve("preexisting")
            Files.writeString(file, "first")
            assertTrue(restrictToCurrentUser(file))
            assertTrue(restrictToCurrentUser(file))
            assertEquals("first", Files.readString(file))
            Files.writeString(file, "second")
            assertEquals("second", Files.readString(file))
            Files.delete(file)
            assertFalse(Files.exists(file))
        } finally { delete(directory) }
    }

    private fun tempDirectory() = Files.createTempDirectory("ringmaster-owner-")

    private fun delete(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}
