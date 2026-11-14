package local.ringleader.core

import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Calendar scheduler model. Only graceful guest Start and Shut down are schedulable in this slice;
 * hard stop, reset, node power, rollback and job chains are deliberately absent.
 */
enum class ScheduleAction(val power: PowerAction, val title: String) {
    Start(PowerAction.Start, "Start"),
    Shutdown(PowerAction.Shutdown, "Shut down");
}

enum class RepeatKind { Once, Daily, Weekdays, Days }

/** [date] is used only by [RepeatKind.Once]; [days] only by [RepeatKind.Days]. */
data class ScheduleRepeat(val kind: RepeatKind, val days: Set<DayOfWeek> = emptySet(), val date: LocalDate? = null) {
    fun matches(day: LocalDate): Boolean = when (kind) {
        RepeatKind.Once -> day == date
        RepeatKind.Daily -> true
        RepeatKind.Weekdays -> day.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        RepeatKind.Days -> day.dayOfWeek in days
    }
}

/** How the guest was recognised when the schedule was authorized. Only a digest of the evidence is kept. */
enum class IdentityMethod(val label: String) {
    SmbiosUuid("SMBIOS UUID"),
    /** QEMU `meta` ctime. Weaker than the UUID: a clone copies it (while getting a fresh SMBIOS UUID). */
    CreationTime("creation time"),
    /** Name, OS type and root disk. Weak (a recreated guest with the same settings matches); needs explicit consent. */
    Fallback("name, OS type and root disk (weak)");
}

data class GuestIdentity(val method: IdentityMethod, val digest: String) {
    init { require(digest.matches(sha256PinPattern)) { "The guest identity is not usable." } }
}

/** Evidence read from a guest's configuration. Values never leave memory except as [GuestIdentity] digests. */
data class GuestIdentityFacts(val uuid: String?, val ctime: Long?, val fallback: String, val lock: String?, val template: Boolean) {
    /** Strongest available identity. Null when only the weak fallback exists and it was not allowed. */
    fun best(allowFallback: Boolean): GuestIdentity? = when {
        uuid != null -> GuestIdentity(IdentityMethod.SmbiosUuid, identityDigest("uuid", uuid.lowercase()))
        ctime != null -> GuestIdentity(IdentityMethod.CreationTime, identityDigest("ctime", ctime.toString()))
        allowFallback -> GuestIdentity(IdentityMethod.Fallback, identityDigest("fallback", fallback))
        else -> null
    }
    /** The identity computed with the same method the schedule recorded, or null when that evidence is gone. */
    fun with(method: IdentityMethod): GuestIdentity? = when (method) {
        IdentityMethod.SmbiosUuid -> uuid?.let { GuestIdentity(method, identityDigest("uuid", it.lowercase())) }
        IdentityMethod.CreationTime -> ctime?.let { GuestIdentity(method, identityDigest("ctime", it.toString())) }
        IdentityMethod.Fallback -> GuestIdentity(method, identityDigest("fallback", fallback))
    }
}

internal fun identityDigest(kind: String, value: String): String = sha256Hex("ringmaster-identity-v1\n$kind\n$value")

internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/**
 * Digest of everything that decides where a background run connects and as whom: kind, origin, token ID and certificate
 * pin (or normal TLS). Any change to the saved connection invalidates schedules and background access bound to it.
 */
fun profileBinding(profile: ConnectionProfile): String =
    sha256Hex("ringmaster-binding-v1\n${profile.kind}\n${profile.origin}\n${profile.tokenId}\n${profile.sha256Pin ?: "pki"}")

