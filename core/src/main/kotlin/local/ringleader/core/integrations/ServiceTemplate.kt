package local.ringleader.core.integrations

import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import local.ringleader.core.SERVICE_CATALOGUE
import local.ringleader.core.requireJsonDepth

/** A parsed expression with its source text, for messages. */
class Expr(val text: String, val node: TemplateExpr.Node) {
    val requests: Set<String> get() = TemplateExpr.requests(node)
    override fun toString() = text
}

data class TemplatePort(val port: Int, val secure: Boolean, val path: String)
data class TemplateDetect(val catalogue: List<String>, val helperScripts: List<String>, val ports: List<TemplatePort>)

enum class ResponseKind { Json, Text }

data class TemplateProbe(val path: String, val query: Map<String, String>, val accept: Set<Int>, val expect: Expr?, val kind: ResponseKind)

enum class AuthScheme(val id: String) {
    None("none"), HeaderKey("header-key"), HeaderTemplate("header-template"), Basic("basic"), SessionPassword("session-password");
    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } }
}

data class AuthField(val id: String, val label: String, val secret: Boolean)

/** A session login: the ONE place a template may cause a POST. Its JSON body is built only from credential fields. */
data class LoginSpec(
    val path: String,
    /** JSON key -> credential field id. */
    val body: Map<String, String>,
    val token: List<String>,
    val validity: List<String>?,
    val totp: List<String>?,
    val sendAs: String,
    val minIntervalSeconds: Long,
    val extendsOnUse: Boolean,
)

data class AuthSpec(
    val scheme: AuthScheme,
    val header: String? = null,
    val prefix: String = "",
    val format: String? = null,
    val fixedUser: String? = null,
    val fields: List<AuthField> = emptyList(),
    val ask: String = "",
    val power: String = "",
    /** True when the only credential the service offers can change everything: the link carries an honest warning. */
    val fullPower: Boolean = false,
    val readOnly: String? = null,
    val where: List<String> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val login: LoginSpec? = null,
    val logoutPath: String? = null,
) {
    val secretField get() = fields.firstOrNull { it.secret }
    val needsCredential get() = scheme != AuthScheme.None
}

enum class ParamKind { Text, Choice, Multi }
data class ChoiceSpec(val request: String, val items: Expr, val value: Expr, val label: Expr)
data class ParamSpec(val id: String, val label: String, val kind: ParamKind, val max: Int, val pattern: Regex, val choices: ChoiceSpec?)

data class RequestSpec(
    val id: String,
    val path: String,
    val query: Map<String, String>,
    val every: Duration,
    val minEvery: Duration,
    val maxBytes: Int,
    val kind: ResponseKind,
    val optional: Boolean,
    val errors: Map<String, String>,
)

data class ReadingSpec(
    val id: String, val label: String, val expr: Expr, val unit: ReadingUnit, val required: Boolean, val identity: Boolean,
    val levels: Levels?, val states: Map<String, Level>, val help: String?,
)

sealed interface SeriesSpec {
    val id: String; val label: String; val unit: ReadingUnit
    /** The line names are identities (entity ids, host names): hidden in showcase. */
    val identity: Boolean
    data class Fixed(override val id: String, override val label: String, override val unit: ReadingUnit, val time: Expr, val values: Map<String, Expr>) : SeriesSpec { override val identity get() = false }
    /** One line per item of [each]: [name], [time] and [value] are item expressions over that item. */
    data class Each(override val id: String, override val label: String, override val unit: ReadingUnit, val each: Expr, val name: Expr, val time: Expr, val value: Expr, val max: Int,
        override val identity: Boolean) : SeriesSpec
    /** The engine keeps the last [keep] values of a reading, one per poll. */
    data class Sampled(override val id: String, override val label: String, override val unit: ReadingUnit, val reading: String, val keep: Int) : SeriesSpec { override val identity get() = false }
}

data class ListSpec(val id: String, val label: String, val items: Expr, val title: Expr, val subtitle: Expr?, val value: Expr?, val unit: ReadingUnit,
    val max: Int, val identity: Boolean, val open: Expr?)

data class GridSpec(val id: String, val label: String, val items: Expr, val title: Expr, val state: Expr, val states: Map<String, Level>,
    val value: Expr?, val unit: ReadingUnit, val max: Int, val identity: Boolean, val open: Expr?)

enum class ChartKind(val id: String) { Area("area"), StackedArea("stacked-area"), Line("line"), Bars("bars"), StackedBars("stacked-bars");
    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } } }

sealed interface PanelBlock {
    val open: String?
    data class Hero(val reading: String, val sub: String?, val state: String?, val caption: String?, val zeroText: String?, override val open: String?) : PanelBlock
    data class Tiles(val readings: List<String>, val sparks: Map<String, String>, override val open: String?) : PanelBlock
    data class Gauge(val ring: Boolean, val reading: String, val maxReading: String?, val maxValue: Double?, override val open: String?) : PanelBlock
    data class Chart(val series: String, val kind: ChartKind, val height: Int, override val open: String?) : PanelBlock
    data class Breakdown(val list: String, override val open: String?) : PanelBlock
    data class TopList(val list: String, val bars: Boolean, override val open: String?) : PanelBlock
    data class Grid(val grid: String, val collapsedWhenEmpty: Boolean, override val open: String?) : PanelBlock
    data class Facts(val readings: List<String>, override val open: String?) : PanelBlock
    /** [text] may name readings as {readingId}; shown when [whenExpr] is true. */
    data class Notice(val whenExpr: Expr, val text: String, val reading: String?, override val open: String?) : PanelBlock
}

data class CompactPin(val reading: String, val state: String?, val zeroText: String?)
data class CompactTicker(val label: String, val reading: String, val spark: String?, val state: String?)
data class CompactSpec(val pin: CompactPin?, val ticker: CompactTicker?)
data class LinksSpec(val home: String, val help: String?)

