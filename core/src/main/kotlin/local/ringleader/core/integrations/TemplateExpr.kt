package local.ringleader.core.integrations

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Why a value is not there. Every evaluation failure is one of these, never an exception and never a guess. */
enum class UnavailableKind { NoAnswer, Missing, Null, WrongType, DivisionByZero, OutOfRange, NotANumber, Empty, TooMany, ParamNotSet }

data class Unavailable(val kind: UnavailableKind, val detail: String)

/** A value in the expression language. JSON objects stay opaque and are reached only through pointers. */
sealed interface XValue {
    data class Num(val value: Double) : XValue
    data class Text(val value: String) : XValue
    data class Bool(val value: Boolean) : XValue
    data class Items(val items: List<XValue>) : XValue
    data class Obj(val json: JsonObject) : XValue
    data class Gone(val why: Unavailable) : XValue
}

internal fun gone(kind: UnavailableKind, detail: String) = XValue.Gone(Unavailable(kind, detail))

/** One response as the evaluator sees it. [headers] holds only [ServiceWire.READABLE_HEADERS]; [at] is when it was fetched. */
sealed interface ResponseValue {
    data class Json(val json: JsonElement, val headers: Map<String, String> = emptyMap(), val at: Instant? = null) : ResponseValue
    data class Text(val text: String, val headers: Map<String, String> = emptyMap(), val at: Instant? = null) : ResponseValue
    /** No usable answer: [reason] is one plain sentence. */
    data class Failed(val reason: String) : ResponseValue
}

/** What `rate` remembers of one expression: its value at the previous fetch, and the rate worked out then. */
data class RateSample(val at: Instant, val value: Double, val rate: Double?)

/**
 * The previous poll's samples, read by `rate`. Evaluation never changes [previous]; what it saw is collected in [next],
 * which the engine keeps for the following poll. Without a memory `rate` is "no data".
 */
class RateMemory(val previous: Map<String, RateSample> = emptyMap()) {
    val next: MutableMap<String, RateSample> = HashMap(previous)
}

/** Everything an expression can see: the declared responses, the current item, the link's parameters and the time. */
class ExprContext(
    val responses: Map<String, ResponseValue>,
    val params: Map<String, List<String>> = emptyMap(),
    val now: Instant = Instant.EPOCH,
    val item: XValue? = null,
    val rates: RateMemory? = null,
) {
    fun withItem(value: XValue) = ExprContext(responses, params, now, value, rates)
}

class ExprError(message: String) : IllegalArgumentException(message)

/**
 * The extraction language of service templates (design §4.2): RFC 6901 JSON Pointer plus a closed set of pure, total
 * functions. No variables, loops, lambdas, regular expressions or access to anything but the declared responses.
 *
 * ```
 * expr    := call | ref | number | string | true | false
 * call    := name '(' [expr (',' expr)*] ')'      name in FUNCTIONS, arity checked when parsed
 * ref     := '@' name pointer                     name = a declared request, or `item` inside a list, grid or series
 * pointer := ('/' segment)*                       RFC 6901 (~0 ~1); one '*' maps over an array or an object's values;
 *                                                 a negative index counts from the end (/-1 is the last element)
 * ```
 * A field that is only a pointer (`"/domain"`) means `@item/domain`. A missing path, a JSON null, a wrong type, a
 * division by zero or an out-of-range number gives [XValue.Gone] with its reason; functions pass it on.
 */
object TemplateExpr {
    const val MAX_CHARS = 512
    const val MAX_NESTING = 16
    const val MAX_LIST = 10_000
    const val MAX_TEXT = 200

    sealed interface Node
    data class Ref(val name: String, val segments: List<String>) : Node
    data class Lit(val value: XValue) : Node
    data class Call(val name: String, val args: List<Node>) : Node

    /** Arity (min..max) and which argument positions must be literal pointers. */
    private class Spec(val min: Int, val max: Int, val pointers: Set<Int> = emptySet(), val literalText: Set<Int> = emptySet())