/** What a schedule acts on. [node] is the last known route; the run reads the current one. */
data class ScheduleTarget(
    val profileId: String,
    val binding: String,
    val node: String,
    val kind: String,
    val vmid: Int,
    val name: String,
    val identity: GuestIdentity,
) {
    init {
        require(runCatching { UUID.fromString(profileId).toString() == profileId }.getOrDefault(false)) { "The schedule connection is not usable." }
        require(binding.matches(sha256PinPattern)) { "The schedule connection is not usable." }
        require(pveNodeNameOrNull(node) == node) { "The schedule node is not usable." }
        require(kind == "VM" || kind == "Container") { "Only VMs and containers can be scheduled." }
        require(vmid in 100..999_999_999) { "The guest ID is not usable." }
        require(name.length <= 128 && name.none { it.isISOControl() }) { "The guest name is not usable." }
    }
    val label: String get() = "${name.ifBlank { if (kind == "VM") "VM" else "Container" }} ($vmid)"
}

data class SchedulePlan(
    val id: String,
    val name: String,
    val revision: Int,
    val action: ScheduleAction,
    val repeat: ScheduleRepeat,
    val time: LocalTime,
    val zone: ZoneId,
    val target: ScheduleTarget,
    val enabled: Boolean,
    val authorizedAt: Instant,
    /** Occurrences due before this instant never run and are never recorded as missed (save, resume, reauthorize). */
    val notBefore: Instant,
    /** Monotonic claim watermark: no occurrence at or before it is ever handled again (clock rollback, restart). */
    val lastHandledDue: Instant? = null,
    /** Set when a run found the guest or connection changed. Execution stops until the user authorizes again. */
    val invalidReason: String? = null,
) {
    init {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "The schedule identity is not usable." }
        require(scheduleNameError(name) == null) { scheduleNameError(name)!! }
        require(revision in 1..1_000_000) { "The schedule revision is not usable." }
        require(time.second == 0 && time.nano == 0) { "Schedule times use whole minutes." }
        require(repeat.kind != RepeatKind.Once || repeat.date != null) { "A one-time schedule needs a date." }
        require(repeat.kind != RepeatKind.Days || repeat.days.isNotEmpty()) { "Choose at least one day." }
        require(invalidReason == null || (invalidReason.length <= 200 && invalidReason.none { it.isISOControl() })) { "The schedule state is not usable." }
    }
}

/** What happened to one due occurrence. In-flight phases come first. */
enum class RunPhase(val label: String, val inFlight: Boolean = false) {
    Claimed("Preparing", true),
    Dispatching("Sending", true),
    Accepted("Accepted, running", true),
    Succeeded("OK"),
    Warnings("Finished with warnings"),
    Failed("Failed"),
    Rejected("Refused by the server"),
    /** Accepted, but the task had not finished when the follow window ended. The task reference is kept. */
    Submitted("Submitted, not confirmed"),
    /** The request may have reached the server; the answer was lost. Never resent. */
    Unknown("Unknown, check the task list"),
    NoChange("Already in that state"),
    Skipped("Skipped"),
    NotSent("Not sent"),
    Missed("Missed"),
    Cancelled("Cancelled");
}

data class ScheduleRun(
    val id: String,
    val planId: String,
    val revision: Int,
    val due: Instant,
    val action: ScheduleAction,
    val guest: String,
    val phase: RunPhase,
    val detail: String,
    val updatedAt: Instant,
    /** Exact server task reference (UPID). Never a secret; tokens are not part of it. */
    val upid: String? = null,
    val node: String? = null,
    /** Earlier occurrences folded into this Missed record, so a long sleep does not produce a storm of rows. */
    val missedEarlier: Int = 0,
) {
    init {
        require(runCatching { UUID.fromString(id) }.isSuccess) { "The run identity is not usable." }
        require(detail.length <= 300 && detail.none { it.isISOControl() }) { "The run detail is not usable." }
        require(guest.length <= 160 && guest.none { it.isISOControl() }) { "The run guest is not usable." }
        require(upid == null || parseServerUpid(upid) != null) { "The run task reference is not usable." }
        require(node == null || pveNodeNameOrNull(node) == node) { "The run node is not usable." }
        require(missedEarlier >= 0) { "The run count is not usable." }
    }
}

/** Per-profile opt-in for background runs to read the remembered secret. Bound to the connection it was granted for. */
data class BackgroundAccess(val profileId: String, val binding: String, val grantedAt: Instant) {
    init {
        require(runCatching { UUID.fromString(profileId).toString() == profileId }.getOrDefault(false)) { "The access identity is not usable." }
        require(binding.matches(sha256PinPattern)) { "The access binding is not usable." }
    }
}

