package local.ringleader.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleModelTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val newYork = ZoneId.of("America/New_York")

    @Test fun repeatsMatchTheirDays() {
        val monday = LocalDate.of(2026, 11, 16)
        assertTrue(ScheduleRepeat(RepeatKind.Daily).matches(monday.plusDays(6)))
        assertTrue(ScheduleRepeat(RepeatKind.Weekdays).matches(monday))
        assertTrue(!ScheduleRepeat(RepeatKind.Weekdays).matches(monday.plusDays(5)))
        assertTrue(ScheduleRepeat(RepeatKind.Days, days = setOf(DayOfWeek.WEDNESDAY)).matches(monday.plusDays(2)))
        assertTrue(!ScheduleRepeat(RepeatKind.Days, days = setOf(DayOfWeek.WEDNESDAY)).matches(monday))
        assertTrue(ScheduleRepeat(RepeatKind.Once, date = monday).matches(monday))
        assertTrue(!ScheduleRepeat(RepeatKind.Once, date = monday).matches(monday.plusDays(7)))
    }

    @Test fun explicitTimeZoneDecidesTheInstant() {
        val day = LocalDate.of(2026, 11, 16)
        assertEquals(Instant.parse("2026-11-16T12:00:00Z"), occurrenceAt(day, LocalTime.of(7, 0), newYork))
        assertEquals(Instant.parse("2026-11-16T06:00:00Z"), occurrenceAt(day, LocalTime.of(7, 0), berlin))
        assertEquals(Instant.parse("2026-07-16T11:00:00Z"), occurrenceAt(LocalDate.of(2026, 7, 16), LocalTime.of(7, 0), newYork))
    }

    @Test fun springForwardGapRunsLaterByTheGapAndOnlyOnce() {
        // Berlin 2026-03-29: 02:00 CET jumps to 03:00 CEST. 02:30 does not exist and runs at 03:30 CEST (01:30Z).
        val gap = occurrencesBetween(ScheduleRepeat(RepeatKind.Daily), LocalTime.of(2, 30), berlin,
            Instant.parse("2026-03-28T12:00:00Z"), Instant.parse("2026-03-29T12:00:00Z"))
        assertEquals(listOf(Instant.parse("2026-03-29T01:30:00Z")), gap)
    }

    @Test fun fallBackOverlapRunsOnceAtTheEarlierOffset() {
        // Berlin 2026-10-25: 03:00 CEST goes back to 02:00 CET; 02:30 happens twice. Only the first (00:30Z) runs.
        val overlap = occurrencesBetween(ScheduleRepeat(RepeatKind.Daily), LocalTime.of(2, 30), berlin,
            Instant.parse("2026-10-24T12:00:00Z"), Instant.parse("2026-10-25T12:00:00Z"))
        assertEquals(listOf(Instant.parse("2026-10-25T00:30:00Z")), overlap)
        // Every local date yields exactly one instant across the change.
        val week = occurrencesBetween(ScheduleRepeat(RepeatKind.Daily), LocalTime.of(2, 30), berlin,
            Instant.parse("2026-10-21T12:00:00Z"), Instant.parse("2026-10-28T12:00:00Z"))
        assertEquals(7, week.size)
        assertEquals(7, week.map { it.atZone(berlin).toLocalDate() }.distinct().size)
    }

    @Test fun nextOccurrenceAndOnceSchedules() {
        val now = Instant.parse("2026-11-20T10:00:00Z") // Friday
        val weekdays = nextOccurrence(ScheduleRepeat(RepeatKind.Weekdays), LocalTime.of(7, 0), berlin, now)
        assertEquals(Instant.parse("2026-11-23T06:00:00Z"), weekdays) // Monday
        assertNull(nextOccurrence(ScheduleRepeat(RepeatKind.Once, date = LocalDate.of(2026, 11, 19)), LocalTime.of(7, 0), berlin, now))
        assertEquals(Instant.parse("2026-12-24T06:00:00Z"), nextOccurrence(ScheduleRepeat(RepeatKind.Once, date = LocalDate.of(2026, 12, 24)), LocalTime.of(7, 0), berlin, now))
    }

    @Test fun longRangesAreBounded() {
        val all = occurrencesBetween(ScheduleRepeat(RepeatKind.Daily), LocalTime.of(7, 0), berlin, Instant.parse("2000-01-01T00:00:00Z"), Instant.parse("2026-11-20T10:00:00Z"))
        assertTrue(all.size in 399..401, "clamped to about 400 days: ${all.size}")
    }

    @Test fun draftValidation() {
        val now = Instant.parse("2026-11-20T10:00:00Z")
        val good = ScheduleDraft("Lab start", ScheduleAction.Start, RepeatKind.Weekdays, emptySet(), "", "07:30", "Europe/Berlin")
        assertEquals(emptyMap(), scheduleDraftErrors(good, now))
        assertEquals(setOf("time"), scheduleDraftErrors(good.copy(time = "7.30"), now).keys)
        assertEquals(setOf("time"), scheduleDraftErrors(good.copy(time = "24:00"), now).keys)
        assertEquals(setOf("zone"), scheduleDraftErrors(good.copy(zone = "+02:00"), now).keys) // fixed offsets ignore DST
        assertEquals(setOf("zone"), scheduleDraftErrors(good.copy(zone = "Mars/Base"), now).keys)
        assertEquals(setOf("name"), scheduleDraftErrors(good.copy(name = " "), now).keys)
        assertEquals(setOf("days"), scheduleDraftErrors(good.copy(repeat = RepeatKind.Days), now).keys)
        assertEquals(setOf("date"), scheduleDraftErrors(good.copy(repeat = RepeatKind.Once, date = "2026-11-20", time = "10:59"), now).keys) // 09:59Z is past
        assertEquals(emptyMap(), scheduleDraftErrors(good.copy(repeat = RepeatKind.Once, date = "2026-11-20", time = "11:01"), now))
        assertEquals(setOf("date"), scheduleDraftErrors(good.copy(repeat = RepeatKind.Once, date = "20.11.2026"), now).keys)
    }

    @Test fun overlapWarningsOnlyForTheSameGuest() {
        val now = Instant.parse("2026-11-20T10:00:00Z")
        val a = ScheduleFixtures.plan(time = LocalTime.of(7, 0))
        val b = ScheduleFixtures.plan(time = LocalTime.of(7, 5), action = ScheduleAction.Shutdown)
        val c = ScheduleFixtures.plan(time = LocalTime.of(7, 5), vmid = 105)
        val d = ScheduleFixtures.plan(time = LocalTime.of(9, 0))
        assertEquals(listOf(b.id), scheduleOverlaps(a, listOf(a, b, c, d), now).map { it.id })
        assertEquals(emptyList(), scheduleOverlaps(a, listOf(a, b.copy(enabled = false)), now))
    }

    @Test fun bindingAndIdentityDigestsChangeWithTheirInputs() {
        val p = ScheduleFixtures.profile()
        assertTrue(profileBinding(p) != profileBinding(p.copy(sha256Pin = "ab".repeat(32))))
        assertTrue(profileBinding(p) != profileBinding(p.copy(tokenId = "other@pve!x")))
        assertTrue(profileBinding(p) != profileBinding(p.copy(origin = "https://other.example.test:8006")))
        val facts = GuestIdentityFacts("01234567-89ab-cdef-0123-456789abcdef", 1700000000, "VM|a||", null, false)
        assertEquals(IdentityMethod.SmbiosUuid, facts.best(false)!!.method)
        assertEquals(facts.best(false), facts.copy(uuid = facts.uuid!!.uppercase()).with(IdentityMethod.SmbiosUuid))
        assertNull(facts.copy(uuid = null, ctime = null).best(false))
        assertEquals(IdentityMethod.Fallback, facts.copy(uuid = null, ctime = null).best(true)!!.method)
        assertEquals(Duration.ofMinutes(15), SCHEDULE_GRACE)
    }
}
