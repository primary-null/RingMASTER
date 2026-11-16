package local.ringleader.core.integrations

import kotlin.test.*

/** The template schema: a good template loads, and each kind of mistake is named with where it is. */
class ServiceTemplateParserTest {
    companion object {
        /** A small valid template; tests replace one piece at a time. */
        val GOOD = """
        {
          "schema": 1, "id": "demo", "templateVersion": "1.0.0", "name": "Demo", "glyph": "pulse",
          "sources": ["https://docs.example.test/api"],
          "detect": {"catalogue": ["pihole"], "ports": [{"port": 8080, "secure": false}]},
          "variants": [{
            "id": "v1", "label": "Demo v1",
            "probe": {"path": "/api/ping", "accept": [200, 401], "expect": "has(@probe/ok)"},
            "auth": {"scheme": "header-key", "header": "X-Api-Key", "fields": [{"id": "secret", "label": "Key", "secret": true}],
                     "ask": "Demo key", "power": "Reads only.", "fullPower": false, "where": ["Settings > Keys."]},
            "params": [{"id": "pick", "label": "Things", "kind": "multi", "max": 2, "pattern": "^[a-z]+$"}],
            "requests": {
              "stats": {"path": "/api/stats", "query": {"count": "8", "ids": "{param:pick}"}, "every": "30s", "minEvery": "10s", "maxBytes": 65536},
              "items": {"path": "/api/items/{param:pick}", "every": "5m", "optional": true}
            },
            "readings": [
              {"id": "total", "label": "Total", "expr": "@stats/total", "unit": "count", "required": true},
              {"id": "share", "label": "Share", "expr": "percent(@stats/part, @stats/total)", "unit": "percent", "levels": {"warnAbove": 50}},
              {"id": "state", "label": "State", "expr": "@stats/state", "unit": "state", "states": {"on": "good", "off": "bad"}}
            ],
            "series": [{"id": "hist", "label": "History", "unit": "count", "time": "@stats/h/*/t", "values": {"All": "@stats/h/*/n"}},
                       {"id": "totals", "label": "Totals", "unit": "count", "sampled": "total", "keep": 10}],
            "lists": [{"id": "top", "label": "Top", "items": "top(@items/list, 5, \"/n\")", "title": "/name", "value": "/n", "unit": "count", "identity": true}],
            "panel": [
              {"block": "hero", "reading": "share", "sub": "total", "state": "state", "caption": "share"},
              {"block": "tiles", "readings": ["total", "share"], "sparks": {"total": "hist.All"}},
              {"block": "chart", "series": "hist", "kind": "stacked-bars"},
              {"block": "list", "list": "top", "bars": true, "open": "/ui/top"},
              {"block": "notice", "when": "eq(@stats/state, \"off\")", "text": "Off. Total {total}."}
            ],
            "compact": {"pin": {"reading": "share", "state": "state"}, "ticker": {"label": "Demo", "reading": "share", "spark": "hist.All"}},
            "links": {"home": "/ui/", "help": "https://docs.example.test/"}
          }]
        }
        """.trimIndent()
    }

    private fun problems(text: String): List<String> = assertFailsWith<TemplateError> { ServiceTemplateParser.parse("t.json", text) }.problems
    private fun assertProblem(text: String, vararg wanted: String) {
        val found = problems(text)
        for (w in wanted) assertTrue(found.any { w in it }, "expected a problem containing '$w', got:\n${found.joinToString("\n")}")
    }

    @Test fun theGoodTemplateLoadsWithItsDependencies() {
        val t = ServiceTemplateParser.parse("t.json", GOOD)
        val v = t.variants.single()
        assertEquals(setOf("stats"), v.compactRequests, "the pin and ticker poll only what they need")
        assertEquals(setOf("stats", "items"), v.panelRequests)
        assertEquals(setOf("stats"), v.readingRequests["share"])
        assertEquals(ReadingUnit.Percent, v.reading("share")!!.unit)
    }

    private fun swap(old: String, new: String): String { assertTrue(old in GOOD, old); return GOOD.replace(old, new) }