/** Runs due within this window after their time still start (catch-up after sleep, restart or a busy guest). */
val SCHEDULE_GRACE: Duration = Duration.ofMinutes(15)
/** How long a run follows its task before recording Submitted with the task reference. */
val SCHEDULE_FOLLOW: Duration = Duration.ofMinutes(2)
const val MAX_SCHEDULES = 64
const val MAX_SCHEDULE_RUNS = 400

fun scheduleNameError(name: String): String? = when {
    name.isBlank() -> "Enter a name."
    name != name.trim() -> "Remove spaces at the start or end of the name."
    name.length > 80 -> "Use at most 80 characters."
    name.any { it.isISOControl() } -> "The name contains a control character."
    else -> null
}

/** Whole-minute local time "HH:MM" (24-hour). */
fun parseScheduleTime(text: String): LocalTime? {
    val match = Regex("([01]?[0-9]|2[0-3]):([0-5][0-9])").matchEntire(text.trim()) ?: return null
    return LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
}

fun parseScheduleZone(text: String): ZoneId? = runCatching {
    val id = text.trim()
    // Region ids only ("Europe/Berlin", "UTC"): fixed offsets would silently ignore daylight saving time.
    if (id != "UTC" && !id.contains('/')) return null
    ZoneId.of(id)
}.getOrNull()

/**
 * The instant of one local date at the schedule time. A time inside a spring-forward gap runs later by the gap length
 * (02:30 becomes 03:30). A time inside a fall-back overlap runs once, at the earlier offset (java.time's documented rule).
 */
fun occurrenceAt(day: LocalDate, time: LocalTime, zone: ZoneId): Instant = ZonedDateTime.of(day, time, zone).toInstant()

/** Due times in (afterExclusive, untilInclusive], oldest first. At most [limit]; the range is clamped to about 400 days. */
fun occurrencesBetween(repeat: ScheduleRepeat, time: LocalTime, zone: ZoneId, afterExclusive: Instant, untilInclusive: Instant, limit: Int = 500): List<Instant> {
    if (!untilInclusive.isAfter(afterExclusive)) return emptyList()
    val from = maxOf(afterExclusive, untilInclusive.minus(400, ChronoUnit.DAYS))
    if (repeat.kind == RepeatKind.Once) {
        val at = occurrenceAt(repeat.date ?: return emptyList(), time, zone)
        return if (at.isAfter(from) && !at.isAfter(untilInclusive)) listOf(at) else emptyList()
    }
    val out = mutableListOf<Instant>()
    var day = from.atZone(zone).toLocalDate().minusDays(1)
    val last = untilInclusive.atZone(zone).toLocalDate().plusDays(1)
    while (!day.isAfter(last) && out.size < limit) {
        if (repeat.matches(day)) {
            val at = occurrenceAt(day, time, zone)
            if (at.isAfter(from) && !at.isAfter(untilInclusive)) out += at
        }
        day = day.plusDays(1)
    }
    return out
}

/** The first due time strictly after [after], or null (a past one-time schedule). */
fun nextOccurrence(repeat: ScheduleRepeat, time: LocalTime, zone: ZoneId, after: Instant): Instant? {
    if (repeat.kind == RepeatKind.Once) {
        val at = occurrenceAt(repeat.date ?: return null, time, zone)
        return at.takeIf { it.isAfter(after) }
    }
    var day = after.atZone(zone).toLocalDate().minusDays(1)
    repeat(10) {
        if (repeat.matches(day)) {
            val at = occurrenceAt(day, time, zone)
            if (at.isAfter(after)) return at
        }
        day = day.plusDays(1)
    }
    return null
}

/** The next time this plan could run, after its watermark and not-before. */
fun nextRun(plan: SchedulePlan, now: Instant): Instant? {
    val floor = listOfNotNull(now, plan.lastHandledDue, plan.notBefore.minusNanos(1)).max()
    return nextOccurrence(plan.repeat, plan.time, plan.zone, floor)
}

