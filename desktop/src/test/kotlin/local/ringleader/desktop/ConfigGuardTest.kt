package local.ringleader.desktop

import java.nio.file.*
import java.time.*
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
}
