package local.ringleader.core.integrations

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlin.test.*

/** The extraction language: every function, its type errors, missing fields, limits and units. */
class TemplateExprTest {
    private val answer = Json.parseToJsonElement("""
        {"n": 7, "zero": 0, "neg": -2.5, "big": 1e308, "huge": 1e400, "s": "12.5", "word": "on", "t": true, "nul": null,
         "list": [3, 1, 2], "mixed": [1, "2", null, "x"], "empty": [], "objs": [{"k": "a", "v": 5}, {"k": "b", "v": 9}, {"k": "c"}],
         "map": {"x": 1, "y": 2}, "pairs": [{"a.test": 4}, {"b.test": 6}], "iso": "2026-11-16T12:00:00+00:00", "ver": "v6.6.1",
         "slash/key": 1, "tilde~key": 2, "nested": {"deep": {"er": [10, 20, 30]}}}
    """)
    private val now = Instant.parse("2026-11-16T12:01:40Z")
    private val ctx = ExprContext(mapOf("a" to ResponseValue.Json(answer), "txt" to ResponseValue.Text("m 1\nm 2\n"), "bad" to ResponseValue.Failed("No answer.")),
        params = mapOf("ids" to listOf("a", "c")), now = now)

    private fun ev(text: String): XValue = TemplateExpr.eval(TemplateExpr.parse(text), ctx)
    private fun num(text: String) = (ev(text) as? XValue.Num)?.value ?: fail("$text gave ${ev(text)}")
    private fun gone(text: String): UnavailableKind = (ev(text) as? XValue.Gone)?.why?.kind ?: fail("$text gave ${ev(text)} instead of missing")
    private fun bool(text: String) = (ev(text) as? XValue.Bool)?.value ?: fail("$text gave ${ev(text)}")
    private fun text(text: String) = (ev(text) as? XValue.Text)?.value ?: fail("$text gave ${ev(text)}")
    private fun count(text: String) = (ev(text) as? XValue.Items)?.items?.size ?: fail("$text gave ${ev(text)}")

    @Test fun pointers() {
        assertEquals(7.0, num("@a/n"))
        assertEquals(20.0, num("@a/nested/deep/er/1"))
        assertEquals(30.0, num("@a/nested/deep/er/-1"), "a negative index counts from the end")
        assertEquals(1.0, num("@a/slash~1key")); assertEquals(2.0, num("@a/tilde~0key"))
        assertEquals(UnavailableKind.Missing, gone("@a/nope"))
        assertEquals(UnavailableKind.Missing, gone("@a/list/9"))
        assertEquals(UnavailableKind.Missing, gone("@a/n/deeper"))
        assertEquals(UnavailableKind.Null, gone("@a/nul"))
        assertEquals(UnavailableKind.OutOfRange, gone("@a/huge"), "1e400 is not turned into infinity")
        assertEquals(UnavailableKind.NoAnswer, gone("@missing/x"))
        assertEquals(UnavailableKind.NoAnswer, gone("@bad/x"))
        assertEquals(UnavailableKind.WrongType, gone("@txt/x"))
        assertEquals("m 1\nm 2\n", text("@txt"))
        assertEquals(3, count("@a/objs/*/k"))
        assertEquals(listOf(5.0, 9.0), (ev("@a/objs/*/v") as XValue.Items).items.filterIsInstance<XValue.Num>().map { it.value })
        assertEquals(2, count("@a/map/*"))
    }

    @Test fun aggregates() {
        assertEquals(6.0, num("sum(@a/list)")); assertEquals(3.0, num("count(@a/list)")); assertEquals(2.0, num("avg(@a/list)"))
        assertEquals(1.0, num("min(@a/list)")); assertEquals(3.0, num("max(@a/list)"))
        assertEquals(3.0, num("first(@a/list)")); assertEquals(2.0, num("last(@a/list)"))
        assertEquals(2.0, num("count(@a/map)"))
        assertEquals(0.0, num("sum(@a/empty)"))
        assertEquals(UnavailableKind.Empty, gone("avg(@a/empty)")); assertEquals(UnavailableKind.Empty, gone("min(@a/empty)"))
        assertEquals(UnavailableKind.Empty, gone("max(@a/empty)")); assertEquals(UnavailableKind.Empty, gone("first(@a/empty)")); assertEquals(UnavailableKind.Empty, gone("last(@a/empty)"))
        assertEquals(UnavailableKind.WrongType, gone("sum(@a/mixed)"), "text that is not a number is never skipped silently")
    }

    @Test fun aggregatesSkipOnlyMissingElements() {
        assertEquals(14.0, num("sum(pick(@a/objs, \"/v\"))"))
        assertEquals(UnavailableKind.WrongType, gone("sum(@a/n)"))
        assertEquals(UnavailableKind.WrongType, gone("count(@a/n)"))
        assertEquals(0.0, num("sum(pick(@a/objs, \"/nope\"))"), "a list of only missing values sums to zero")
    }