fun repeatLabel(repeat: ScheduleRepeat): String = when (repeat.kind) {
    RepeatKind.Once -> "Once on ${repeat.date}"
    RepeatKind.Daily -> "Every day"
    RepeatKind.Weekdays -> "Weekdays (Mon-Fri)"
    RepeatKind.Days -> repeat.days.sorted().joinToString(", ") { it.name.take(3).lowercase().replaceFirstChar(Char::uppercase) }
}

private val stampFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm")

/** Local time of an instant in the schedule zone, with the zone's short offset, e.g. "Mon 2 Mar 2026 07:00 (+01:00)". */
fun scheduleStamp(at: Instant, zone: ZoneId): String {
    val local = at.atZone(zone)
    return "${stampFormat.format(local)} (${local.offset.id.replace("Z", "UTC")})"
}

/**
 * Other enabled schedules for the same guest whose times fall within [window] of this plan's next occurrences during
 * the next 14 days, or that send the opposite action at the same minute. Warning only; saving is not blocked.
 */
fun scheduleOverlaps(plan: SchedulePlan, others: List<SchedulePlan>, now: Instant, window: Duration = Duration.ofMinutes(10)): List<SchedulePlan> {
    val horizon = now.plus(14, ChronoUnit.DAYS)
    val mine = occurrencesBetween(plan.repeat, plan.time, plan.zone, now, horizon)
    return others.filter { other ->
        other.id != plan.id && other.enabled && other.target.profileId == plan.target.profileId &&
            other.target.vmid == plan.target.vmid && other.target.kind == plan.target.kind &&
            occurrencesBetween(other.repeat, other.time, other.zone, now, horizon).any { theirs ->
                mine.any { Duration.between(it, theirs).abs() <= window }
            }
    }
}

/** Editor input. Validated by [scheduleDraftErrors]; turned into a plan by the engine with the authorization captured. */
data class ScheduleDraft(
    val name: String,
    val action: ScheduleAction,
    val repeat: RepeatKind,
    val days: Set<DayOfWeek>,
    val date: String,
    val time: String,
    val zone: String,
)

/** Field -> message. Empty when the draft is valid at [now]. */
fun scheduleDraftErrors(draft: ScheduleDraft, now: Instant): Map<String, String> {
    val errors = linkedMapOf<String, String>()
    scheduleNameError(draft.name)?.let { errors["name"] = it }
    val time = parseScheduleTime(draft.time)
    if (time == null) errors["time"] = "Use a 24-hour time such as 07:30."
    val zone = parseScheduleZone(draft.zone)
    if (zone == null) errors["zone"] = "Use a time zone region such as Europe/Berlin or UTC."
    when (draft.repeat) {
        RepeatKind.Days -> if (draft.days.isEmpty()) errors["days"] = "Choose at least one day."
        RepeatKind.Once -> {
            val date = runCatching { LocalDate.parse(draft.date.trim()) }.getOrNull()
            if (date == null) errors["date"] = "Use a date such as 2026-12-24."
            else if (time != null && zone != null && !occurrenceAt(date, time, zone).isAfter(now)) errors["date"] = "This time has already passed."
        }
        else -> {}
    }
    return errors
}

fun ScheduleDraft.toRepeat(): ScheduleRepeat = when (repeat) {
    RepeatKind.Once -> ScheduleRepeat(RepeatKind.Once, date = LocalDate.parse(date.trim()))
    RepeatKind.Days -> ScheduleRepeat(RepeatKind.Days, days = days)
    else -> ScheduleRepeat(repeat)
}

/** Fields whose change needs a new authorization: the target, what is done, and when. */
fun authorizationChanged(old: SchedulePlan, action: ScheduleAction, repeat: ScheduleRepeat, time: LocalTime, zone: ZoneId, target: ScheduleTarget): Boolean =
    old.action != action || old.repeat != repeat || old.time != time || old.zone != zone || old.target != target
