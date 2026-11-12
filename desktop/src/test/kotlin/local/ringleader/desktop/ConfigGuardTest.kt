package local.ringleader.desktop

import java.nio.file.*
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.time.*
import java.time.temporal.ChronoUnit
import local.ringleader.core.ownerDirectoryPermissions
import local.ringleader.core.ownerFilePermissions
import kotlin.test.*

class ConfigGuardTest {
    @Test fun upgradeDowngradeAndFirstExistingRunBackupBeforeMarker() {
        val root=Files.createTempDirectory("guard")
        Files.writeString(root.resolve("profiles.json"),"original")
        val guard=ConfigGuard(root,"0.22.0")
        guard.startup()
        assertEquals("0.22.0",Files.readString(root.resolve("last-version")))
        val dirs=Files.list(guard.backups).use { it.toList() }; assertEquals(1,dirs.size)
        assertEquals("original",Files.readString(dirs.single().resolve("profiles.json")))
        guard.startup(); assertEquals(1,Files.list(guard.backups).use { it.count() }.toInt())
        ConfigGuard(root,"0.21.0").startup(); assertEquals(2,Files.list(guard.backups).use { it.count() }.toInt())
    }
    @Test fun retentionExcludesPluginsAndLargeBackgrounds() {
        val root=Files.createTempDirectory("guard-retention")
        Files.writeString(root.resolve("appearance.properties"),"theme=Dark")
        Files.createDirectories(root.resolve("plugins")); Files.writeString(root.resolve("plugins/tool"),"keep out")
        Files.createDirectories(root.resolve("backgrounds")); Files.newByteChannel(root.resolve("backgrounds/large"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use { it.position(20L*1024*1024); it.write(java.nio.ByteBuffer.wrap(byteArrayOf(1))) }
        for(i in 0..4) ConfigGuard(root,"1",Clock.fixed(Instant.parse("2026-11-10T00:00:00Z").plusSeconds(i.toLong()),ZoneOffset.UTC)).backupNow("test")
        val dirs=Files.list(root.resolve("backups")).use { it.toList() }; assertEquals(3,dirs.size)
        dirs.forEach { assertTrue(Files.exists(it.resolve("appearance.properties"))); assertFalse(Files.exists(it.resolve("plugins"))); assertFalse(Files.exists(it.resolve("backgrounds"))); assertFalse(Files.exists(it.resolve("backups"))) }
    }
    @Test fun smallBackgroundAndFailureDoNotBlockStartup() {
        val root=Files.createTempDirectory("guard-small"); Files.createDirectories(root.resolve("backgrounds")); Files.writeString(root.resolve("backgrounds/a"),"small")
        val g=ConfigGuard(root,"1"); val dest=g.backupNow("manual")!!; assertEquals("small",Files.readString(dest.resolve("backgrounds/a")))
        val failed=Files.createTempDirectory("guard-fail"); Files.writeString(failed.resolve("profiles.json"),"x"); Files.writeString(failed.resolve("backups"),"blocked")
        var reports=0; ConfigGuard(failed,"1",report={reports++}).startup(); assertTrue(reports>0); assertFalse(Files.exists(failed.resolve("last-version")))
    }
    @Test fun newerPropertiesDefaultsAndSessionLatch() {
        val root=Files.createTempDirectory("guard-schema")
        for(name in listOf("appearance.properties","window.properties","motion.txt")) {
            val file=root.resolve(name); val text="schema=2\nreduce=true\nmaximized=true\n"; Files.writeString(file,text)
            assertTrue(ConfigSchemas.properties(file).isEmpty); assertFalse(ConfigSchemas.writable(file)); assertEquals(text,Files.readString(file))
            SaveStatus.dismiss(); assertTrue(ConfigSchemas.properties(file).isEmpty); assertNull(SaveStatus.problem)
        }
        val motion=root.resolve("motion-new.txt"); Files.writeString(motion,"schema=2\nreduce=true\n")
        val m=MotionPreferences(motion); assertFalse(m.read()); m.write(true); assertEquals("schema=2\nreduce=true\n",Files.readString(motion))
    }
    @Test fun legacyPropertiesAndMotionGetSchemaOnWrite() {
        val file=Files.createTempDirectory("motion-old").resolve("motion.txt"); Files.writeString(file,"reduce")
        val p=MotionPreferences(file); assertTrue(p.read()); p.put("expanded",true)
        assertEquals("1",ConfigSchemas.properties(file).getProperty("schema"))
    }
    @Test fun corruptMotionIsNotReplaced() {
        val file=Files.createTempDirectory("motion-corrupt").resolve("motion.txt")
        val text="reduce=\\uXXXX"
        Files.writeString(file,text); val prefs=MotionPreferences(file)
        assertFalse(prefs.read()); prefs.write(true); assertEquals(text,Files.readString(file))
    }
    @Test fun motionWritersPreserveEachOthersKeys() {
        val file=Files.createTempDirectory("motion-writers").resolve("motion.txt")
        val a=MotionPreferences(file); val b=MotionPreferences(file)
        a.put("expanded",true); b.write(true)
        assertTrue(a.read()); assertTrue(b.option("expanded",false))
    }
    @Test fun newerPinsNeverWritten() {
        val file=Files.createTempDirectory("pins-new").resolve("pins.json"); val text="""{"version":2,"connections":{}}"""; Files.writeString(file,text)
        val pins=PinStore(file); assertTrue(pins.byConnection.isEmpty()); pins.unpin("demo","missing"); assertEquals(text,Files.readString(file))
    }
    @Test fun backupStampIsUtcEvenWhenTheClockZoneIsNot() {
        val root=Files.createTempDirectory("guard-utc")
        Files.writeString(root.resolve("profiles.json"),"x")
        val clock=Clock.fixed(Instant.parse("2026-11-11T15:04:05Z"), ZoneId.of("America/New_York"))
        val dest=ConfigGuard(root,"1",clock).backupNow("manual")!!
        assertTrue(dest.fileName.toString().startsWith("20261111-150405-from-manual"), dest.fileName.toString())
    }
    @Test fun retentionKeepsNewestStampNamesNotNewestMtime() {
        val root=Files.createTempDirectory("guard-names")
        Files.writeString(root.resolve("profiles.json"),"x")
        val backups=Files.createDirectories(root.resolve("backups"))
        val old=Files.createDirectories(backups.resolve("20201111-000000-from-old"))
        Files.createDirectories(backups.resolve("20261111-150405-from-a"))
        Files.createDirectories(backups.resolve("20261111-150406-from-b"))
        Files.createDirectories(backups.resolve("20261111-150407-from-c"))
        Files.setLastModifiedTime(old, FileTime.from(Instant.parse("2099-01-01T00:00:00Z")))
        ConfigGuard(root,"1",Clock.fixed(Instant.parse("2026-11-11T16:00:00Z"), ZoneOffset.UTC)).backupNow("new")
        val names=Files.list(backups).use { it.toList() }.map { it.fileName.toString() }.filter { !it.startsWith(".") }
        assertEquals(3, names.size)
        assertFalse(names.any { it.startsWith("20201111") })
        assertFalse(names.any { it.contains("150405-from-a") })
        assertTrue(names.any { it.startsWith("20261111-160000") })
    }
    @Test fun stalePendingFoldersAreRemovedAndFreshOnesStay() {
        val root=Files.createTempDirectory("guard-pending")
        Files.writeString(root.resolve("profiles.json"),"x")
        Files.writeString(root.resolve("last-version"),"1")
        val backups=Files.createDirectories(root.resolve("backups"))
        val clock=Clock.fixed(Instant.parse("2026-11-11T15:04:05Z"), ZoneOffset.UTC)
        val old=Files.createTempDirectory(backups,".pending-")
        val fresh=Files.createTempDirectory(backups,".pending-")
        val boundary=Files.createTempDirectory(backups,".pending-")
        Files.setLastModifiedTime(old, FileTime.from(clock.instant().minus(2, ChronoUnit.DAYS)))
        Files.setLastModifiedTime(fresh, FileTime.from(clock.instant().minus(1, ChronoUnit.HOURS)))
        Files.setLastModifiedTime(boundary, FileTime.from(clock.instant().minus(1, ChronoUnit.DAYS)))
        ConfigGuard(root,"1",clock).startup()
        assertFalse(Files.exists(old))
        assertTrue(Files.exists(fresh))
        assertTrue(Files.exists(boundary))
        assertEquals("1", Files.readString(root.resolve("last-version")).trim())
    }
    @Test fun failedPruneStillKeepsTheNewBackupAndWritesLastVersion() {
        val root=Files.createTempDirectory("guard-prune")
        Files.writeString(root.resolve("profiles.json"),"x")
        repeat(3) { i ->
            ConfigGuard(root,"1",Clock.fixed(Instant.parse("2026-11-11T15:04:05Z").plusSeconds(i.toLong()), ZoneOffset.UTC)).backupNow("old$i")
        }
        val oldest=Files.list(root.resolve("backups")).use { it.toList() }.filter { !it.fileName.toString().startsWith(".") }.minBy { it.fileName.toString() }
        val posix=root.fileSystem.supportedFileAttributeViews().contains("posix")
        // Root (the release workflow's containers) deletes through a read-only folder, so the failed prune cannot be staged there.
        if (posix) org.junit.jupiter.api.Assumptions.assumeTrue(readOnlyFolderBlocksDelete(), "A read-only folder does not stop deletes here (running as root?)")
        if (posix) Files.setPosixFilePermissions(oldest, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        else Files.setAttribute(oldest.resolve("profiles.json"), "dos:readonly", true)
        try {
            var warning: String? = null
            val next=ConfigGuard(root,"1",Clock.fixed(Instant.parse("2026-11-11T16:00:00Z"), ZoneOffset.UTC), report={ warning = it }).backupNow("new")
            assertNotNull(next)
            assertTrue(Files.isDirectory(next))
            assertEquals(BACKUP_PRUNE_WARNING, warning)
            assertNotEquals(BACKUP_FAILURE, warning)
            var startupNote: String? = null
            ConfigGuard(root,"2",Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC), report={ startupNote = it }).startup()
            assertEquals("2", Files.readString(root.resolve("last-version")).trim())
            assertNotEquals(BACKUP_FAILURE, startupNote)
        } finally {
            if (posix) Files.setPosixFilePermissions(oldest, ownerDirectoryPermissions())
            else runCatching { Files.setAttribute(oldest.resolve("profiles.json"), "dos:readonly", false) }
        }
    }
    /** True when a file inside an r-x folder cannot be deleted. Root and some file systems ignore that. Probes a throwaway folder. */
    private fun readOnlyFolderBlocksDelete(): Boolean {
        val probe=Files.createTempDirectory("guard-probe")
        val file=Files.writeString(probe.resolve("f"),"x")
        Files.setPosixFilePermissions(probe, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        return try { runCatching { Files.delete(file) }.isFailure } finally {
            Files.setPosixFilePermissions(probe, ownerDirectoryPermissions())
            Files.deleteIfExists(file)
            Files.deleteIfExists(probe)
        }
    }
    @Test fun backupCopiesAreOwnerOnlyOnPosix() {
        val root=Files.createTempDirectory("guard-mode")
        if (!root.fileSystem.supportedFileAttributeViews().contains("posix")) return
        Files.writeString(root.resolve("profiles.json"),"secret")
        ConfigGuard(root,"1").startup()
        assertEquals(ownerDirectoryPermissions(), Files.getPosixFilePermissions(root))
        assertEquals(ownerDirectoryPermissions(), Files.getPosixFilePermissions(root.resolve("backups")))
        val backup=Files.list(root.resolve("backups")).use { it.toList() }.single { !it.fileName.toString().startsWith(".") }
        assertEquals(ownerDirectoryPermissions(), Files.getPosixFilePermissions(backup))
        assertEquals(ownerFilePermissions(), Files.getPosixFilePermissions(backup.resolve("profiles.json")))
        assertEquals(ownerFilePermissions(), Files.getPosixFilePermissions(root.resolve("last-version")))
    }
}