    @Test fun arithmeticIsTotal() {
        assertEquals(9.0, num("add(@a/n, 2)")); assertEquals(5.0, num("sub(@a/n, 2)")); assertEquals(14.0, num("mul(@a/n, 2)"))
        assertEquals(3.5, num("div(@a/n, 2)")); assertEquals(50.0, num("percent(1, 2)"))
        assertEquals(UnavailableKind.DivisionByZero, gone("div(@a/n, @a/zero)"))
        assertEquals(UnavailableKind.DivisionByZero, gone("percent(@a/n, 0)"))
        assertEquals(UnavailableKind.OutOfRange, gone("mul(@a/big, 10)"), "overflow is a reason, not infinity")
        assertEquals(UnavailableKind.OutOfRange, gone("add(@a/big, @a/big)"))
        assertEquals(UnavailableKind.WrongType, gone("add(@a/word, 1)"))
        assertEquals(UnavailableKind.WrongType, gone("add(@a/s, 1)"), "numeric text needs num() first")
        assertEquals(13.5, num("add(num(@a/s), 1)"))
        assertEquals(UnavailableKind.NotANumber, gone("num(@a/word)"))
        assertEquals(UnavailableKind.WrongType, gone("num(@a/list)"))
        assertEquals(UnavailableKind.Missing, gone("add(@a/nope, 1)"), "the first missing argument is passed on")
        assertEquals(3.14, num("round(3.14159, 2)")); assertEquals(3.0, num("round(2.5, 0)"))
        assertEquals(UnavailableKind.WrongType, gone("round(1, 9)"))
        assertEquals(-2.5, num("@a/neg")); assertEquals(1.0e-3, num("div(1, 1000)"))
    }

    @Test fun lists() {
        assertEquals(2, count("entries(@a/map)"))
        assertEquals(listOf("a.test", "b.test"), (ev("pick(entries(@a/pairs), \"/key\")") as XValue.Items).items.map { (it as XValue.Text).value })
        assertEquals(UnavailableKind.WrongType, gone("entries(@a/n)"))
        assertEquals(listOf(9.0, 5.0), (ev("pick(top(@a/objs, 2, \"/v\"), \"/v\")") as XValue.Items).items.map { (it as XValue.Num).value })
        assertEquals(3, count("top(@a/objs, 10, \"/v\")"), "items without the number sort last, not dropped")
        assertEquals(UnavailableKind.WrongType, gone("top(@a/objs, 101, \"/v\")"))
        assertEquals(1.0, num("count_eq(@a/objs, \"/k\", \"b\")"))
        assertEquals(1.0, num("count_eq(@a/list, \"\", 2)"))
        assertEquals(2, count("filter(@a/objs, \"/v\", \"has\", 0)"))
        assertEquals(1, count("filter(@a/objs, \"/v\", \"gt\", 5)")); assertEquals(2, count("filter(@a/objs, \"/v\", \"ge\", 5)"))
        assertEquals(1, count("filter(@a/objs, \"/v\", \"lt\", 9)")); assertEquals(2, count("filter(@a/objs, \"/v\", \"le\", 9)"))
        assertEquals(1, count("filter(@a/objs, \"/k\", \"eq\", \"a\")")); assertEquals(2, count("filter(@a/objs, \"/k\", \"ne\", \"a\")"))
        assertEquals(3, count("filter(@a/objs, \"/k\", \"prefix\", \"\")"))
        assertEquals(2, count("filter(@a/objs, \"/k\", \"in\", param(\"ids\"))"))
        assertEquals(UnavailableKind.WrongType, gone("pick(@a/n, \"/x\")"))
    }

    @Test fun logic() {
        assertEquals(7.0, num("coalesce(@a/nope, @a/nul, @a/n)"))
        assertEquals(UnavailableKind.Null, gone("coalesce(@a/nope, @a/nul)"), "all missing: the last reason")
        assertTrue(bool("has(@a/n)")); assertFalse(bool("has(@a/nul)")); assertFalse(bool("has(@a/nope)"))
        assertTrue(bool("eq(@a/word, \"on\")")); assertFalse(bool("eq(@a/s, 12.5)"), "different types are never equal")
        assertTrue(bool("ne(@a/n, 8)")); assertEquals(UnavailableKind.Missing, gone("eq(@a/nope, 1)"))
        assertFalse(bool("not(@a/t)")); assertEquals(UnavailableKind.WrongType, gone("not(@a/n)"))
        assertTrue(bool("and(@a/t, true)")); assertFalse(bool("and(@a/t, false)")); assertTrue(bool("or(false, @a/t)"))
        assertEquals(UnavailableKind.WrongType, gone("and(@a/t, 1)"))
        assertEquals("yes", text("if(@a/t, \"yes\", @a/nope)"), "if only evaluates the branch it takes")
        assertEquals(UnavailableKind.WrongType, gone("if(@a/n, 1, 2)"))
        assertEquals(UnavailableKind.ParamNotSet, gone("param(\"other\")"))
        assertEquals(2, count("param(\"ids\")"))
    }