    private val SPECS: Map<String, Spec> = mapOf(
        "sum" to Spec(1, 1), "count" to Spec(1, 1), "avg" to Spec(1, 1), "min" to Spec(1, 1), "max" to Spec(1, 1),
        "first" to Spec(1, 1), "last" to Spec(1, 1),
        "add" to Spec(2, 2), "sub" to Spec(2, 2), "mul" to Spec(2, 2), "div" to Spec(2, 2), "percent" to Spec(2, 2),
        "num" to Spec(1, 1), "round" to Spec(2, 2),
        "entries" to Spec(1, 1), "top" to Spec(3, 3, pointers = setOf(2)), "pick" to Spec(2, 2, pointers = setOf(1)),
        "count_eq" to Spec(3, 3, pointers = setOf(1)), "filter" to Spec(4, 4, pointers = setOf(1), literalText = setOf(2)),
        "coalesce" to Spec(1, 6), "has" to Spec(1, 1), "eq" to Spec(2, 2), "ne" to Spec(2, 2), "not" to Spec(1, 1),
        "and" to Spec(2, 4), "or" to Spec(2, 4), "if" to Spec(3, 3),
        "concat" to Spec(1, 4), "prefix_of" to Spec(2, 2), "lower" to Spec(1, 1),
        "time" to Spec(1, 1), "ago" to Spec(1, 1), "version_ge" to Spec(2, 2), "param" to Spec(1, 1, literalText = setOf(0)),
        "prom" to Spec(2, 6, literalText = setOf(1)), "prom_sum" to Spec(2, 6, literalText = setOf(1)),
        "rate" to Spec(1, 1), "header" to Spec(2, 2, literalText = setOf(1)),
    )

    /** The only callable names. */
    val FUNCTIONS: Set<String> get() = SPECS.keys
    private val FILTER_OPS = setOf("eq", "ne", "gt", "ge", "lt", "le", "prefix", "in", "has")

    /** Parses [text]; a bare pointer (`/a/b`) is read as `@item/a/b` when [itemField] is set. */
    fun parse(text: String, itemField: Boolean = false): Node {
        if (text.length > MAX_CHARS) throw ExprError("The expression is longer than $MAX_CHARS characters.")
        if (itemField && text.startsWith("/")) { val p = Parser("@item$text"); val node = p.ref(); p.end(); return node }
        val p = Parser(text)
        val node = p.expr(0)
        p.end()
        return node
    }

    /** Every request name the expression reads (not `item`). */
    fun requests(node: Node): Set<String> = when (node) {
        is Ref -> if (node.name == "item") emptySet() else setOf(node.name)
        is Lit -> emptySet()
        is Call -> node.args.flatMapTo(mutableSetOf()) { requests(it) }
    }

    fun usesItem(node: Node): Boolean = when (node) {
        is Ref -> node.name == "item"
        is Lit -> false
        is Call -> node.args.any(::usesItem)
    }

    /** Parameter ids named by `param("…")`. */
    fun params(node: Node): Set<String> = when (node) {
        is Call -> if (node.name == "param") setOfNotNull(((node.args[0] as Lit).value as? XValue.Text)?.value)
            else node.args.flatMapTo(mutableSetOf()) { params(it) }
        else -> emptySet()
    }

    private class Parser(val s: String) {
        var i = 0
        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun peek() = if (i < s.length) s[i] else '\u0000'
        fun end() { skip(); if (i != s.length) throw ExprError("Unexpected '${s[i]}' at position ${i + 1}.") }
        fun expect(c: Char) { skip(); if (peek() != c) throw ExprError(if (i >= s.length) "The expression ends early; '$c' expected." else "'$c' expected at position ${i + 1}, found '${s[i]}'."); i++ }

