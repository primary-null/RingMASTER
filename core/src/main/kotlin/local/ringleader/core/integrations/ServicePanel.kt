package local.ringleader.core.integrations

import java.time.Duration
import java.time.Instant

/** What a whole panel is doing. Blocks carry their own freshness and error beside it. */
enum class PanelStatus {
    /** Linked, nothing answered yet. */
    Loading,
    Ok,
    /** The service did not answer; the last values stay, with the reason. */
    Unreachable,
    /** The service refused the credential (or lacks a permission). No polling until the credential is edited. */
    Unauthorised,
    /** No secret in memory this run. */
    CredentialNeeded,
    /** The certificate is not the pinned one, or is not trusted: review it. */
    Certificate,
    /** The plain-HTTP rule stops the credential: an acknowledgement is needed, or the address is public. */
    PlainHttpBlocked,
    /** It answered, but not in the shape the template expects. */
    ApiChanged,
    /** A recognised version that RingMASTER does not read yet. */
    Unsupported,
    /** The link's kill switch is on. */
    Paused,
}

/** One reading, evaluated. [value] is a number, a text, true/false, or [XValue.Gone] with the typed reason. */
data class ReadingValue(
    val id: String, val label: String, val unit: ReadingUnit, val value: XValue, val text: String, val level: Level,
    val identity: Boolean, val required: Boolean, val help: String?,
) {
    val number: Double? get() = (value as? XValue.Num)?.value
    val unavailable: Unavailable? get() = (value as? XValue.Gone)?.why
}

data class SeriesPoint(val at: Instant, val value: Double?)
data class SeriesLine(val name: String, val points: List<SeriesPoint>)
data class SeriesValue(val id: String, val label: String, val unit: ReadingUnit, val lines: List<SeriesLine>, val identity: Boolean, val unavailable: Unavailable?)

/** [share] is the row's value against the largest row (0..1), for a proportional bar. [open] is a path on the service's own address. */
data class ListRow(val title: String, val subtitle: String?, val value: Double?, val valueText: String?, val share: Double?, val open: String?)
data class ListValue(val id: String, val label: String, val unit: ReadingUnit, val rows: List<ListRow>, val total: Int, val identity: Boolean, val unavailable: Unavailable?)

data class GridCell(val title: String, val state: String, val level: Level, val valueText: String?, val open: String?)
data class GridValue(val id: String, val label: String, val cells: List<GridCell>, val total: Int, val identity: Boolean, val unavailable: Unavailable?)

/** A request as the panel sees it: the last good answer stays while a later fetch fails, with that failure beside it. */
data class RequestSnapshot(
    val response: ResponseValue? = null,
    /** When the last good answer arrived. */
    val okAt: Instant? = null,
    /** The newest fetch, good or not. */
    val fetchedAt: Instant? = null,
    val status: Int? = null,
    val problem: ServiceProblem? = null,
    val nextAt: Instant? = null,
    /** Polling of this request stopped (three "API changed" answers, or a setting is missing). */
    val stopped: Boolean = false,
)

/** One panel block with its own freshness and error. [text] is set for a notice. */
data class BlockState(
    val block: PanelBlock, val requests: Set<String>, val fetchedAt: Instant?, val stale: Boolean, val problem: ServiceProblem?,
    val visible: Boolean, val text: String? = null,
)

/** The sidebar pin's or the ticker tile's one reading. */
data class CompactValue(val label: String?, val text: String, val level: Level, val spark: List<Double?>?)

/**
 * Everything a panel shows, immutable, made by [ServicePanel.evaluate] from the answers alone. It names blocks and readings,
 * never colours or drawing: the UI decides how a hero, a gauge or a grid looks.
 */
