package local.ringleader.desktop

import local.ringleader.core.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import kotlin.test.*

class SpiceConsoleTest {
    private val remote = "https://192.0.2.10:8006"
    private val vm = Guest(remote, 113, "fixture", "pve1", "VM", "running")
    private val live = WorkspaceState(connected = true, address = remote, overview = Overview(1, listOf(vm)))

    @Test fun consoleIsOfferedOnlyForARunningDirectVm() {
        assertNull(spiceRefusal(live, vm))
        assertEquals(SPICE_NOT_RUNNING, spiceRefusal(live, vm.copy(status = "stopped")))
        assertEquals(SPICE_NOT_RUNNING, spiceRefusal(live, vm.copy(template = true)))
        assertEquals(SPICE_NOT_VM, spiceRefusal(live, vm.copy(kind = "Container")))
        assertEquals(SPICE_DEMO, spiceRefusal(live.copy(demo = true), vm))
        assertNotNull(spiceRefusal(live.copy(kind = PDM_KIND), vm))
        assertNotNull(spiceRefusal(live.copy(stale = true), vm))
        assertNotNull(spiceRefusal(live.copy(connected = false), vm))
    }

    @Test fun ticketFileIsRestrictedBeforeTheSecretIsWritten() {
        val directory = Files.createTempDirectory("ringmaster-spice-test-")
        try {
            val file = directory.resolve("console.vv")
            SpiceViewer.createPrivateFile(file)
            assertTrue(Files.exists(file))
            assertEquals(0, Files.size(file))
            if (directory.fileSystem.supportedFileAttributeViews().contains("posix")) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file),
                )
            } else if (Files.getFileAttributeView(file, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) != null) {
                val entries = Files.getFileAttributeView(file, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)!!.acl
                assertEquals(1, entries.size)
                val entry = entries.single()
                assertEquals(AclEntryType.ALLOW, entry.type())
                assertEquals(AclEntryPermission.values().toSet(), entry.permissions())
                // The restriction must leave the current user able to read, write, and delete the ticket.
                Files.writeString(file, "ticket")
                assertEquals("ticket", Files.readString(file))
                Files.delete(file)
                assertFalse(Files.exists(file))
            }
        } finally { deleteTree(directory) }
    }

    @Test fun aViewerThatFailsIsReportedAndTheTicketFileIsRemoved() {
        val body = """{"data":{"type":"spice","proxy":"http://192.0.2.10:3128","host":"pvespiceproxy:x","password":"one-time","tls-port":61002,"title":"VM 113"}}"""
        val ticket = parseSpiceTicket(body, "192.0.2.10")
        fun spiceDirs(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { s -> s.filter { it.fileName.toString().startsWith("ringmaster-spice-") }.toList().toSet() }
        val before = spiceDirs()
        // The JVM launcher cannot run a .vv file, so it exits non-zero at once: a stand-in for a broken viewer.
        val java = File(System.getProperty("java.home"), if (System.getProperty("os.name").startsWith("Windows")) "bin/java.exe" else "bin/java")
        val error = assertFailsWith<IllegalStateException> { SpiceViewer.launch(ticket, java) }
        assertTrue(error.message!!.startsWith("remote-viewer exited"), error.message)
        assertFalse("one-time" in error.message!!)
        assertEquals(before, spiceDirs(), "no ticket file or directory is left behind")
    }

    private fun deleteTree(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}