data class TemplateVariant(
    val id: String,
    val label: String,
    val probe: TemplateProbe,
    /** Set for a recognised version that RingMASTER does not read yet: the panel shows this sentence and nothing else. */
    val unsupported: String?,
    val auth: AuthSpec,
    val params: List<ParamSpec>,
    val requests: Map<String, RequestSpec>,
    val readings: List<ReadingSpec>,
    val series: List<SeriesSpec>,
    val lists: List<ListSpec>,
    val grids: List<GridSpec>,
    val panel: List<PanelBlock>,
    val compact: CompactSpec?,
    val links: LinksSpec?,
) {
    fun reading(id: String) = readings.firstOrNull { it.id == id }

    /** Requests each reading, series, list and grid reads, so the engine fetches only what a visible block needs. */
    val readingRequests: Map<String, Set<String>> by lazy { readings.associate { it.id to it.expr.requests } }

    fun requestsOfSeries(s: SeriesSpec): Set<String> = when (s) {
        is SeriesSpec.Fixed -> s.time.requests + s.values.values.flatMap { it.requests }
        is SeriesSpec.Each -> s.each.requests + s.name.requests + s.time.requests + s.value.requests
        is SeriesSpec.Sampled -> readingRequests[s.reading].orEmpty()
    }
    fun requestsOfList(l: ListSpec) = l.items.requests + listOfNotNull(l.title, l.subtitle, l.value, l.open).flatMap { it.requests }
    fun requestsOfGrid(g: GridSpec) = g.items.requests + listOfNotNull(g.title, g.state, g.value, g.open).flatMap { it.requests }

    fun requestsOfBlock(b: PanelBlock): Set<String> {
        fun r(id: String?) = id?.let { readingRequests[it] }.orEmpty()
        fun spark(ref: String) = series.firstOrNull { it.id == ref.substringBefore('.') }?.let(::requestsOfSeries).orEmpty()
        return when (b) {
            is PanelBlock.Hero -> r(b.reading) + r(b.sub) + r(b.state)
            is PanelBlock.Tiles -> b.readings.flatMap { r(it) }.toSet() + b.sparks.values.flatMap { spark(it) }
            is PanelBlock.Gauge -> r(b.reading) + r(b.maxReading)
            is PanelBlock.Chart -> spark(b.series)
            is PanelBlock.Breakdown -> lists.first { it.id == b.list }.let(::requestsOfList)
            is PanelBlock.TopList -> lists.first { it.id == b.list }.let(::requestsOfList)
            is PanelBlock.Grid -> grids.first { it.id == b.grid }.let(::requestsOfGrid)
            is PanelBlock.Facts -> b.readings.flatMap { r(it) }.toSet()
            is PanelBlock.Notice -> b.whenExpr.requests + r(b.reading) + NOTICE_FIELD.findAll(b.text).flatMap { r(it.groupValues[1]) }
        }
    }

    /** What the sidebar pin and the ticker tile need: nothing else is polled for them. */
    val compactRequests: Set<String> by lazy {
        val ids = listOfNotNull(compact?.pin?.reading, compact?.pin?.state, compact?.ticker?.reading, compact?.ticker?.state)
        ids.flatMap { readingRequests[it].orEmpty() }.toSet() + (compact?.ticker?.spark?.let { ref ->
            series.firstOrNull { it.id == ref.substringBefore('.') }?.let(::requestsOfSeries) }.orEmpty())
    }

    val panelRequests: Set<String> by lazy { panel.flatMap { requestsOfBlock(it) }.toSet() + compactRequests + readings.filter { it.required }.flatMap { it.expr.requests } }

    companion object { internal val NOTICE_FIELD = Regex("\\{([A-Za-z][A-Za-z0-9]{0,40})}") }
}

data class ServiceTemplate(
    val schema: Int,
    val id: String,
    val templateVersion: String,
    val name: String,
    val glyph: String,
    val tags: List<String>,
    val sources: List<String>,
    val detect: TemplateDetect,
    val variants: List<TemplateVariant>,
) {
    fun variant(id: String) = variants.firstOrNull { it.id == id }
}

/** Every mistake found in one template file, each with where it is and what to write instead. */
class TemplateError(val file: String, val problems: List<String>) :
    IllegalArgumentException("$file: " + problems.joinToString("\n  ", prefix = if (problems.size > 1) "\n  " else ""))

/**
 * Reads and validates one template. Templates are DATA: JSON with a closed schema, parsed into the types above. No
 * scripting, reflection or code loading; expressions are the closed language of [TemplateExpr]. Every problem is collected
 * so one run shows them all.
 */
object ServiceTemplateParser {
    const val SCHEMA = 1
    private const val MAX_FILE = 256 * 1024
    val GLYPHS = setOf("shield", "home", "chart", "pulse", "play", "box", "server", "cloud", "globe", "lock", "film", "photo", "music", "download", "network", "database", "bell", "book", "gauge", "plug")
    private val ID = Regex("[a-z0-9][a-z0-9-]{0,40}")
    private val NAME_ID = Regex("[A-Za-z][A-Za-z0-9]{0,40}")
    private val SEMVER = Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")
    private val HEADER = Regex("[A-Za-z0-9-]{1,40}")
    private val FORBIDDEN_HEADERS = setOf("host", "user-agent", "accept", "accept-encoding", "connection", "content-length", "content-type",
        "transfer-encoding", "te", "trailer", "upgrade", "expect", "cookie", "keep-alive", "forwarded", "x-forwarded-for", "x-real-ip")
    private val SEGMENT = Regex("[A-Za-z0-9._~-]+")
    private val PLACEHOLDER = Regex("\\{(param:[A-Za-z][A-Za-z0-9]{0,40}|now|now-\\d{1,4}[smhd]|iso:now|iso:now-\\d{1,4}[smhd])}")
    private val QUERY_KEY = Regex("[A-Za-z0-9_.\\[\\]-]{1,40}")
    private val QUERY_VALUE = Regex("[A-Za-z0-9 ._~,:+-]{0,100}")
    private val DURATION = Regex("(\\d{1,5})(s|m|h)")
    private val OPEN_LINK = Regex("/[A-Za-z0-9._~/?&=%#:+,-]*")
    private val RESERVED_REQUESTS = setOf("item", "probe")
    private val LEVEL_NAMES = mapOf("good" to Level.Good, "info" to Level.Info, "warn" to Level.Warn, "bad" to Level.Bad, "neutral" to Level.Neutral)
    private val json = Json { isLenient = false }

    fun parse(file: String, text: String): ServiceTemplate {
        if (text.length > MAX_FILE) throw TemplateError(file, listOf("The file is larger than ${MAX_FILE / 1024} KiB."))
        val root = try {
            requireJsonDepth(text, 32)
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw TemplateError(file, listOf("The file is not valid JSON: ${e.message?.lineSequence()?.firstOrNull()?.take(200)}"))
        }
        val r = Reader(file)
        val template = r.template(root)
        absoluteUrls(root, "", r)
        if (r.problems.isNotEmpty() || template == null) throw TemplateError(file, r.problems.ifEmpty { listOf("The template could not be read.") })
        return template
    }

