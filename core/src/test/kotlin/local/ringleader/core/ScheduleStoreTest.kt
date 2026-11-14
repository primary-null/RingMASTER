package local.ringleader.core

import java.nio.file.Files
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduleStoreTest {
    private val now = Instant.parse("2026-11-20T06:00:00Z")

    private val fixed = build()
    private fun document() = fixed
    private fun build(): ScheduleDocument {
        val a = ScheduleFixtures.plan(repeat = ScheduleRepeat(RepeatKind.Days, days = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)))
        val b = ScheduleFixtures.plan(repeat = ScheduleFixtures.once(LocalDate.of(2026, 12, 24), LocalTime.of(7, 0)), action = ScheduleAction.Shutdown, kind = "Container")
            .copy(lastHandledDue = now, invalidReason = "The guest's identity changed.")
        val run = ScheduleRun(java.util.UUID.randomUUID().toString(), a.id, 1, now, ScheduleAction.Start, "alpha (104)", RunPhase.Succeeded,
            "Stopped with exit status OK.", now, ScheduleFixtures.upid(), "node-a")
        return ScheduleDocument(listOf(a, b), listOf(BackgroundAccess(ScheduleFixtures.PROFILE_ID, profileBinding(ScheduleFixtures.profile()), now)), listOf(run))
    }

    @Test fun roundTripIsExactOwnerOnlyAndHoldsNoSecret() {
        val dir = Files.createTempDirectory("schedule-store")
        val store = ScheduleStore(dir.resolve("schedules.json"))
        assertEquals(ScheduleDocument(), store.load())
        val doc = document()
        store.save(doc)
        assertEquals(doc, ScheduleStore(dir.resolve("schedules.json")).load())
        val text = Files.readString(dir.resolve("schedules.json"))
        assertFalse(text.contains(ScheduleFixtures.SECRET))
        assertFalse(text.contains(ScheduleFixtures.UUID_A), "only identity digests are stored")
        assertFalse(text.contains(ScheduleFixtures.PIN), "only a binding digest of the pin is stored")
        assertEquals(0, Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".tmp") }.count() })
    }

    @Test fun strictDecodingRejectsUnknownDuplicateAndInvalidData() {
        val good = encodeSchedules(document())
        assertEquals(document(), decodeSchedules(good))
        for (bad in listOf(
            good.replace("\"runs\":", "\"runs2\":"),
            good.replaceFirst("\"name\":", "\"name\":\"x\",\"name\":"),
            good.replaceFirst("\"revision\":1", "\"revision\":\"1\""),
            good.replaceFirst("\"time\":\"07:00\"", "\"time\":\"7:00\""),
            good.replaceFirst("\"zone\":\"Europe/Berlin\"", "\"zone\":\"+01:00\""),
            good.replaceFirst("\"phase\":\"Succeeded\"", "\"phase\":\"Great\""),
            good.replaceFirst("\"enabled\":true", "\"enabled\":true,\"password\":\"x\""),
            "[]", "{", "",
        )) {
            val e = assertFailsWith<ScheduleStoreException> { decodeSchedules(bad) }
            assertTrue(e.unreadable, bad.take(80))
        }
        val future = assertFailsWith<ScheduleStoreException> { decodeSchedules(good.replace("\"version\":1", "\"version\":2")) }
        assertTrue(future.readOnly)
        assertFalse(future.unreadable)
    }

    @Test fun unreadableAndNewerFilesAreNeverOverwrittenAndExternalChangesBlockWrites() {
        val dir = Files.createTempDirectory("schedule-store")
        val file = dir.resolve("schedules.json")
        Files.writeString(file, "{not json")
        val store = ScheduleStore(file)
        assertFailsWith<ScheduleStoreException> { store.load() }
        assertFailsWith<ScheduleStoreException> { store.save(ScheduleDocument()) }
        assertEquals("{not json", Files.readString(file))
        Files.write(file, ByteArray(MAX_SCHEDULE_BYTES + 1) { ' '.code.toByte() })
        assertTrue(assertFailsWith<ScheduleStoreException> { ScheduleStore(file).load() }.unreadable)

        Files.writeString(file, encodeSchedules(document()))
        val a = ScheduleStore(file); a.load()
        val b = ScheduleStore(file); b.load()
        a.save(document().copy(runs = emptyList()))
        val changed = assertFailsWith<ScheduleStoreException> { b.save(document()) }
        assertTrue(changed.readOnly)
        assertEquals(document().copy(runs = emptyList()), ScheduleStore(file).load())
    }

    @Test fun failedAtomicReplaceKeepsThePreviousFile() {
        val dir = Files.createTempDirectory("schedule-store")
        val file = dir.resolve("schedules.json")
        ScheduleStore(file).apply { load(); save(document()) }
        val before = Files.readString(file)
        val store = ScheduleStore(file) { _, _ -> throw java.io.IOException("injected") }
        store.load()
        assertFailsWith<ScheduleStoreException> { store.save(ScheduleDocument()) }
        assertEquals(before, Files.readString(file))
        assertEquals(0, Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".tmp") }.count() })
    }
}