        fun expr(depth: Int): Node {
            if (depth > MAX_NESTING) throw ExprError("The expression is nested deeper than $MAX_NESTING.")
            skip()
            if (i >= s.length) throw ExprError("The expression ends early.")
            val c = s[i]
            return when {
                c == '@' -> ref()
                c == '"' -> Lit(XValue.Text(string()))
                c == '-' || c.isDigit() -> Lit(XValue.Num(number()))
                c.isLetter() -> {
                    val start = i
                    val name = word()
                    when (name) {
                        "true" -> Lit(XValue.Bool(true))
                        "false" -> Lit(XValue.Bool(false))
                        "null" -> throw ExprError("There is no null: use has(\u2026) to test whether a value is there.")
                        else -> {
                            val spec = SPECS[name] ?: throw ExprError("Unknown function '$name' at position ${start + 1}. Known: ${SPECS.keys.sorted().joinToString(", ")}.")
                            expect('(')
                            val args = mutableListOf<Node>()
                            skip()
                            if (peek() != ')') {
                                while (true) {
                                    args += expr(depth + 1); skip()
                                    if (peek() == ',') { i++; continue }
                                    break
                                }
                            }
                            expect(')')
                            if (args.size !in spec.min..spec.max) {
                                val want = if (spec.min == spec.max) "${spec.min}" else "${spec.min} to ${spec.max}"
                                throw ExprError("$name takes $want argument${if (spec.max == 1) "" else "s"}, not ${args.size}.")
                            }
                            for (p in spec.pointers) {
                                val text = ((args.getOrNull(p) as? Lit)?.value as? XValue.Text)?.value
                                if (text == null || (text.isNotEmpty() && !text.startsWith("/"))) throw ExprError("Argument ${p + 1} of $name is a pointer string such as \"/count\".")
                                if (text.split('/').count { it == "*" } > 0) throw ExprError("A pointer argument of $name cannot use *.")
                            }
                            for (p in spec.literalText) {
                                if (((args.getOrNull(p) as? Lit)?.value as? XValue.Text) == null) throw ExprError("Argument ${p + 1} of $name must be a text in quotes.")
                            }
                            if (name == "filter") {
                                val op = ((args[2] as Lit).value as XValue.Text).value
                                if (op !in FILTER_OPS) throw ExprError("filter has no operator '$op'. Use one of ${FILTER_OPS.joinToString(", ")}.")
                            }
                            if (name == "header") {
                                val from = args[0] as? Ref
                                if (from == null || from.segments.isNotEmpty() || from.name == "item") throw ExprError("Argument 1 of header is a request, such as @list.")
                                val wanted = ((args[1] as Lit).value as XValue.Text).value
                                if (wanted !in ServiceWire.READABLE_HEADERS) throw ExprError("header can read ${ServiceWire.READABLE_HEADERS.joinToString(", ")} only, not '$wanted'.")
                            }
                            if (name == "rate" && usesItem(args[0])) throw ExprError("rate works on a whole answer, not on @item.")
                            Call(name, args)
                        }
                    }
                }
                else -> throw ExprError("Unexpected '$c' at position ${i + 1}.")
            }
        }

        fun word(): String { val start = i; while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++; return s.substring(start, i) }