    /** Absolute URLs are allowed only in `sources` and `links.help`; every request and deep link is relative to the link's origin. */
    private fun absoluteUrls(e: JsonElement, path: String, r: Reader) {
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> absoluteUrls(v, if (path.isEmpty()) k else "$path.$k", r) }
            is JsonArray -> e.forEachIndexed { i, v -> absoluteUrls(v, "$path[$i]", r) }
            is JsonPrimitive -> if (e.isString && "://" in e.content && !path.startsWith("sources[") && !path.endsWith("links.help"))
                r.problems += "$path: an absolute URL is not allowed here; requests and links are paths on the service's own address."
        }
    }

    private class Reader(val file: String) {
        val problems = mutableListOf<String>()
        /** Parameters of the variant being read, so `param("id")` can be checked. */
        var paramIds: Set<String> = emptySet()
        fun bad(at: String, message: String): Nothing? { problems += "$at: $message"; return null }

        inner class Obj(val at: String, val o: JsonObject) {
            fun keys(vararg allowed: String) {
                for (k in o.keys) if (k !in allowed) {
                    val hint = when {
                        k == "method" -> " Requests are GET only; a template cannot choose another method."
                        k == "body" && "path" in allowed -> " Only a session login has a body, and it is built from the credential fields."
                        else -> allowed.minByOrNull { distance(it, k) }?.takeIf { distance(it, k) <= 2 }?.let { " (did you mean '$it'?)" } ?: " Allowed: ${allowed.joinToString(", ")}."
                    }
                    problems += "$at: unknown key '$k'.$hint"
                }
            }
            fun has(k: String) = k in o
            fun str(k: String, required: Boolean = true, max: Int = 200): String? {
                val v = o[k] ?: return if (required) bad(at, "'$k' is missing.") else null
                val p = (v as? JsonPrimitive)?.takeIf { it.isString } ?: return bad("$at.$k", "must be text in quotes.")
                if (p.content.length > max) return bad("$at.$k", "is longer than $max characters.")
                if (p.content.any { it.isISOControl() }) return bad("$at.$k", "contains a control character.")
                return p.content
            }
            fun int(k: String, range: IntRange, required: Boolean = true): Int? {
                val v = o[k] ?: return if (required) bad(at, "'$k' is missing.") else null
                val n = (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return bad("$at.$k", "must be a whole number.")
                if (n !in range) return bad("$at.$k", "must be between ${range.first} and ${range.last}, not $n.")
                return n
            }
            fun dbl(k: String): Double? {
                val v = o[k] ?: return null
                val d = (v as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return bad("$at.$k", "must be a number.")
                return d
            }
            fun bool(k: String, default: Boolean? = false): Boolean? {
                val v = o[k] ?: return default ?: bad(at, "'$k' is missing (true or false).")
                return (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: bad("$at.$k", "must be true or false.")
            }
            fun obj(k: String, required: Boolean = true): Obj? {
                val v = o[k] ?: return if (required) bad(at, "'$k' is missing.") else null
                return (v as? JsonObject)?.let { Obj("$at.$k", it) } ?: bad("$at.$k", "must be an object { \u2026 }.")
            }
            fun arr(k: String, required: Boolean = true, max: Int = 64): List<JsonElement>? {
                val v = o[k] ?: return if (required) bad(at, "'$k' is missing.") else null
                val a = v as? JsonArray ?: return bad("$at.$k", "must be a list [ \u2026 ].")
                if (a.size > max) return bad("$at.$k", "has more than $max entries.")
                return a
            }
            fun strings(k: String, required: Boolean = true, max: Int = 64, each: Int = 300): List<String>? =
                arr(k, required, max)?.mapIndexed { i, e -> ((e as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= each }) ?: return bad("$at.$k[$i]", "must be text in quotes (at most $each characters).") }
            fun stringMap(k: String, required: Boolean = false): Map<String, String>? {
                val m = obj(k, required) ?: return if (required) null else emptyMap()
                return m.o.mapValues { (key, v) -> ((v as? JsonPrimitive)?.takeIf { it.isString }?.content) ?: return bad("${m.at}.$key", "must be text in quotes.") }
            }
            fun objects(k: String, required: Boolean = true, max: Int = 64): List<Obj>? =
                arr(k, required, max)?.mapIndexed { i, e -> (e as? JsonObject)?.let { Obj("$at.$k[$i]", it) } ?: return bad("$at.$k[$i]", "must be an object { \u2026 }.") }
        }

        fun template(root: JsonElement): ServiceTemplate? {
            val t = Obj("template", root as? JsonObject ?: return bad("template", "The file must hold one JSON object."))
            t.keys("schema", "id", "templateVersion", "name", "glyph", "tags", "sources", "detect", "variants")
            val schema = t.int("schema", 1..1000)
            if (schema != null && schema != SCHEMA) bad("template.schema", if (schema > SCHEMA) "schema $schema is newer than this RingMASTER understands ($SCHEMA)." else "schema must be $SCHEMA.")
            val id = t.str("id", max = 41)?.also { if (!ID.matches(it)) bad("template.id", "'$it' must be lower-case letters, digits and -.") }
            val version = t.str("templateVersion", max = 20)?.also { if (!SEMVER.matches(it)) bad("template.templateVersion", "'$it' must look like 1.0.0.") }
            val name = t.str("name", max = 40)
            val glyph = t.str("glyph", max = 20)?.also { if (it !in GLYPHS) bad("template.glyph", "'$it' is not one of the app's glyphs: ${GLYPHS.sorted().joinToString(", ")}.") }
            val tags = t.strings("tags", required = false, max = 8, each = 20).orEmpty()
            val sources = t.strings("sources", max = 16, each = 300).orEmpty()
            sources.forEachIndexed { i, s -> if (!s.startsWith("https://")) bad("template.sources[$i]", "a source is an https:// address of the documentation.") }
            val detect = t.obj("detect")?.let(::detect)
            val variantObjects = t.objects("variants", max = 8)
            if (variantObjects != null && variantObjects.isEmpty()) bad("template.variants", "needs at least one variant.")
            val variants = variantObjects.orEmpty().mapNotNull(::variant)
            variants.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { bad("template.variants", "variant id '$it' is used twice.") }
            if (variants.isNotEmpty() && variants.all { it.unsupported != null }) bad("template.variants", "at least one variant must be readable (not 'unsupported').")
            if (schema == null || id == null || version == null || name == null || glyph == null || detect == null) return null
            return ServiceTemplate(schema, id, version, name, glyph, tags, sources, detect, variants)
        }

        fun detect(d: Obj): TemplateDetect? {
            d.keys("catalogue", "helperScripts", "ports")
            val catalogue = d.strings("catalogue", max = 8, each = 60).orEmpty()
            catalogue.forEachIndexed { i, c -> if (SERVICE_CATALOGUE.none { it.id == c }) bad("${d.at}.catalogue[$i]", "'$c' is not an id in SERVICE_CATALOGUE (ServiceCatalog.kt).") }
            val helper = d.strings("helperScripts", required = false, max = 8, each = 60).orEmpty()
            val ports = d.objects("ports", max = 8).orEmpty().mapNotNull { p ->
                p.keys("port", "secure", "path")
                val port = p.int("port", 1..65535); val secure = p.bool("secure", null); val path = p.str("path", required = false) ?: "/"
                if (!path.startsWith("/") || ServiceOrigin.cleanBase(path) == null) bad("${p.at}.path", "'$path' must be a base path such as /admin.")
                if (port == null || secure == null) null else TemplatePort(port, secure, path)
            }
            if (ports.isEmpty()) bad(d.at, "'ports' needs at least one port.")
            return TemplateDetect(catalogue, helper, ports)
        }

        fun expr(at: String, text: String?, scope: Set<String>, itemField: Boolean = false): Expr? {
            text ?: return null
            val node = try { TemplateExpr.parse(text, itemField) } catch (e: ExprError) { return bad(at, "'${text.take(80)}': ${e.message}") }
            TemplateExpr.requests(node).filter { it !in scope }.forEach { return bad(at, "'${text.take(80)}' reads @$it, which is not a declared request${if (scope.isEmpty()) "" else " (declared: ${scope.sorted().joinToString(", ")})"}.") }
            if (!itemField && TemplateExpr.usesItem(node)) return bad(at, "'${text.take(80)}' uses @item outside a list, grid or series item.")
            TemplateExpr.params(node).filter { it !in paramIds }.forEach { return bad(at, "'${text.take(80)}' reads param(\"$it\"), which is not a declared parameter.") }
            return Expr(text, node)
        }

        fun path(at: String, p: String?, params: Set<String>, allowPlaceholders: Boolean = true): String? {
            p ?: return null
            if (!p.startsWith("/") || p.startsWith("//")) return bad(at, "'$p' must start with a single /.")
            if ('?' in p || '#' in p) return bad(at, "'$p' must not hold a query or fragment; use 'query'.")
            val segments = p.substring(1).split('/')
            segments.forEachIndexed { i, s ->
                val last = i == segments.lastIndex
                when {
                    s.isEmpty() && last -> {}
                    s == "." || s == ".." -> return bad(at, "'$p' must not use . or .. segments.")
                    PLACEHOLDER.matches(s) && allowPlaceholders -> {
                        val name = s.removePrefix("{").removeSuffix("}")
                        if (name.startsWith("param:") && name.removePrefix("param:") !in params) return bad(at, "'$p' names {$name}, which is not a declared parameter.")
                        if (name.startsWith("param:") && !last) return bad(at, "'$p': a parameter may only fill the last segment.")
                    }
                    !SEGMENT.matches(s) -> return bad(at, "'$p': segment '$s' may hold letters, digits and . _ ~ - only.")
                }
            }
            return p
        }

        fun query(at: String, q: Map<String, String>?, params: Set<String>): Map<String, String>? {
            q ?: return emptyMap()
            if (q.size > 16) return bad(at, "has more than 16 query parameters.")
            for ((k, v) in q) {
                if (!QUERY_KEY.matches(k)) return bad("$at.$k", "'$k' is not a plain query name.")
                if (v.startsWith("{")) {
                    if (!PLACEHOLDER.matches(v)) return bad("$at.$k", "'$v' is not a known placeholder ({param:id}, {now}, {now-24h}, {iso:now-24h}).")
                    if (v.startsWith("{param:") && v.removePrefix("{param:").removeSuffix("}") !in params) return bad("$at.$k", "'$v' names a parameter that is not declared.")
                } else if (!QUERY_VALUE.matches(v)) return bad("$at.$k", "'$v' may hold letters, digits, spaces and . _ ~ , : + - only.")
            }
            return q
        }

        fun duration(at: String, text: String?): Duration? {
            text ?: return null
            val m = DURATION.matchEntire(text) ?: return bad(at, "'$text' is not a duration such as 30s, 5m or 6h.")
            val n = m.groupValues[1].toLong()
            return when (m.groupValues[2]) { "s" -> Duration.ofSeconds(n); "m" -> Duration.ofMinutes(n); else -> Duration.ofHours(n) }
        }

        fun unit(at: String, text: String?): ReadingUnit? {
            text ?: return null
            return ReadingUnit.of(text) ?: bad(at, "'$text' is not a unit. Use one of ${ReadingUnit.ids.joinToString(", ")}.")
        }

        fun states(o: Obj, key: String): Map<String, Level>? {
            val m = o.stringMap(key) ?: return null
            return m.mapValues { (k, v) -> LEVEL_NAMES[v] ?: return bad("${o.at}.$key.$k", "'$v' must be good, info, warn, bad or neutral.") }
        }

        fun header(at: String, name: String?): String? {
            name ?: return null
            if (!HEADER.matches(name) || name.lowercase() in FORBIDDEN_HEADERS || name.lowercase().startsWith("proxy-")) return bad(at, "'$name' is not a header a template may send.")
            return name
        }

        fun pointer(at: String, text: String?): List<String>? {
            text ?: return null
            if (!text.startsWith("/") || "*" in text.split('/')) return bad(at, "'$text' must be a JSON pointer such as /session/sid.")
            return text.substring(1).split('/').map { it.replace("~1", "/").replace("~0", "~") }
        }

        fun openLink(at: String, text: String?): String? {
            text ?: return null
            if (!OPEN_LINK.matches(text) || text.startsWith("//") || text.split('/', '?', '#').any { it == ".." }) return bad(at, "'$text' must be a path on the service's own address, such as /admin/.")
            return text
        }

        fun variant(v: Obj): TemplateVariant? {
            val unsupported = v.has("unsupported")
            if (unsupported) v.keys("id", "label", "probe", "unsupported")
            else v.keys("id", "label", "probe", "auth", "params", "requests", "readings", "series", "lists", "grids", "panel", "compact", "links")
            val id = v.str("id", max = 30)?.also { if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,29}").matches(it)) bad("${v.at}.id", "'$it' must be letters, digits and . _ -.") }
            val label = v.str("label", max = 60)
            val probe = v.obj("probe")?.let(::probe)
            if (unsupported) {
                val message = v.str("unsupported", max = 300)
                if (id == null || label == null || probe == null || message == null) return null
                return TemplateVariant(id, label, probe, message, AuthSpec(AuthScheme.None), emptyList(), emptyMap(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null, null)
            }
            val paramObjects = v.objects("params", required = false, max = 8).orEmpty()
            val paramIds = paramObjects.mapNotNull { (it.o["id"] as? JsonPrimitive)?.content }.toSet()
            this.paramIds = paramIds
            val requestsObj = v.obj("requests")
            val requestIds = requestsObj?.o?.keys.orEmpty()
            requestIds.forEach { if (!NAME_ID.matches(it) || it in RESERVED_REQUESTS) bad("${requestsObj!!.at}.$it", "'$it' is not a usable request name (letters and digits; not item or probe).") }
            if (requestsObj != null && requestIds.isEmpty()) bad(requestsObj.at, "needs at least one request.")
            if (requestIds.size > 16) bad(requestsObj!!.at, "has more than 16 requests.")
            val auth = v.obj("auth")?.let(::auth)
            val params = paramObjects.mapNotNull { param(it, requestIds) }
            val requests = requestsObj?.o?.mapNotNull { (rid, value) -> (value as? JsonObject)?.let { request(Obj("${requestsObj.at}.$rid", it), rid, paramIds) } ?: bad("${requestsObj.at}.$rid", "must be an object { \u2026 }.") }?.associateBy { it.id }.orEmpty()
            val readings = v.objects("readings", max = 64).orEmpty().mapNotNull { reading(it, requestIds) }
            dupes(v.at + ".readings", readings.map { it.id })
            if (readings.none { it.required }) bad("${v.at}.readings", "at least one reading must be 'required': true, so a changed API is noticed.")
            val readingIds = readings.map { it.id }.toSet()
            val series = v.objects("series", required = false, max = 16).orEmpty().mapNotNull { series(it, requestIds, readingIds) }
            val lists = v.objects("lists", required = false, max = 16).orEmpty().mapNotNull { list(it, requestIds) }
            val grids = v.objects("grids", required = false, max = 8).orEmpty().mapNotNull { grid(it, requestIds) }
            dupes(v.at + ".series/lists/grids", series.map { it.id } + lists.map { it.id } + grids.map { it.id })
            val seriesLines = series.associate { s -> s.id to (if (s is SeriesSpec.Fixed) s.values.keys else emptySet()) }
            val panel = v.objects("panel", max = 24).orEmpty().mapNotNull { block(it, readingIds, seriesLines, lists.map { l -> l.id }.toSet(), grids.map { g -> g.id }.toSet(), requestIds) }
            if (panel.isEmpty()) bad("${v.at}.panel", "needs at least one block.")
            val compact = v.obj("compact", required = false)?.let { compact(it, readingIds, seriesLines) }
            val links = v.obj("links", required = false)?.let { l ->
                l.keys("home", "help")
                val home = openLink("${l.at}.home", l.str("home")); val help = l.str("help", required = false, max = 300)
                if (help != null && !help.startsWith("https://")) bad("${l.at}.help", "the help link is an https:// address of the vendor's documentation.")
                home?.let { LinksSpec(it, help) }
            }
            readings.filter { it.required }.forEach { r -> r.expr.requests.filter { requests[it]?.optional == true }.forEach {
                bad("${v.at}.readings", "required reading '${r.id}' reads the optional request '$it'; a required reading needs a request that is not optional.") } }
            if (id == null || label == null || probe == null || auth == null || requestsObj == null) return null
            val variant = TemplateVariant(id, label, probe, null, auth, params, requests, readings, series, lists, grids, panel, compact, links)
            val read = variant.readingRequests.values.flatten() + series.flatMap(variant::requestsOfSeries) + lists.flatMap(variant::requestsOfList) + grids.flatMap(variant::requestsOfGrid) +
                panel.filterIsInstance<PanelBlock.Notice>().flatMap { it.whenExpr.requests } + params.mapNotNull { it.choices?.request }
            requests.keys.filter { it !in read }.forEach { bad("${requestsObj.at}.$it", "nothing reads this request; remove it or read it.") }
            return variant
        }

        fun dupes(at: String, ids: List<String>) = ids.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { bad(at, "id '$it' is used twice.") }

        fun probe(p: Obj): TemplateProbe? {
            p.keys("path", "query", "accept", "expect", "kind")
            val path = path("${p.at}.path", p.str("path"), emptySet(), allowPlaceholders = false)
            val query = query("${p.at}.query", p.stringMap("query"), emptySet())
            val accept = p.arr("accept", max = 8)?.mapIndexed { i, e -> (e as? JsonPrimitive)?.intOrNull?.takeIf { it in 100..599 } ?: return bad("${p.at}.accept[$i]", "must be an HTTP status such as 200.") }?.toSet()
            if (accept != null && accept.isEmpty()) bad("${p.at}.accept", "needs at least one status.")
            val expect = p.str("expect", required = false, max = 512)?.let { expr("${p.at}.expect", it, setOf("probe")) }
            val kind = when (val k = p.str("kind", required = false) ?: "json") { "json" -> ResponseKind.Json; "text" -> ResponseKind.Text; else -> bad("${p.at}.kind", "'$k' must be json or text.") }
            if (path == null || query == null || accept == null || kind == null) return null
            return TemplateProbe(path, query, accept, expect, kind)
        }

        fun auth(a: Obj): AuthSpec? {
            val schemeText = a.str("scheme") ?: return null
            val scheme = AuthScheme.of(schemeText) ?: return bad("${a.at}.scheme", "'$schemeText' is not a scheme. Use none, header-key, header-template, basic or session-password.")
            val common = arrayOf("scheme", "fields", "ask", "power", "fullPower", "readOnly", "where", "errors")
            when (scheme) {
                AuthScheme.None -> a.keys("scheme", "ask", "where")
                AuthScheme.HeaderKey -> a.keys(*common, "header", "prefix")
                AuthScheme.HeaderTemplate -> a.keys(*common, "header", "format")
                AuthScheme.Basic -> a.keys(*common, "fixedUser")
                AuthScheme.SessionPassword -> a.keys(*common, "login", "logout")
            }
            if (scheme == AuthScheme.None) return AuthSpec(scheme, ask = a.str("ask", required = false, max = 200).orEmpty(), where = a.strings("where", required = false, max = 8).orEmpty())
            val fields = a.objects("fields", max = 3).orEmpty().mapNotNull { f ->
                f.keys("id", "label", "secret")
                val fid = f.str("id", max = 20); val flabel = f.str("label", max = 60); val secret = f.bool("secret", null)
                if (fid != null && !NAME_ID.matches(fid)) bad("${f.at}.id", "'$fid' must be letters and digits.")
                if (fid == null || flabel == null || secret == null) null else AuthField(fid, flabel, secret)
            }
            if (fields.count { it.secret } != 1) bad("${a.at}.fields", "needs exactly one field with \"secret\": true.")
            dupes("${a.at}.fields", fields.map { it.id })
            val ask = a.str("ask", max = 120)
            val power = a.str("power", max = 400)
            val fullPower = a.bool("fullPower", null)
            val errors = a.stringMap("errors").orEmpty()
            errors.keys.forEach { if (it !in setOf("401", "403", "429", "totp")) bad("${a.at}.errors.$it", "error messages are for 401, 403, 429 or totp.") }
            val header = when (scheme) { AuthScheme.HeaderKey, AuthScheme.HeaderTemplate -> header("${a.at}.header", a.str("header")); else -> null }
            val format = if (scheme == AuthScheme.HeaderTemplate) a.str("format", max = 200)?.also {
                if ("{secret}" !in it) bad("${a.at}.format", "must contain {secret}.")
                Regex("\\{([a-z]+)}").findAll(it).map { m -> m.groupValues[1] }.filter { n -> n != "secret" && fields.none { f -> f.id == n && !f.secret } }
                    .forEach { n -> bad("${a.at}.format", "{$n} is not a non-secret field.") }
            } else null
            val login = if (scheme == AuthScheme.SessionPassword) a.obj("login")?.let { l ->
                l.keys("path", "body", "token", "validity", "totp", "sendAs", "minIntervalSeconds", "extendsOnUse")
                val lp = path("${l.at}.path", l.str("path"), emptySet(), allowPlaceholders = false)
                val body = l.stringMap("body", required = true)
                body?.forEach { (k, fid) ->
                    if (!NAME_ID.matches(k)) bad("${l.at}.body.$k", "'$k' must be a plain JSON key.")
                    if (fields.none { it.id == fid }) bad("${l.at}.body.$k", "'$fid' is not a credential field; the login body is built only from the credential fields.")
                }
                if (body != null && body.values.none { fid -> fields.any { it.id == fid && it.secret } }) bad("${l.at}.body", "must send the secret field.")
                val token = pointer("${l.at}.token", l.str("token")); val validity = pointer("${l.at}.validity", l.str("validity", required = false))
                val totp = pointer("${l.at}.totp", l.str("totp", required = false))
                val sendAs = header("${l.at}.sendAs", l.str("sendAs"))
                val interval = l.int("minIntervalSeconds", 10..3600, required = false) ?: 60
                val extends = l.bool("extendsOnUse") ?: false
                if (lp == null || body == null || token == null || sendAs == null) null else LoginSpec(lp, body, token, validity, totp, sendAs, interval.toLong(), extends)
            } else null
            val logout = if (scheme == AuthScheme.SessionPassword) a.obj("logout")?.let { l -> l.keys("path"); path("${l.at}.path", l.str("path"), emptySet(), allowPlaceholders = false) } else null
            if (scheme == AuthScheme.SessionPassword && login != null && logout == null) bad(a.at, "a session login needs a 'logout' path so the session is dropped on unlink.")
            if (ask == null || power == null || fullPower == null) return null
            return AuthSpec(scheme, header, a.str("prefix", required = false, max = 40) ?: "", format, a.str("fixedUser", required = false, max = 60), fields, ask, power, fullPower,
                a.str("readOnly", required = false, max = 200), a.strings("where", max = 8).orEmpty(), errors, login, logout)
        }

        fun param(p: Obj, requests: Set<String>): ParamSpec? {
            p.keys("id", "label", "kind", "max", "pattern", "choices")
            val id = p.str("id", max = 40)?.also { if (!NAME_ID.matches(it)) bad("${p.at}.id", "'$it' must be letters and digits.") }
            val label = p.str("label", max = 80)
            val kind = when (val k = p.str("kind") ?: return null) { "text" -> ParamKind.Text; "choice" -> ParamKind.Choice; "multi" -> ParamKind.Multi; else -> bad("${p.at}.kind", "'$k' must be text, choice or multi.") }
            val max = p.int("max", 1..16, required = kind == ParamKind.Multi) ?: 1
            val patternText = p.str("pattern", max = 120)
            val pattern = patternText?.let { runCatching { Regex(it) }.getOrNull() ?: bad("${p.at}.pattern", "'$it' is not a valid pattern.") }
            if (patternText != null && (!patternText.startsWith("^") || !patternText.endsWith("$"))) bad("${p.at}.pattern", "must be anchored with ^ and $.")
            val choices = p.obj("choices", required = false)?.let { c ->
                c.keys("request", "items", "value", "label")
                val req = c.str("request")?.also { if (it !in requests) bad("${c.at}.request", "'$it' is not a declared request.") }
                val items = expr("${c.at}.items", c.str("items", max = 512), requests)
                val value = expr("${c.at}.value", c.str("value"), requests, itemField = true)
                val clabel = expr("${c.at}.label", c.str("label"), requests, itemField = true)
                if (req == null || items == null || value == null || clabel == null) null else ChoiceSpec(req, items, value, clabel)
            }
            if (id == null || label == null || kind == null || pattern == null) return null
            return ParamSpec(id, label, kind, max, pattern, choices)
        }

        fun request(r: Obj, id: String, params: Set<String>): RequestSpec? {
            r.keys("path", "query", "every", "minEvery", "maxBytes", "kind", "optional", "errors")
            val path = path("${r.at}.path", r.str("path"), params)
            val query = query("${r.at}.query", r.stringMap("query"), params)
            val every = duration("${r.at}.every", r.str("every"))
            val minEvery = r.str("minEvery", required = false)?.let { duration("${r.at}.minEvery", it) } ?: every
            if (every != null && minEvery != null) {
                if (minEvery < Duration.ofSeconds(5)) bad("${r.at}.minEvery", "must be at least 5s.")
                if (every < minEvery) bad("${r.at}.every", "is shorter than minEvery.")
                if (every > Duration.ofHours(24)) bad("${r.at}.every", "must be at most 24h.")
            }
            val maxBytes = r.int("maxBytes", 256..SERVICE_ANSWER_DEFAULT, required = false) ?: SERVICE_ANSWER_DEFAULT
            val kind = when (val k = r.str("kind", required = false) ?: "json") { "json" -> ResponseKind.Json; "text" -> ResponseKind.Text; else -> bad("${r.at}.kind", "'$k' must be json or text.") }
            val optional = r.bool("optional") ?: false
            val errors = r.stringMap("errors").orEmpty()
            errors.keys.forEach { if (it.toIntOrNull() !in 400..599) bad("${r.at}.errors.$it", "error messages are keyed by an HTTP status 400 to 599.") }
            if (path == null || query == null || every == null || minEvery == null || kind == null) return null
            return RequestSpec(id, path, query, every, minEvery, maxBytes, kind, optional, errors)
        }

        fun reading(o: Obj, requests: Set<String>): ReadingSpec? {
            o.keys("id", "label", "expr", "unit", "required", "identity", "levels", "states", "help")
            val id = o.str("id", max = 40)?.also { if (!NAME_ID.matches(it)) bad("${o.at}.id", "'$it' must be letters and digits.") }
            val label = o.str("label", max = 60)
            val expr = expr("${o.at}.expr", o.str("expr", max = 512), requests)
            val unit = unit("${o.at}.unit", o.str("unit"))
            val levels = o.obj("levels", required = false)?.let { l ->
                l.keys("warnAbove", "badAbove", "warnBelow", "badBelow")
                if (unit != null && !unit.numeric) bad(l.at, "levels are for numeric units; use 'states' for a ${unit.id} reading.")
                Levels(l.dbl("warnAbove"), l.dbl("badAbove"), l.dbl("warnBelow"), l.dbl("badBelow"))
            }
            val states = states(o, "states").orEmpty()
            if (states.isNotEmpty() && unit != ReadingUnit.State) bad("${o.at}.states", "states are for a reading with unit state.")
            if (id == null || label == null || expr == null || unit == null) return null
            return ReadingSpec(id, label, expr, unit, o.bool("required") ?: false, o.bool("identity") ?: false, levels, states, o.str("help", required = false, max = 300))
        }

        fun series(o: Obj, requests: Set<String>, readings: Set<String>): SeriesSpec? {
            val id = o.str("id", max = 40); val label = o.str("label", max = 60)
            return when {
                o.has("sampled") -> {
                    o.keys("id", "label", "unit", "sampled", "keep")
                    val reading = o.str("sampled")?.also { if (it !in readings) bad("${o.at}.sampled", "'$it' is not a reading.") }
                    val keep = o.int("keep", 2..2016) ?: return null
                    val unit = unit("${o.at}.unit", o.str("unit"))
                    if (id == null || label == null || reading == null || unit == null) null else SeriesSpec.Sampled(id, label, unit, reading, keep)
                }
                o.has("each") -> {
                    o.keys("id", "label", "unit", "each", "name", "time", "value", "max", "identity")
                    val each = expr("${o.at}.each", o.str("each", max = 512), requests)
                    val name = expr("${o.at}.name", o.str("name", max = 512), requests, itemField = true)
                    val time = expr("${o.at}.time", o.str("time", max = 512), requests, itemField = true)
                    val value = expr("${o.at}.value", o.str("value", max = 512), requests, itemField = true)
                    val unit = unit("${o.at}.unit", o.str("unit"))
                    val max = o.int("max", 1..8, required = false) ?: 3
                    if (id == null || label == null || each == null || name == null || time == null || value == null || unit == null) null else SeriesSpec.Each(id, label, unit, each, name, time, value, max, o.bool("identity") ?: false)
                }
                else -> {
                    o.keys("id", "label", "unit", "time", "values")
                    val time = expr("${o.at}.time", o.str("time", max = 512), requests)
                    val values = o.stringMap("values", required = true)?.let { m ->
                        if (m.isEmpty() || m.size > 6) bad("${o.at}.values", "needs 1 to 6 lines.")
                        m.mapValues { (k, e) -> expr("${o.at}.values.$k", e, requests) ?: return null }
                    }
                    val unit = unit("${o.at}.unit", o.str("unit"))
                    if (id == null || label == null || time == null || values == null || unit == null) null else SeriesSpec.Fixed(id, label, unit, time, values)
                }
            }
        }

        fun list(o: Obj, requests: Set<String>): ListSpec? {
            o.keys("id", "label", "items", "title", "subtitle", "value", "unit", "max", "identity", "open")
            val id = o.str("id", max = 40); val label = o.str("label", max = 60)
            val items = expr("${o.at}.items", o.str("items", max = 512), requests)
            val title = expr("${o.at}.title", o.str("title", max = 512), requests, true)
            val subtitle = o.str("subtitle", required = false, max = 512)?.let { expr("${o.at}.subtitle", it, requests, true) }
            val value = o.str("value", required = false, max = 512)?.let { expr("${o.at}.value", it, requests, true) }
            val unit = o.str("unit", required = value != null)?.let { unit("${o.at}.unit", it) } ?: ReadingUnit.Count
            val open = o.str("open", required = false, max = 512)?.let { expr("${o.at}.open", it, requests, true) }
            val max = o.int("max", 1..100, required = false) ?: 10
            if (id == null || label == null || items == null || title == null) return null
            return ListSpec(id, label, items, title, subtitle, value, unit, max, o.bool("identity") ?: false, open)
        }

        fun grid(o: Obj, requests: Set<String>): GridSpec? {
            o.keys("id", "label", "items", "title", "state", "states", "value", "unit", "max", "identity", "open")
            val id = o.str("id", max = 40); val label = o.str("label", max = 60)
            val items = expr("${o.at}.items", o.str("items", max = 512), requests)
            val title = expr("${o.at}.title", o.str("title", max = 512), requests, true)
            val state = expr("${o.at}.state", o.str("state", max = 512), requests, true)
            val states = states(o, "states").orEmpty()
            val value = o.str("value", required = false, max = 512)?.let { expr("${o.at}.value", it, requests, true) }
            val unit = o.str("unit", required = value != null)?.let { unit("${o.at}.unit", it) } ?: ReadingUnit.Text
            val open = o.str("open", required = false, max = 512)?.let { expr("${o.at}.open", it, requests, true) }
            val max = o.int("max", 1..200, required = false) ?: 48
            if (id == null || label == null || items == null || title == null || state == null) return null
            return GridSpec(id, label, items, title, state, states, value, unit, max, o.bool("identity") ?: false, open)
        }

        fun block(o: Obj, readings: Set<String>, series: Map<String, Set<String>>, lists: Set<String>, grids: Set<String>, requests: Set<String>): PanelBlock? {
            val kind = o.str("block") ?: return null
            fun reading(k: String, required: Boolean = true) = o.str(k, required)?.also { if (it !in readings) bad("${o.at}.$k", "'$it' is not a reading.") }
            fun spark(at: String, ref: String) {
                val sid = ref.substringBefore('.'); val line = ref.substringAfter('.', "")
                if (sid !in series) bad(at, "'$ref' is not a series.") else if (line.isNotEmpty() && series[sid]!!.isNotEmpty() && line !in series[sid]!!) bad(at, "'$ref': the series has no line '$line'.")
            }
            val open = openLink("${o.at}.open", o.str("open", required = false, max = 300))
            return when (kind) {
                "hero" -> { o.keys("block", "reading", "sub", "state", "caption", "zeroText", "open")
                    val r = reading("reading"); PanelBlock.Hero(r ?: return null, reading("sub", false), reading("state", false), o.str("caption", false, 80), o.str("zeroText", false, 60), open) }
                "tiles" -> { o.keys("block", "readings", "sparks", "open")
                    val list = o.strings("readings", max = 6).orEmpty(); list.forEach { if (it !in readings) bad("${o.at}.readings", "'$it' is not a reading.") }
                    if (list.isEmpty()) bad("${o.at}.readings", "needs 1 to 6 readings.")
                    val sparks = o.stringMap("sparks").orEmpty(); sparks.forEach { (k, v) -> if (k !in list) bad("${o.at}.sparks.$k", "'$k' is not one of the tile readings."); spark("${o.at}.sparks.$k", v) }
                    PanelBlock.Tiles(list, sparks, open) }
                "ring", "bar" -> { o.keys("block", "reading", "max", "open")
                    val r = reading("reading") ?: return null
                    val maxEl = o.o["max"] as? JsonPrimitive
                    val maxReading = maxEl?.takeIf { it.isString }?.content?.also { if (it !in readings) bad("${o.at}.max", "'$it' is not a reading.") }
                    val maxValue = maxEl?.takeIf { !it.isString }?.content?.toDoubleOrNull()
                    if (maxEl == null) bad(o.at, "'max' is missing (a number or a reading).")
                    PanelBlock.Gauge(kind == "ring", r, maxReading, maxValue, open) }
                "chart" -> { o.keys("block", "series", "kind", "height", "open")
                    val s = o.str("series")?.also { spark("${o.at}.series", it) } ?: return null
                    val k = o.str("kind")?.let { ChartKind.of(it) ?: bad("${o.at}.kind", "'$it' must be ${ChartKind.entries.joinToString(", ") { c -> c.id }}.") } ?: return null
                    PanelBlock.Chart(s, k, o.int("height", 60..400, required = false) ?: 140, open) }
                "breakdown" -> { o.keys("block", "list", "open"); val l = o.str("list")?.also { if (it !in lists) bad("${o.at}.list", "'$it' is not a list.") } ?: return null; PanelBlock.Breakdown(l, open) }
                "list" -> { o.keys("block", "list", "bars", "open"); val l = o.str("list")?.also { if (it !in lists) bad("${o.at}.list", "'$it' is not a list.") } ?: return null; PanelBlock.TopList(l, o.bool("bars") ?: false, open) }
                "grid" -> { o.keys("block", "grid", "collapsedWhenEmpty", "open"); val g = o.str("grid")?.also { if (it !in grids) bad("${o.at}.grid", "'$it' is not a grid.") } ?: return null; PanelBlock.Grid(g, o.bool("collapsedWhenEmpty") ?: false, open) }
                "facts" -> { o.keys("block", "readings", "open")
                    val list = o.strings("readings", max = 8).orEmpty(); list.forEach { if (it !in readings) bad("${o.at}.readings", "'$it' is not a reading.") }
                    PanelBlock.Facts(list, open) }
                "notice" -> { o.keys("block", "when", "text", "reading", "open")
                    val w = expr("${o.at}.when", o.str("when", max = 512), requests); val text = o.str("text", max = 200)
                    text?.let { VariantFields.notice(it).forEach { f -> if (f !in readings) bad("${o.at}.text", "{$f} is not a reading.") } }
                    if (w == null || text == null) null else PanelBlock.Notice(w, text, reading("reading", false), open) }
                else -> bad("${o.at}.block", "'$kind' is not a block. Use hero, tiles, ring, bar, chart, breakdown, list, grid, facts or notice.")
            }
        }

        fun compact(c: Obj, readings: Set<String>, series: Map<String, Set<String>>): CompactSpec {
            c.keys("pin", "ticker")
            fun r(o: Obj, k: String, required: Boolean = true) = o.str(k, required)?.also { if (it !in readings) bad("${o.at}.$k", "'$it' is not a reading.") }
            val pin = c.obj("pin", required = false)?.let { p -> p.keys("reading", "state", "zeroText"); r(p, "reading")?.let { CompactPin(it, r(p, "state", false), p.str("zeroText", false, 40)) } }
            val ticker = c.obj("ticker", required = false)?.let { t ->
                t.keys("label", "reading", "spark", "state")
                val spark = t.str("spark", false)?.also { if (it.substringBefore('.') !in series) bad("${t.at}.spark", "'$it' is not a series.") }
                val label = t.str("label", max = 20); val reading = r(t, "reading")
                if (label == null || reading == null) null else CompactTicker(label, reading, spark, r(t, "state", false))
            }
            return CompactSpec(pin, ticker)
        }
    }

    private object VariantFields { fun notice(text: String) = TemplateVariant.NOTICE_FIELD.findAll(text).map { it.groupValues[1] }.toList() }

    /** Levenshtein distance for "did you mean". */
    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }
}

/** The templates that ship with the app, read from resources at start. A broken one is a build failure (the catalogue test). */
object ServiceTemplates {
    val BUILT_IN = listOf("pihole", "homeassistant")

    val all: List<ServiceTemplate> by lazy { BUILT_IN.map { load(it) } }

    fun byId(id: String): ServiceTemplate? = all.firstOrNull { it.id == id }

    fun load(id: String): ServiceTemplate {
        val text = ServiceTemplates::class.java.getResourceAsStream("/service-templates/$id.json")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw TemplateError("$id.json", listOf("The template file is missing from the app."))
        val template = ServiceTemplateParser.parse("$id.json", text)
        if (template.id != id) throw TemplateError("$id.json", listOf("template.id is '${template.id}', but the file is $id.json."))
        return template
    }

    /** Templates that serve a catalogue service (ServiceCatalog.kt id). */
    fun forCatalogue(catalogueId: String) = all.filter { catalogueId in it.detect.catalogue }
}