data class ServicePanelState(
    val templateId: String, val templateName: String, val templateVersion: String, val variantId: String, val variantLabel: String,
    val status: PanelStatus,
    /** The sentence for [status], when it is not Ok. */
    val problem: ServiceProblem?,
    val readings: List<ReadingValue>, val series: List<SeriesValue>, val lists: List<ListValue>, val grids: List<GridValue>,
    val blocks: List<BlockState>, val pin: CompactValue?, val ticker: CompactValue?,
    val requests: Map<String, RequestSnapshot>,
    /** Set when the link is plain http: the standing chip. */
    val unencrypted: UnencryptedMark?,
    /** The only credential this service offers can change everything: the standing warning, with [power] as its words. */
    val fullPower: Boolean, val power: String,
    val evaluatedAt: Instant,
) {
    fun reading(id: String) = readings.firstOrNull { it.id == id }
    fun series(id: String) = series.firstOrNull { it.id == id }
    fun list(id: String) = lists.firstOrNull { it.id == id }
    fun grid(id: String) = grids.firstOrNull { it.id == id }
    /** When the oldest shown answer arrived; null before the first. */
    val freshness: Instant? get() = requests.values.mapNotNull { it.okAt }.minOrNull()
}

/** What [ServicePanel.evaluate] reads. Nothing here is a secret. */
class PanelInput(
    val requests: Map<String, RequestSnapshot>,
    val now: Instant,
    val params: Map<String, List<String>> = emptyMap(),
    /** Kept values of `sampled` series, by series id. */
    val sampled: Map<String, List<SeriesPoint>> = emptyMap(),
    val rates: Map<String, RateSample> = emptyMap(),
    val paused: Boolean = false,
    /** A problem of the link as a whole (credential, certificate, plain-HTTP rule), decided by the engine. */
    val linkProblem: ServiceProblem? = null,
    val unencrypted: UnencryptedMark? = null,
    /** The requests being polled now; null means all of them. */
    val wanted: Set<String>? = null,
)

class Evaluated(val state: ServicePanelState, val rates: Map<String, RateSample>)

/** Template + answers = state. Pure: no I/O, no clock but [PanelInput.now], no exception for any answer. */
object ServicePanel {
    const val MAX_POINTS = 2_000
    private const val MAX_SHOWN_TEXT = 200

    fun evaluate(template: ServiceTemplate, variant: TemplateVariant, input: PanelInput): Evaluated {
        val memory = RateMemory(input.rates)
        val responses = input.requests.mapNotNull { (id, snap) ->
            (snap.response ?: snap.problem?.let { ResponseValue.Failed(it.message) })?.let { id to it }
        }.toMap()
        val ctx = ExprContext(responses, input.params, input.now, rates = memory)
        val readings = variant.readings.map { reading(it, ctx) }
        val byId = readings.associateBy { it.id }
        val series = variant.series.map { series(it, ctx, input) }
        val lists = variant.lists.map { list(it, ctx) }
        val grids = variant.grids.map { grid(it, ctx) }
        val blocks = variant.panel.map { block(it, variant, input, ctx, byId, grids) }
        val (status, problem) = status(template, variant, input, readings)
        val state = ServicePanelState(
            template.id, template.name, template.templateVersion, variant.id, variant.label, status, problem,
            readings, series, lists, grids, blocks,
            variant.compact?.pin?.let { compact(null, it.reading, it.state, it.zeroText, null, byId, series) },
            variant.compact?.ticker?.let { compact(it.label, it.reading, it.state, null, it.spark, byId, series) },
            input.requests, input.unencrypted, variant.auth.fullPower, variant.auth.power, input.now,
        )
        return Evaluated(state, memory.next.toMap())
    }

    private fun clean(text: String, max: Int = MAX_SHOWN_TEXT): String {
        val plain = text.filterNot { it.isISOControl() }.trim()
        return if (plain.length > max) plain.take(max - 1) + "\u2026" else plain
    }

    private fun scalar(v: XValue, unit: ReadingUnit, what: String): XValue = when {
        v is XValue.Gone -> v
        v is XValue.Items || v is XValue.Obj -> gone(UnavailableKind.WrongType, "$what is ${TemplateExpr.typeName(v)}, not a single value.")
        unit.numeric -> when (v) {
            is XValue.Num -> v
            is XValue.Text -> v.value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let { XValue.Num(it) }
                ?: gone(UnavailableKind.NotANumber, "$what is not a number.")
            else -> gone(UnavailableKind.WrongType, "$what is ${TemplateExpr.typeName(v)}, not a number.")
        }
        v is XValue.Text -> XValue.Text(clean(v.value))
        else -> v
    }