        fun number(): Double {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || ((s[i] == '+' || s[i] == '-') && (s[i - 1] == 'e' || s[i - 1] == 'E')))) i++
            val value = s.substring(start, i).toDoubleOrNull() ?: throw ExprError("Bad number at position ${start + 1}.")
            if (value.isInfinite() || value.isNaN()) throw ExprError("The number at position ${start + 1} is out of range.")
            return value
        }

        fun string(): String {
            i++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) throw ExprError("A text in quotes is not closed.")
                val c = s[i++]
                if (c == '"') break
                if (c == '\\') {
                    if (i >= s.length) throw ExprError("A text in quotes is not closed.")
                    out.append(s[i++])
                } else out.append(c)
            }
            return out.toString()
        }

        fun ref(): Ref {
            i++ // @
            val name = word()
            if (name.isEmpty() || !name[0].isLetter()) throw ExprError("A reference needs a request name after @ at position $i.")
            val segments = mutableListOf<String>()
            while (peek() == '/') {
                i++
                val start = i
                while (i < s.length && s[i] != '/' && s[i] != ',' && s[i] != ')' && !s[i].isWhitespace()) i++
                val raw = s.substring(start, i)
                if (raw.contains('~') && !Regex("(~[01]|[^~])*").matches(raw)) throw ExprError("A pointer segment has a bad ~ escape: '$raw'.")
                segments += raw.replace("~1", "/").replace("~0", "~")
            }
            if (segments.count { it == "*" } > 1) throw ExprError("A reference may use * once.")
            return Ref(name, segments)
        }
    }

    // ---- evaluation ----

    fun eval(node: Node, ctx: ExprContext): XValue = when (node) {
        is Lit -> node.value
        is Ref -> ref(node, ctx)
        is Call -> call(node, ctx)
    }

    private fun ref(node: Ref, ctx: ExprContext): XValue {
        val root: XValue = if (node.name == "item") ctx.item ?: gone(UnavailableKind.Missing, "There is no current item here.")
        else when (val r = ctx.responses[node.name]) {
            null -> gone(UnavailableKind.NoAnswer, "No answer from ${node.name} yet.")
            is ResponseValue.Failed -> gone(UnavailableKind.NoAnswer, r.reason)
            is ResponseValue.Text -> if (node.segments.isEmpty()) XValue.Text(r.text) else gone(UnavailableKind.WrongType, "${node.name} is text, not JSON.")
            is ResponseValue.Json -> return resolve(r.json, node.segments, node.name)
        }
        if (root is XValue.Gone || node.segments.isEmpty()) return root
        val json = when (root) {
            is XValue.Obj -> root.json
            is XValue.Items -> return resolveItems(root, node.segments, node.name)
            else -> return gone(UnavailableKind.WrongType, "${node.name} is ${typeName(root)}, so it has no /${node.segments.first()}.")
        }
        return resolve(json, node.segments, node.name)
    }

    private fun resolveItems(items: XValue.Items, segments: List<String>, where: String): XValue {
        val seg = segments.first()
        if (seg == "*") return XValue.Items(items.items.map { child -> descend(child, segments.drop(1), where) })
        val index = seg.toIntOrNull() ?: return gone(UnavailableKind.Missing, "$where is a list; /$seg is not an index.")
        val at = if (index < 0) items.items.size + index else index
        val child = items.items.getOrNull(at) ?: return gone(UnavailableKind.Missing, "$where has no item $index.")
        return descend(child, segments.drop(1), where)
    }

    private fun descend(value: XValue, rest: List<String>, where: String): XValue = when {
        rest.isEmpty() -> value
        value is XValue.Obj -> resolve(value.json, rest, where)
        value is XValue.Items -> resolveItems(value, rest, where)
        value is XValue.Gone -> value
        else -> gone(UnavailableKind.Missing, "$where has no /${rest.joinToString("/")}.")
    }

    /** Walks [segments] from [root]. A wildcard maps the rest of the pointer over every element or value. */
    fun resolve(root: JsonElement, segments: List<String>, where: String = "the answer"): XValue {
        var current: JsonElement = root
        segments.forEachIndexed { index, seg ->
            val path = "/" + segments.take(index + 1).joinToString("/")
            if (seg == "*") {
                val children = when (val c = current) {
                    is JsonArray -> c.toList()
                    is JsonObject -> c.values.toList()
                    else -> return gone(UnavailableKind.WrongType, "$where$path: * needs a list or an object.")
                }
                if (children.size > MAX_LIST) return gone(UnavailableKind.TooMany, "$where has more than $MAX_LIST items.")
                val rest = segments.drop(index + 1)
                return XValue.Items(children.map { resolve(it, rest, where) })
            }
            current = when (val c = current) {
                is JsonObject -> c[seg] ?: return gone(UnavailableKind.Missing, "$where has no $path.")
                is JsonArray -> {
                    val i = seg.toIntOrNull() ?: return gone(UnavailableKind.Missing, "$where has no $path (a list needs an index).")
                    c.getOrNull(if (i < 0) c.size + i else i) ?: return gone(UnavailableKind.Missing, "$where has no $path.")
                }
                else -> return gone(UnavailableKind.Missing, "$where has no $path.")
            }
        }
        return fromJson(current, if (segments.isEmpty()) where else "$where/" + segments.joinToString("/"))
    }

    fun fromJson(e: JsonElement, where: String = "the answer"): XValue = when (e) {
        JsonNull -> gone(UnavailableKind.Null, "$where is empty (null).")
        is JsonPrimitive -> when {
            e.isString -> XValue.Text(e.content)
            e.booleanOrNull != null -> XValue.Bool(e.booleanOrNull!!)
            else -> {
                val d = e.content.toDoubleOrNull()
                if (d == null) gone(UnavailableKind.NotANumber, "$where is not a number.")
                else if (d.isInfinite() || d.isNaN()) gone(UnavailableKind.OutOfRange, "$where is too large a number.")
                else XValue.Num(d)
            }
        }
        is JsonArray -> if (e.size > MAX_LIST) gone(UnavailableKind.TooMany, "$where has more than $MAX_LIST items.") else XValue.Items(e.map { fromJson(it, where) })
        is JsonObject -> XValue.Obj(e)
    }

    fun typeName(v: XValue) = when (v) {
        is XValue.Num -> "a number"; is XValue.Text -> "text"; is XValue.Bool -> "true/false"; is XValue.Items -> "a list"
        is XValue.Obj -> "an object"; is XValue.Gone -> "missing"
    }

    private fun wrong(fn: String, want: String, got: XValue) = gone(UnavailableKind.WrongType, "$fn needs $want, got ${typeName(got)}.")
    private fun num(x: Double): XValue = if (x.isNaN() || x.isInfinite()) gone(UnavailableKind.OutOfRange, "The result is out of range.") else XValue.Num(x)

    /** Numbers of a list. Missing elements are skipped; numeric text counts (Home Assistant states); other types are an error. */
    private fun numbers(fn: String, v: XValue): Pair<List<Double>?, XValue?> {
        if (v is XValue.Gone) return null to v
        val list = (v as? XValue.Items)?.items ?: return null to wrong(fn, "a list", v)
        val out = ArrayList<Double>(list.size)
        for (e in list) when (e) {
            is XValue.Num -> out += e.value
            is XValue.Gone -> {}
            is XValue.Text -> out += (e.value.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null to wrong(fn, "a list of numbers", e))
            else -> return null to wrong(fn, "a list of numbers", e)
        }
        return out to null
    }

    private fun pointerArg(node: Node): List<String> {
        val text = ((node as Lit).value as XValue.Text).value
        return if (text.isEmpty()) emptyList() else text.substring(1).split('/').map { it.replace("~1", "/").replace("~0", "~") }
    }

    private fun at(item: XValue, ptr: List<String>): XValue = descend(item, ptr, "an item")

    private fun call(node: Call, ctx: ExprContext): XValue {
        val name = node.name
        val a = node.args
        // Lazy and absence-aware functions first.
        when (name) {
            "coalesce" -> { var last: XValue? = null; for (n in a) { val v = eval(n, ctx); if (v !is XValue.Gone) return v; last = v }; return last!! }
            "has" -> return XValue.Bool(eval(a[0], ctx) !is XValue.Gone)
            "if" -> return when (val c = eval(a[0], ctx)) {
                is XValue.Bool -> eval(if (c.value) a[1] else a[2], ctx)
                is XValue.Gone -> c
                else -> wrong("if", "true/false", c)
            }
            "param" -> {
                val id = ((a[0] as Lit).value as XValue.Text).value
                val values = ctx.params[id]
                return when {
                    values.isNullOrEmpty() -> gone(UnavailableKind.ParamNotSet, "The setting '$id' is not chosen.")
                    else -> XValue.Items(values.map { XValue.Text(it) })
                }
            }
            "header" -> {
                val from = (a[0] as Ref).name
                val wanted = ((a[1] as Lit).value as XValue.Text).value
                val headers = when (val r = ctx.responses[from]) {
                    is ResponseValue.Json -> r.headers
                    is ResponseValue.Text -> r.headers
                    is ResponseValue.Failed -> return gone(UnavailableKind.NoAnswer, r.reason)
                    null -> return gone(UnavailableKind.NoAnswer, "No answer from $from yet.")
                }
                return headers[wanted]?.let { XValue.Text(it.take(MAX_TEXT)) } ?: gone(UnavailableKind.Missing, "$from sent no $wanted header.")
            }
            "rate" -> return rate(a[0], ctx)
        }
        val v = a.map { eval(it, ctx) }
        when (name) {
            "and", "or" -> {
                v.firstOrNull { it is XValue.Gone }?.let { return it }
                val bools = v.map { (it as? XValue.Bool)?.value ?: return wrong(name, "true/false", it) }
                return XValue.Bool(if (name == "and") bools.all { it } else bools.any { it })
            }
            "eq", "ne" -> {
                v.firstOrNull { it is XValue.Gone }?.let { return it }
                val same = same(v[0], v[1])
                return XValue.Bool(if (name == "eq") same else !same)
            }
        }
        // Everything else passes the first missing argument on (pointer literals are never missing).
        v.firstOrNull { it is XValue.Gone }?.let { return it }
        return when (name) {
            "count" -> when (val x = v[0]) { is XValue.Items -> XValue.Num(x.items.size.toDouble()); is XValue.Obj -> XValue.Num(x.json.size.toDouble()); else -> wrong(name, "a list", x) }
            "sum" -> numbers(name, v[0]).let { (n, e) -> e ?: num(n!!.sum()) }
            "avg" -> numbers(name, v[0]).let { (n, e) -> e ?: if (n!!.isEmpty()) gone(UnavailableKind.Empty, "avg of an empty list.") else num(n.average()) }
            "min" -> numbers(name, v[0]).let { (n, e) -> e ?: n!!.minOrNull()?.let { XValue.Num(it) } ?: gone(UnavailableKind.Empty, "min of an empty list.") }
            "max" -> numbers(name, v[0]).let { (n, e) -> e ?: n!!.maxOrNull()?.let { XValue.Num(it) } ?: gone(UnavailableKind.Empty, "max of an empty list.") }
            "first", "last" -> {
                val list = (v[0] as? XValue.Items)?.items ?: return wrong(name, "a list", v[0])
                (if (name == "first") list.firstOrNull() else list.lastOrNull()) ?: gone(UnavailableKind.Empty, "$name of an empty list.")
            }
            "add", "sub", "mul", "div", "percent" -> {
                val x = (v[0] as? XValue.Num)?.value ?: return wrong(name, "numbers", v[0])
                val y = (v[1] as? XValue.Num)?.value ?: return wrong(name, "numbers", v[1])
                when (name) {
                    "add" -> num(x + y); "sub" -> num(x - y); "mul" -> num(x * y)
                    "div" -> if (y == 0.0) gone(UnavailableKind.DivisionByZero, "Division by zero.") else num(x / y)
                    else -> if (y == 0.0) gone(UnavailableKind.DivisionByZero, "A share of zero.") else num(100.0 * x / y)
                }
            }
            "num" -> when (val x = v[0]) {
                is XValue.Num -> x
                is XValue.Text -> x.value.trim().toDoubleOrNull()?.let { num(it) } ?: gone(UnavailableKind.NotANumber, "'${x.value.take(40)}' is not a number.")
                else -> wrong(name, "a number or text", x)
            }
            "round" -> {
                val x = (v[0] as? XValue.Num)?.value ?: return wrong(name, "a number", v[0])
                val d = (v[1] as? XValue.Num)?.value?.takeIf { it == Math.floor(it) && it in 0.0..6.0 }?.toInt() ?: return wrong(name, "0 to 6 digits", v[1])
                num(java.math.BigDecimal(x).setScale(d, java.math.RoundingMode.HALF_UP).toDouble())
            }
            "entries" -> {
                val maps = when (val x = v[0]) {
                    is XValue.Obj -> listOf(x.json)
                    is XValue.Items -> x.items.map { (it as? XValue.Obj)?.json ?: return wrong(name, "an object or a list of objects", it) }
                    else -> return wrong(name, "an object or a list of objects", x)
                }
                val out = maps.flatMap { m -> m.entries.map { (k, value) -> XValue.Obj(JsonObject(mapOf("key" to JsonPrimitive(k), "value" to value))) } }
                if (out.size > MAX_LIST) gone(UnavailableKind.TooMany, "More than $MAX_LIST entries.") else XValue.Items(out)
            }
            "top" -> {
                val list = (v[0] as? XValue.Items)?.items ?: return wrong(name, "a list", v[0])
                val n = (v[1] as? XValue.Num)?.value?.takeIf { it == Math.floor(it) && it in 0.0..100.0 }?.toInt() ?: return wrong(name, "a count of 0 to 100", v[1])
                val ptr = pointerArg(a[2])
                XValue.Items(list.sortedByDescending { (at(it, ptr) as? XValue.Num)?.value ?: Double.NEGATIVE_INFINITY }.take(n))
            }
            "pick" -> {
                val list = (v[0] as? XValue.Items)?.items ?: return wrong(name, "a list", v[0])
                val ptr = pointerArg(a[1]); XValue.Items(list.map { at(it, ptr) })
            }
            "count_eq" -> {
                val list = (v[0] as? XValue.Items)?.items ?: return wrong(name, "a list", v[0])
                val ptr = pointerArg(a[1])
                XValue.Num(list.count { val x = at(it, ptr); x !is XValue.Gone && same(x, v[2]) }.toDouble())
            }
            "filter" -> {
                val list = (v[0] as? XValue.Items)?.items ?: return wrong(name, "a list", v[0])
                val ptr = pointerArg(a[1]); val op = ((a[2] as Lit).value as XValue.Text).value; val want = v[3]
                XValue.Items(list.filter { item -> matches(at(item, ptr), op, want) })
            }
            "not" -> (v[0] as? XValue.Bool)?.let { XValue.Bool(!it.value) } ?: wrong(name, "true/false", v[0])
            "concat" -> {
                val parts = StringBuilder()
                for (x in v) parts.append(plain(x) ?: return wrong(name, "texts or numbers", x))
                val text = parts.toString()
                if (text.length > MAX_TEXT) XValue.Text(text.take(MAX_TEXT - 1) + "\u2026") else XValue.Text(text)
            }
            "prefix_of" -> {
                val t = (v[0] as? XValue.Text)?.value ?: return wrong(name, "text", v[0])
                val sep = (v[1] as? XValue.Text)?.value?.takeIf { it.isNotEmpty() } ?: return wrong(name, "a separator", v[1])
                XValue.Text(t.substringBefore(sep))
            }
            "lower" -> (v[0] as? XValue.Text)?.let { XValue.Text(it.value.lowercase()) } ?: wrong(name, "text", v[0])
            "time" -> when (val x = v[0]) {
                is XValue.Num -> x
                is XValue.Text -> parseTime(x.value)?.let { XValue.Num(it) } ?: gone(UnavailableKind.NotANumber, "'${x.value.take(40)}' is not a time.")
                else -> wrong(name, "a time", x)
            }
            "ago" -> {
                val t = (v[0] as? XValue.Num)?.value ?: return wrong(name, "epoch seconds", v[0])
                num(ctx.now.epochSecond + ctx.now.nano / 1e9 - t)
            }
            "version_ge" -> {
                val x = (v[0] as? XValue.Text)?.value ?: plain(v[0]) ?: return wrong(name, "a version", v[0])
                val y = (v[1] as? XValue.Text)?.value ?: plain(v[1]) ?: return wrong(name, "a version", v[1])
                val px = versionParts(x) ?: return gone(UnavailableKind.NotANumber, "'${x.take(40)}' is not a version.")
                val py = versionParts(y) ?: return gone(UnavailableKind.NotANumber, "'${y.take(40)}' is not a version.")
                XValue.Bool(compareVersions(px, py) >= 0)
            }
            "prom", "prom_sum" -> {
                val text = (v[0] as? XValue.Text)?.value ?: return wrong(name, "a text answer", v[0])
                val metric = (v[1] as XValue.Text).value
                val filters = v.drop(2).map { plain(it) ?: return wrong(name, "label names and values", it) }
                if (filters.size % 2 != 0) return wrong(name, "label name and value pairs", v.last())
                val samples = promSamples(text, metric, filters.chunked(2).associate { it[0] to it[1] })
                if (name == "prom") samples else numbers(name, XValue.Items(samples.items.map { (it as XValue.Obj).json["value"]?.let { j -> fromJson(j) } ?: gone(UnavailableKind.Missing, "") }))
                    .let { (n, e) -> e ?: num(n!!.sum()) }
            }
            else -> throw IllegalStateException("unreachable: $name")
        }
    }

    /**
     * Change per second between this fetch and the previous one. "No data" on the first fetch, when the counter went
     * down (a restart), and when there is no memory. Re-evaluating the same fetch gives the same rate again.
     */
    private fun rate(arg: Node, ctx: ExprContext): XValue {
        val value = when (val v = eval(arg, ctx)) { is XValue.Num -> v.value; is XValue.Gone -> return v; else -> return wrong("rate", "a number", v) }
        val memory = ctx.rates ?: return gone(UnavailableKind.Empty, "A rate needs two readings.")
        val at = requests(arg).mapNotNull { name -> when (val r = ctx.responses[name]) { is ResponseValue.Json -> r.at; is ResponseValue.Text -> r.at; else -> null } }.maxOrNull() ?: ctx.now
        val key = arg.toString()
        val before = memory.previous[key]
        val rate = when {
            before == null -> null
            before.at == at -> before.rate
            at.isBefore(before.at) || value < before.value -> null
            else -> ((value - before.value) / (java.time.Duration.between(before.at, at).toMillis() / 1000.0)).takeIf { it.isFinite() }
        }
        if (before == null || before.at != at) memory.next[key] = RateSample(at, value, rate)
        return rate?.let { XValue.Num(it) } ?: gone(UnavailableKind.Empty, "A rate needs two readings.")
    }

    /** Text form of a scalar, for concat and keys: integers without ".0". */
    fun plain(x: XValue): String? = when (x) {
        is XValue.Text -> x.value
        is XValue.Num -> if (x.value == Math.rint(x.value) && Math.abs(x.value) < 1e15) x.value.toLong().toString() else x.value.toString()
        is XValue.Bool -> x.value.toString()
        else -> null
    }

    /** Strict equality: numbers by value, texts and booleans exactly; different types are never equal. */
    fun same(x: XValue, y: XValue): Boolean = when {
        x is XValue.Num && y is XValue.Num -> x.value == y.value
        x is XValue.Text && y is XValue.Text -> x.value == y.value
        x is XValue.Bool && y is XValue.Bool -> x.value == y.value
        else -> false
    }

    private fun matches(x: XValue, op: String, want: XValue): Boolean {
        if (op == "has") return x !is XValue.Gone
        if (x is XValue.Gone) return false
        return when (op) {
            "eq" -> same(x, want)
            "ne" -> !same(x, want)
            "prefix" -> x is XValue.Text && want is XValue.Text && x.value.startsWith(want.value)
            "in" -> (want as? XValue.Items)?.items?.any { same(x, it) } ?: false
            else -> {
                val a = (x as? XValue.Num)?.value ?: return false
                val b = (want as? XValue.Num)?.value ?: return false
                when (op) { "gt" -> a > b; "ge" -> a >= b; "lt" -> a < b; else -> a <= b }
            }
        }
    }

    internal fun parseTime(text: String): Double? = try {
        val t = OffsetDateTime.parse(text.trim()).toInstant(); t.epochSecond + t.nano / 1e9
    } catch (_: DateTimeParseException) {
        runCatching { Instant.parse(text.trim()).let { it.epochSecond + it.nano / 1e9 } }.getOrNull()
    }

    private fun versionParts(text: String): List<Int>? {
        val core = text.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        val parts = core.split('.')
        if (parts.size > 6) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) { val c = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 }); if (c != 0) return c }
        return 0
    }

    private val SAMPLE = Regex("""^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{(.*)\})?\s+(\S+)(\s+\S+)?$""")
    private val LABEL = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"""")

    /** Samples of one metric in Prometheus text format, as `[{labels:{…}, value:n}]`, filtered by exact label values. */
    private fun promSamples(body: String, metric: String, wanted: Map<String, String>): XValue.Items {
        val out = mutableListOf<XValue>()
        for (line in body.lineSequence()) {
            if (line.isBlank() || line.startsWith("#") || line.length > 4096) continue
            val m = SAMPLE.matchEntire(line.trim()) ?: continue
            if (m.groupValues[1] != metric) continue
            val labels = LABEL.findAll(m.groupValues[3]).associate { it.groupValues[1] to it.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\") }
            if (wanted.any { (k, v) -> labels[k] != v }) continue
            val value = m.groupValues[4].toDoubleOrNull()?.takeIf { it.isFinite() }
            out += XValue.Obj(JsonObject(mapOf("labels" to JsonObject(labels.mapValues { JsonPrimitive(it.value) }), "value" to (value?.let { JsonPrimitive(it) } ?: JsonNull))))
            if (out.size >= MAX_LIST) break
        }
        return XValue.Items(out)
    }
}