    @Test fun eachKindOfMistakeIsNamed() {
        assertProblem("{", "not valid JSON")
        assertProblem(swap("\"schema\": 1", "\"schema\": 2"), "template.schema: schema 2 is newer")
        assertProblem(swap("\"id\": \"demo\"", "\"id\": \"Demo!\""), "template.id")
        assertProblem(swap("\"glyph\": \"pulse\"", "\"glyph\": \"logo\""), "not one of the app's glyphs")
        assertProblem(swap("\"catalogue\": [\"pihole\"]", "\"catalogue\": [\"nope\"]"), "not an id in SERVICE_CATALOGUE")
        assertProblem(swap("\"label\": \"Total\"", "\"lable\": \"Total\""), "unknown key 'lable'", "did you mean 'label'", "'label' is missing")
        assertProblem(swap("\"path\": \"/api/stats\",", "\"path\": \"/api/stats\", \"method\": \"POST\","), "Requests are GET only")
        assertProblem(swap("\"path\": \"/api/stats\"", "\"path\": \"http://elsewhere.test/api\""), "absolute URL is not allowed")
        assertProblem(swap("\"path\": \"/api/stats\"", "\"path\": \"/api/../admin\""), "must not use . or ..")
        assertProblem(swap("\"path\": \"/api/stats\"", "\"path\": \"/api/stats?x=1\""), "must not hold a query")
        assertProblem(swap("\"/api/items/{param:pick}\"", "\"/api/{param:pick}/items\""), "may only fill the last segment")
        assertProblem(swap("\"/api/items/{param:pick}\"", "\"/api/items/{param:other}\""), "not a declared parameter")
        assertProblem(swap("\"count\": \"8\"", "\"count\": \"8;rm\""), "may hold letters")
        assertProblem(swap("\"every\": \"30s\"", "\"every\": \"30x\""), "not a duration")
        assertProblem(swap("\"minEvery\": \"10s\"", "\"minEvery\": \"2s\""), "at least 5s")
        assertProblem(swap("\"maxBytes\": 65536", "\"maxBytes\": 4194304"), "maxBytes: must be between")
        assertProblem(swap("\"expr\": \"@stats/total\"", "\"expr\": \"@other/total\""), "reads @other, which is not a declared request")
        assertProblem(swap("\"expr\": \"@stats/total\"", "\"expr\": \"evil(@stats/total)\""), "Unknown function 'evil'")
        assertProblem(swap("\"unit\": \"percent\"", "\"unit\": \"furlongs\""), "'furlongs' is not a unit")
        assertProblem(swap("\"unit\": \"count\", \"required\": true", "\"required\": true"), "'unit' is missing")
        assertProblem(swap("\"required\": true", "\"required\": false"), "at least one reading must be 'required'")
        assertProblem(swap("\"states\": {\"on\": \"good\", \"off\": \"bad\"}", "\"states\": {\"on\": \"green\"}"), "must be good, info, warn, bad or neutral")
        assertProblem(swap("\"levels\": {\"warnAbove\": 50}", "\"levels\": {\"warnAbove\": \"50\"}"), "must be a number")
        assertProblem(swap("\"reading\": \"share\", \"sub\"", "\"reading\": \"nope\", \"sub\""), "'nope' is not a reading")
        assertProblem(swap("\"block\": \"chart\"", "\"block\": \"pie\""), "'pie' is not a block")
        assertProblem(swap("\"kind\": \"stacked-bars\"", "\"kind\": \"pie\""), "'pie' must be area")
        assertProblem(swap("\"hist.All\"}}", "\"hist.Nope\"}}"), "has no line 'Nope'")
        assertProblem(swap("\"open\": \"/ui/top\"", "\"open\": \"//evil.test/x\""), "must be a path on the service's own address")
        assertProblem(swap("Off. Total {total}.", "Off. {nope}."), "{nope} is not a reading")
        assertProblem(swap("\"header\": \"X-Api-Key\"", "\"header\": \"Cookie\""), "not a header a template may send")
        assertProblem(swap("\"secret\": true}]", "\"secret\": false}]"), "exactly one field with \"secret\": true")
        assertProblem(swap("\"fullPower\": false, ", ""), "'fullPower' is missing")
        assertProblem(swap("\"scheme\": \"header-key\"", "\"scheme\": \"oauth\""), "'oauth' is not a scheme")
        assertProblem(swap("\"pattern\": \"^[a-z]+$\"", "\"pattern\": \"[a-z]+\""), "anchored")
        assertProblem(swap("\"help\": \"https://docs.example.test/\"", "\"help\": \"http://docs.example.test/\""), "help link is an https://")
        assertProblem(swap("\"sources\": [\"https://docs.example.test/api\"]", "\"sources\": [\"docs\"]"), "a source is an https:// address")
    }

    @Test fun sessionLoginsAreTheOnlyBodyAndUseOnlyCredentialFields() {
        val session = swap("\"scheme\": \"header-key\", \"header\": \"X-Api-Key\",", "\"scheme\": \"session-password\", \"login\": {\"path\": \"/api/auth\", \"body\": {\"password\": \"secret\"}, \"token\": \"/session/sid\", \"sendAs\": \"X-SID\"}, \"logout\": {\"path\": \"/api/auth\"},")
        ServiceTemplateParser.parse("t.json", session)
        assertProblem(session.replace("\"password\": \"secret\"", "\"password\": \"hunter2\""), "not a credential field")
        assertProblem(session.replace(", \"logout\": {\"path\": \"/api/auth\"}", ""), "needs a 'logout' path")
        assertProblem(session.replace("\"token\": \"/session/sid\"", "\"token\": \"session.sid\""), "must be a JSON pointer")
    }

    @Test fun anUnsupportedVariantHoldsOnlyItsSentence() {
        val withOld = GOOD.replace("\"variants\": [{", "\"variants\": [{\"id\": \"v0\", \"label\": \"Old\", \"probe\": {\"path\": \"/old\", \"accept\": [200]}, \"unsupported\": \"Version 0 is not supported yet.\"}, {")
        val t = ServiceTemplateParser.parse("t.json", withOld)
        assertEquals("Version 0 is not supported yet.", t.variants.first().unsupported)
        assertProblem(withOld.replace("\"unsupported\": \"Version 0 is not supported yet.\"", "\"unsupported\": \"x\", \"requests\": {}"), "unknown key 'requests'")
    }

    @Test fun allProblemsAreReportedAtOnce() {
        val text = GOOD.replace("\"glyph\": \"pulse\"", "\"glyph\": \"x\"").replace("\"every\": \"30s\"", "\"every\": \"soon\"").replace("\"unit\": \"percent\"", "\"unit\": \"y\"")
        assertTrue(problems(text).size >= 3)
    }
}