    private fun reading(spec: ReadingSpec, ctx: ExprContext): ReadingValue {
        val value = scalar(TemplateExpr.eval(spec.expr.node, ctx), spec.unit, spec.label)
        val level = when {
            value is XValue.Gone -> Level.Neutral
            value is XValue.Num && spec.levels != null -> spec.levels.of(value.value)
            spec.unit == ReadingUnit.State -> spec.states[ServiceUnits.stateKey(value)] ?: Level.Neutral
            else -> Level.Neutral
        }
        return ReadingValue(spec.id, spec.label, spec.unit, value, ServiceUnits.format(value, spec.unit, ctx.now), level, spec.identity, spec.required, spec.help)
    }

    private fun why(v: XValue, what: String): Unavailable =
        (v as? XValue.Gone)?.why ?: Unavailable(UnavailableKind.WrongType, "$what is ${TemplateExpr.typeName(v)}, not a list.")

    private fun instant(v: XValue): Instant? = when (v) {
        is XValue.Num -> v.value.takeIf { it in 0.0..4.0e10 }?.let { Instant.ofEpochMilli((it * 1000).toLong()) }
        is XValue.Text -> TemplateExpr.parseTime(v.value)?.let { Instant.ofEpochMilli((it * 1000).toLong()) }
        else -> null
    }