    @Test fun textTimeAndVersions() {
        assertEquals("on-7", text("concat(@a/word, \"-\", @a/n)"))
        assertEquals(TemplateExpr.MAX_TEXT, text("concat(\"" + "x".repeat(150) + "\", \"" + "y".repeat(150) + "\")").length)
        assertEquals(UnavailableKind.WrongType, gone("concat(@a/list)"))
        assertEquals("light", text("prefix_of(\"light.kitchen\", \".\")")); assertEquals("on", text("lower(\"ON\")"))
        assertEquals(1794830400.0, num("time(@a/iso)")); assertEquals(5.0, num("time(5)"))
        assertEquals(UnavailableKind.NotANumber, gone("time(@a/word)"))
        assertEquals(100.0, num("ago(time(@a/iso))"))
        assertTrue(bool("version_ge(@a/ver, \"6.0\")")); assertTrue(bool("version_ge(@a/ver, \"v6.6.1\")")); assertFalse(bool("version_ge(@a/ver, \"6.10\")"))
        assertEquals(UnavailableKind.NotANumber, gone("version_ge(@a/word, \"1\")"))
    }

    @Test fun prometheusText() {
        val prom = ExprContext(mapOf("m" to ResponseValue.Text("# HELP x\nmonitor_status{monitor_name=\"A\",type=\"http\"} 1\nmonitor_status{monitor_name=\"B\",type=\"ping\"} 0\nother 5\n")))
        assertEquals(2, (TemplateExpr.eval(TemplateExpr.parse("prom(@m, \"monitor_status\")"), prom) as XValue.Items).items.size)
        assertEquals(1.0, (TemplateExpr.eval(TemplateExpr.parse("prom_sum(@m, \"monitor_status\", \"type\", \"http\")"), prom) as XValue.Num).value)
        assertEquals(1.0, (TemplateExpr.eval(TemplateExpr.parse("count_eq(prom(@m, \"monitor_status\"), \"/value\", 0)"), prom) as XValue.Num).value)
    }

    @Test fun headerAndRate() {
        val first = ExprContext(mapOf("l" to ResponseValue.Json(Json.parseToJsonElement("""{"c": 100}"""), mapOf("x-total-count" to "42"), Instant.parse("2026-11-16T12:00:00Z"))),
            rates = RateMemory())
        assertEquals("42", (TemplateExpr.eval(TemplateExpr.parse("header(@l, \"x-total-count\")"), first) as XValue.Text).value)
        val rateExpr = TemplateExpr.parse("rate(@l/c)")
        assertIs<XValue.Gone>(TemplateExpr.eval(rateExpr, first), "the first poll has no rate")
        val second = ExprContext(mapOf("l" to ResponseValue.Json(Json.parseToJsonElement("""{"c": 160}"""), emptyMap(), Instant.parse("2026-11-16T12:00:30Z"))),
            rates = RateMemory(first.rates!!.next))
        assertEquals(2.0, (TemplateExpr.eval(rateExpr, second) as XValue.Num).value)
        val reset = ExprContext(mapOf("l" to ResponseValue.Json(Json.parseToJsonElement("""{"c": 3}"""), emptyMap(), Instant.parse("2026-11-16T12:01:00Z"))),
            rates = RateMemory(second.rates!!.next))
        assertIs<XValue.Gone>(TemplateExpr.eval(rateExpr, reset), "a counter that went down is a restart, not a negative rate")
        assertIs<XValue.Gone>(TemplateExpr.eval(rateExpr, ExprContext(second.responses)), "no memory, no rate")
    }

    @Test fun itemFields() {
        val node = TemplateExpr.parse("/k", itemField = true)
        val item = XValue.Obj(Json.parseToJsonElement("""{"k": "z"}""") as kotlinx.serialization.json.JsonObject)
        assertEquals(XValue.Text("z"), TemplateExpr.eval(node, ctx.withItem(item)))
        assertEquals(UnavailableKind.Missing, (TemplateExpr.eval(node, ctx) as XValue.Gone).why.kind)
    }