    private fun numberOrNull(v: XValue): Double? = when (v) {
        is XValue.Num -> v.value
        is XValue.Text -> v.value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    /** Pairs times with values. A value that is not a number is a gap; a time that is not a time drops the point. */
    private fun points(times: XValue, values: XValue): Pair<List<SeriesPoint>, Unavailable?> {
        val t = (times as? XValue.Items)?.items ?: return emptyList<SeriesPoint>() to why(times, "The time")
        val v = (values as? XValue.Items)?.items ?: return emptyList<SeriesPoint>() to why(values, "The line")
        if (t.size != v.size) return emptyList<SeriesPoint>() to Unavailable(UnavailableKind.WrongType, "The line has ${v.size} values for ${t.size} times.")
        val out = ArrayList<SeriesPoint>(minOf(t.size, MAX_POINTS))
        for (i in maxOf(0, t.size - MAX_POINTS) until t.size) instant(t[i])?.let { out += SeriesPoint(it, numberOrNull(v[i])) }
        return out to null
    }

    private fun series(spec: SeriesSpec, ctx: ExprContext, input: PanelInput): SeriesValue = when (spec) {
        is SeriesSpec.Sampled -> SeriesValue(spec.id, spec.label, spec.unit, listOf(SeriesLine(spec.label, input.sampled[spec.id].orEmpty())), false, null)
        is SeriesSpec.Fixed -> {
            val times = TemplateExpr.eval(spec.time.node, ctx)
            var problem: Unavailable? = null
            val lines = spec.values.map { (name, expr) ->
                val (points, bad) = points(times, TemplateExpr.eval(expr.node, ctx))
                if (bad != null && problem == null) problem = bad
                SeriesLine(name, points)
            }
            SeriesValue(spec.id, spec.label, spec.unit, if (problem == null) lines else emptyList(), false, problem)
        }
        is SeriesSpec.Each -> {
            val each = TemplateExpr.eval(spec.each.node, ctx)
            val items = (each as? XValue.Items)?.items
            if (items == null) SeriesValue(spec.id, spec.label, spec.unit, emptyList(), spec.identity, why(each, spec.label))
            else SeriesValue(spec.id, spec.label, spec.unit, items.take(spec.max).mapIndexed { index, item ->
                val c = ctx.withItem(item)
                val name = TemplateExpr.plain(TemplateExpr.eval(spec.name.node, c))?.let { clean(it, 80) } ?: "Line ${index + 1}"
                SeriesLine(name, points(TemplateExpr.eval(spec.time.node, c), TemplateExpr.eval(spec.value.node, c)).first)
            }, spec.identity, null)
        }
    }

    /** A deep link from an answer is used only when it is a plain path on the service's own address. */
    fun safeLink(text: String?): String? = text?.takeIf {
        it.length in 1..300 && it.startsWith('/') && !it.startsWith("//") && '\\' !in it && it.none { c -> c.isISOControl() || c.isWhitespace() }
    }

    private fun text(expr: Expr?, ctx: ExprContext, max: Int = 120): String? =
        expr?.let { TemplateExpr.plain(TemplateExpr.eval(it.node, ctx)) }?.let { clean(it, max) }?.takeIf { it.isNotEmpty() }

    private fun list(spec: ListSpec, ctx: ExprContext): ListValue {
        val found = TemplateExpr.eval(spec.items.node, ctx)
        val items = (found as? XValue.Items)?.items ?: return ListValue(spec.id, spec.label, spec.unit, emptyList(), 0, spec.identity, why(found, spec.label))
        val rows = items.take(spec.max).mapIndexed { index, item ->
            val c = ctx.withItem(item)
            val value = spec.value?.let { numberOrNull(TemplateExpr.eval(it.node, c)) }
            ListRow(text(spec.title, c) ?: "Item ${index + 1}", text(spec.subtitle, c), value, value?.let { ServiceUnits.number(it, spec.unit, ctx.now) }, null,
                safeLink(spec.open?.let { TemplateExpr.plain(TemplateExpr.eval(it.node, c)) }))
        }
        val largest = rows.mapNotNull { it.value }.maxOrNull()?.takeIf { it > 0 }
        return ListValue(spec.id, spec.label, spec.unit, rows.map { r -> r.copy(share = if (largest != null && r.value != null) (r.value / largest).coerceIn(0.0, 1.0) else null) },
            items.size, spec.identity, null)
    }

    private val severity = mapOf(Level.Bad to 0, Level.Warn to 1)

    private fun grid(spec: GridSpec, ctx: ExprContext): GridValue {
        val found = TemplateExpr.eval(spec.items.node, ctx)
        val items = (found as? XValue.Items)?.items ?: return GridValue(spec.id, spec.label, emptyList(), 0, spec.identity, why(found, spec.label))
        val cells = items.mapIndexed { index, item ->
            val c = ctx.withItem(item)
            val state = text(spec.state, c, 40) ?: "no data"
            val value = spec.value?.let { TemplateExpr.eval(it.node, c) }?.takeIf { it !is XValue.Gone }
            GridCell(text(spec.title, c) ?: "Item ${index + 1}", state, spec.states[state] ?: Level.Neutral,
                value?.let { clean(ServiceUnits.format(it, spec.unit, ctx.now), 40) }, safeLink(spec.open?.let { TemplateExpr.plain(TemplateExpr.eval(it.node, c)) }))
        }
        // Bad first, then warn, then the rest by name, so what needs attention is never cut off by the cap.
        val ordered = cells.sortedWith(compareBy<GridCell> { severity[it.level] ?: 2 }.thenBy { it.title.lowercase() })
        return GridValue(spec.id, spec.label, ordered.take(spec.max), cells.size, spec.identity, null)
    }

    private fun block(block: PanelBlock, variant: TemplateVariant, input: PanelInput, ctx: ExprContext, readings: Map<String, ReadingValue>, grids: List<GridValue>): BlockState {
        val needs = variant.requestsOfBlock(block)
        val snaps = needs.mapNotNull { id -> input.requests[id]?.let { id to it } }
        val fetched = if (snaps.size == needs.size && snaps.all { it.second.okAt != null }) snaps.minOfOrNull { it.second.okAt!! } else null
        val stale = snaps.any { (id, snap) ->
            val every = variant.requests[id]?.every ?: return@any false
            snap.okAt != null && Duration.between(snap.okAt, input.now) > every.multipliedBy(3)
        }
        val problem = snaps.firstNotNullOfOrNull { it.second.problem }
        return when (block) {
            is PanelBlock.Notice -> {
                val show = (TemplateExpr.eval(block.whenExpr.node, ctx) as? XValue.Bool)?.value == true
                val text = TemplateVariant.NOTICE_FIELD.replace(block.text) { m -> readings[m.groupValues[1]]?.text ?: "no data" }
                BlockState(block, needs, fetched, stale, problem, show, text)
            }
            is PanelBlock.Grid -> BlockState(block, needs, fetched, stale, problem, !block.collapsedWhenEmpty || grids.firstOrNull { it.id == block.grid }?.cells?.isNotEmpty() == true)
            else -> BlockState(block, needs, fetched, stale, problem, true)
        }
    }

    private fun compact(label: String?, reading: String, state: String?, zeroText: String?, spark: String?, readings: Map<String, ReadingValue>, series: List<SeriesValue>): CompactValue {
        val r = readings[reading]
        val text = when {
            r == null -> "no data"
            zeroText != null && r.number == 0.0 -> zeroText
            else -> r.text
        }
        val level = (state?.let { readings[it] } ?: r)?.level ?: Level.Neutral
        val line = spark?.let { ref ->
            val s = series.firstOrNull { it.id == ref.substringBefore('.') }
            val name = ref.substringAfter('.', "")
            (if (name.isEmpty()) s?.lines?.firstOrNull() else s?.lines?.firstOrNull { it.name == name })?.points?.takeLast(24)?.map { it.value }
        }
        return CompactValue(label, text, level, line)
    }

    private fun status(template: ServiceTemplate, variant: TemplateVariant, input: PanelInput, readings: List<ReadingValue>): Pair<PanelStatus, ServiceProblem?> {
        if (input.paused) return PanelStatus.Paused to null
        variant.unsupported?.let { return PanelStatus.Unsupported to ServiceProblem(ProblemKind.UnsupportedVersion, it) }
        input.linkProblem?.let { return statusOf(it.kind) to it }
        val wanted = input.wanted ?: variant.requests.keys
        val needed = wanted.filter { variant.requests[it]?.optional == false }
        val failing = needed.mapNotNull { input.requests[it]?.problem }
        failing.firstOrNull { it.kind.waitsForOwner || it.kind == ProblemKind.Forbidden }?.let { return statusOf(it.kind) to it }
        // A required reading whose answers all arrived and that still has no value: the shape changed.
        val changed = variant.readings.filter { it.required }.firstOrNull { spec ->
            val asked = spec.expr.requests
            asked.isNotEmpty() && asked.all { input.requests[it]?.response != null && input.requests[it]?.problem == null } &&
                readings.first { it.id == spec.id }.unavailable?.kind.let { it != null && it != UnavailableKind.NoAnswer }
        }
        if (changed != null) return PanelStatus.ApiChanged to apiChanged(template, variant)
        failing.firstOrNull { it.kind in SHAPE_KINDS }?.let { return PanelStatus.ApiChanged to it }
        failing.firstOrNull()?.let { return PanelStatus.Unreachable to it }
        return (if (needed.none { input.requests[it]?.okAt != null }) PanelStatus.Loading else PanelStatus.Ok) to null
    }

    private val SHAPE_KINDS = setOf(ProblemKind.ApiChanged, ProblemKind.NotThisService, ProblemKind.Redirected, ProblemKind.TooLarge)

    fun apiChanged(template: ServiceTemplate, variant: TemplateVariant) = ServiceProblem(ProblemKind.ApiChanged,
        "${template.name} answered, but not in the shape template ${template.templateVersion} (${variant.id}) expects. The service's API may have changed.")

    fun statusOf(kind: ProblemKind): PanelStatus = when (kind) {
        ProblemKind.CredentialNeeded -> PanelStatus.CredentialNeeded
        ProblemKind.CredentialRefused, ProblemKind.Forbidden -> PanelStatus.Unauthorised
        ProblemKind.CertificateChanged, ProblemKind.CertificateUntrusted -> PanelStatus.Certificate
        ProblemKind.PlainHttpNeedsAcknowledgement, ProblemKind.PlainHttpRefused, ProblemKind.AddressRefused, ProblemKind.ProxmoxAddress -> PanelStatus.PlainHttpBlocked
        ProblemKind.UnsupportedVersion -> PanelStatus.Unsupported
        ProblemKind.ApiChanged, ProblemKind.NotThisService, ProblemKind.Redirected, ProblemKind.TooLarge -> PanelStatus.ApiChanged
        else -> PanelStatus.Unreachable
    }

    // ---- the text rendering: what the golden files hold ----

    /**
     * The whole state as plain lines, stable for a given state. With [showcase], every field a template marks `identity`
     * (domains, client addresses, entity names, the home's name) is replaced; numbers, states and shapes stay.
     */
    fun text(state: ServicePanelState, showcase: Boolean = false): String = buildString {
        fun line(s: String) { append(s).append('\n') }
        fun level(l: Level) = l.name.lowercase()
        line("${state.templateName} ${state.templateVersion} (${state.variantId}) \u00b7 ${state.status}" +
            (state.problem?.let { ": ${it.message}" } ?: "") + (state.unencrypted?.let { " \u00b7 $it" } ?: "") + if (state.fullPower) " \u00b7 full-power credential" else "")
        for (r in state.readings) {
            val shown = if (showcase && r.identity && r.unavailable == null) "hidden" else r.text
            line("reading ${r.id} \u00b7 ${r.label}: $shown [${level(r.level)}]" + (r.unavailable?.let { " (${it.kind}: ${it.detail})" } ?: "") + if (r.required) " required" else "")
        }
        for (s in state.series) {
            if (s.unavailable != null) { line("series ${s.id} \u00b7 ${s.label}: no data (${s.unavailable.kind}: ${s.unavailable.detail})"); continue }
            line("series ${s.id} \u00b7 ${s.label} [${s.unit.id}]: ${s.lines.size} line${if (s.lines.size == 1) "" else "s"}")
            s.lines.forEachIndexed { i, l ->
                val values = l.points.mapNotNull { it.value }
                val span = if (l.points.isEmpty()) "no points" else "${l.points.size} points ${l.points.first().at} to ${l.points.last().at}"
                val name = if (showcase && s.identity) "line ${i + 1}" else l.name
                line("  $name: $span" + if (values.isEmpty()) "" else ", sum ${ServiceUnits.number(values.sum(), ReadingUnit.Number)}, max ${ServiceUnits.number(values.max(), ReadingUnit.Number)}, last ${ServiceUnits.number(values.last(), ReadingUnit.Number)}")
            }
        }
        for (l in state.lists) {
            if (l.unavailable != null) { line("list ${l.id} \u00b7 ${l.label}: no data (${l.unavailable.kind}: ${l.unavailable.detail})"); continue }
            line("list ${l.id} \u00b7 ${l.label}: ${l.rows.size} of ${l.total}")
            l.rows.forEachIndexed { i, row ->
                val title = if (showcase && l.identity) "item ${i + 1}" else row.title + (row.subtitle?.let { " ($it)" } ?: "")
                line("  $title" + (row.valueText?.let { ": $it" } ?: "") + (row.share?.let { " bar ${Math.round(it * 100)} %" } ?: "") +
                    (row.open?.takeIf { !(showcase && l.identity) }?.let { " -> $it" } ?: ""))
            }
        }
        for (g in state.grids) {
            if (g.unavailable != null) { line("grid ${g.id} \u00b7 ${g.label}: no data (${g.unavailable.kind}: ${g.unavailable.detail})"); continue }
            line("grid ${g.id} \u00b7 ${g.label}: ${g.cells.size} of ${g.total}")
            g.cells.forEachIndexed { i, cell ->
                line("  ${if (showcase && g.identity) "item ${i + 1}" else cell.title}: ${cell.state} [${level(cell.level)}]" + (cell.valueText?.let { " $it" } ?: ""))
            }
        }
        state.blocks.forEachIndexed { i, b ->
            val what = when (val k = b.block) {
                is PanelBlock.Hero -> "hero ${k.reading}"
                is PanelBlock.Tiles -> "tiles ${k.readings.joinToString(",")}"
                is PanelBlock.Gauge -> "${if (k.ring) "ring" else "bar"} ${k.reading}"
                is PanelBlock.Chart -> "chart ${k.series} ${k.kind.id}"
                is PanelBlock.Breakdown -> "breakdown ${k.list}"
                is PanelBlock.TopList -> "list ${k.list}"
                is PanelBlock.Grid -> "grid ${k.grid}"
                is PanelBlock.Facts -> "facts ${k.readings.joinToString(",")}"
                is PanelBlock.Notice -> "notice \"${b.text}\""
            }
            line("block ${i + 1} $what" + (if (!b.visible) " hidden" else "") + (if (b.fetchedAt == null) " no answer yet" else "") + (if (b.stale) " stale" else "") +
                (b.problem?.let { " problem ${it.kind}" } ?: ""))
        }
        state.pin?.let { line("pin: ${it.text} [${level(it.level)}]") }
        state.ticker?.let { line("ticker: ${it.label} ${it.text} [${level(it.level)}]" + (it.spark?.let { s -> " spark ${s.size} points" } ?: "")) }
    }
}