    @Test fun theLanguageRefusesWhatItDoesNotKnow() {
        val refused = mapOf(
            "exec(\"rm\")" to "Unknown function 'exec'", "sum(@a/x, 1)" to "sum takes 1 argument", "top(@a/l, 3, count)" to "expected at position",
            "top(@a/l, 3, @a/x)" to "pointer string", "top(@a/l, 3, \"count\")" to "pointer string", "pick(@a/l, \"/*/x\")" to "cannot use *",
            "filter(@a/l, \"/x\", \"like\", 1)" to "no operator 'like'", "filter(@a/l, \"/x\", @a/op, 1)" to "text in quotes",
            "@a/*/x/*" to "use * once", "null" to "There is no null", "sum(@a/x" to "ends early", "sum(@a/x))" to "Unexpected ')'",
            "\"open" to "not closed", "1e999" to "out of range", "@" to "request name", "header(@a/x, \"x-total-count\")" to "Argument 1 of header",
            "header(@a, \"authorization\")" to "header can read", "param(@a/x)" to "text in quotes", "@a/bad~2" to "bad ~ escape",
            "x".repeat(600) to "longer than 512", "sum(".repeat(20) + "1" + ")".repeat(20) to "nested deeper",
            "rate(@item/x)" to "rate works on a whole answer", "$" to "Unexpected '\$'",
        )
        for ((text, message) in refused) {
            val e = assertFailsWith<ExprError>(text) { TemplateExpr.parse(text) }
            assertTrue(e.message!!.contains(message), "'${text.take(40)}' -> ${e.message}")
        }
        assertEquals(setOf("a", "b"), TemplateExpr.requests(TemplateExpr.parse("add(@a/x, coalesce(@b/y, @item/z))")))
        assertEquals(TemplateExpr.FUNCTIONS.size, 38)
    }

    @Test fun hugeListsAreBounded() {
        val big = Json.parseToJsonElement("[" + (1..10_001).joinToString(",") + "]")
        val c = ExprContext(mapOf("b" to ResponseValue.Json(big)))
        assertEquals(UnavailableKind.TooMany, (TemplateExpr.eval(TemplateExpr.parse("count(@b)"), c) as XValue.Gone).why.kind)
        assertEquals(UnavailableKind.TooMany, (TemplateExpr.eval(TemplateExpr.parse("count(@b/*)"), c) as XValue.Gone).why.kind)
    }

    @Test fun unitsFormatOneWay() {
        val t0 = Instant.parse("2026-11-16T12:00:00Z")
        val cases = listOf(
            Triple(1234567.0, ReadingUnit.Count, "1,234,567"), Triple(1.1, ReadingUnit.Number, "1.10"), Triple(34.512, ReadingUnit.Percent, "34.5 %"),
            Triple(0.345, ReadingUnit.Ratio, "34.5 %"), Triple(1536.0, ReadingUnit.Bytes, "1.5 KiB"), Triple(500.0, ReadingUnit.Bytes, "500 B"),
            Triple(3.0 * 1024 * 1024 * 1024, ReadingUnit.Bytes, "3.0 GiB"), Triple(2048.0, ReadingUnit.BytesPerSecond, "2.0 KiB/s"),
            Triple(1_500_000.0, ReadingUnit.BitsPerSecond, "1.5 Mbit/s"), Triple(4.25, ReadingUnit.Milliseconds, "4.3 ms"),
            Triple(1234.0, ReadingUnit.Milliseconds, "1,234 ms"), Triple(45.0, ReadingUnit.Seconds, "45 s"), Triple(3900.0, ReadingUnit.Seconds, "1 h 5 min"),
            Triple(190_000.0, ReadingUnit.Seconds, "2 d 4 h"), Triple(21.55, ReadingUnit.Celsius, "21.6 \u00b0C"),
            Triple(t0.epochSecond - 600.0, ReadingUnit.Epoch, "10 min ago"), Triple(t0.epochSecond + 7200.0, ReadingUnit.Epoch, "in 2 h"),
            Triple(1e20, ReadingUnit.Count, "1.00e+20"),
        )
        for ((v, unit, want) in cases) assertEquals(want, ServiceUnits.number(v, unit, t0), "$v $unit")
        assertEquals("no data", ServiceUnits.format(XValue.Gone(Unavailable(UnavailableKind.Missing, "")), ReadingUnit.Count))
        assertEquals("enabled", ServiceUnits.format(XValue.Text("enabled"), ReadingUnit.State))
        assertEquals("true", ServiceUnits.stateKey(XValue.Bool(true))); assertEquals("1", ServiceUnits.stateKey(XValue.Num(1.0)))
        assertEquals(Level.Bad, Levels(badBelow = 1.0).of(0.0)); assertEquals(Level.Good, Levels(badBelow = 1.0).of(5.0))
        assertEquals(Level.Warn, Levels(warnAbove = 10.0, badAbove = 20.0).of(15.0)); assertEquals(Level.Bad, Levels(warnAbove = 10.0, badAbove = 20.0).of(25.0))
    }
}
