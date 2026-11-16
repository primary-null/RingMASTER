package local.ringleader.core.integrations

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.*

/**
 * The two shipped templates against answers recorded from real instances (a disposable Pi-hole v6.6 / FTL v6.9, Pi-hole v5
 * 2024.07.0 and Home Assistant 2026.11.1 on 2026-11-16, scrubbed: see resources/service-samples/README.md). Each must give its
 * golden state exactly. RINGMASTER_UPDATE_GOLDEN=1 rewrites the golden files for review.
 */
class ServiceTemplateGoldenTest {
    private fun sample(dir: String, name: String): String =
        requireNotNull(javaClass.getResource("/service-samples/$dir/$name")) { "missing sample $dir/$name" }.readText(Charsets.UTF_8)

    private fun snapshots(dir: String, ids: Collection<String>, at: Instant) = ids.associateWith { id ->
        RequestSnapshot(ResponseValue.Json(Json.parseToJsonElement(sample(dir, "$id.json"))), at, at, 200)
    }

    private val haParams = mapOf("entities" to listOf("light.kitchen_lights", "sensor.outside_temperature", "switch.ac", "person.fixture"),
        "charted" to listOf("sensor.outside_temperature", "sensor.power_consumption"))

    private fun piholeNow(): Instant = Instant.ofEpochSecond(Json.parseToJsonElement(sample("pihole-v6", "history.json")).jsonObject["history"]!!.jsonArray.last().jsonObject["timestamp"]!!.jsonPrimitive.long + 300)
    private fun haNow(): Instant = Instant.parse("2026-11-16T18:00:00Z")

    private fun pihole(): ServicePanelState {
        val t = ServiceTemplates.load("pihole"); val v = t.variant("v6")!!
        return ServicePanel.evaluate(t, v, PanelInput(snapshots("pihole-v6", v.requests.keys, piholeNow()), piholeNow(), unencrypted = UnencryptedMark.Unencrypted)).state
    }

    private fun homeAssistant(): ServicePanelState {
        val t = ServiceTemplates.load("homeassistant"); val v = t.variant("rest")!!
        return ServicePanel.evaluate(t, v, PanelInput(snapshots("homeassistant-rest", v.requests.keys, haNow()), haNow(), haParams, unencrypted = UnencryptedMark.Unencrypted)).state
    }

    private fun golden(name: String, actual: String) {
        val path = "/service-samples/golden/$name.txt"
        if (System.getenv("RINGMASTER_UPDATE_GOLDEN") == "1") {
            val file = Path.of("src/test/resources/service-samples/golden/$name.txt")
            Files.createDirectories(file.parent); Files.writeString(file, actual)
            return
        }
        val expected = requireNotNull(javaClass.getResource(path)) { "missing golden $path" }.readText(Charsets.UTF_8).replace("\r\n", "\n")
        assertEquals(expected, actual, "golden $name")
    }

    @Test fun piholeV6GivesItsGoldenState() {
        val s = pihole()
        assertEquals(PanelStatus.Ok, s.status)
        assertTrue(s.readings.filter { it.required }.all { it.unavailable == null })
        assertTrue(s.reading("cachedPct")!!.number!! <= 100.0, "a real recording is self-consistent, unlike the documentation's examples")
        assertTrue(s.series("history")!!.lines.first().points.size in 144..145, "24 hours of 10-minute slots (the recording has 145)")
        golden("pihole-v6", ServicePanel.text(s))
        golden("pihole-v6.showcase", ServicePanel.text(s, showcase = true))
    }

    @Test fun homeAssistantGivesItsGoldenState() {
        val s = homeAssistant()
        assertEquals(PanelStatus.Ok, s.status)
        assertEquals(4, s.grid("picked")!!.cells.size)
        assertEquals(listOf("sensor.outside_temperature", "sensor.power_consumption"), s.series("charted")!!.lines.map { it.name })
        golden("homeassistant-rest", ServicePanel.text(s))
        golden("homeassistant-rest.showcase", ServicePanel.text(s, showcase = true))
    }

    @Test fun showcaseHidesIdentitiesButKeepsTheNumbers() {
        val text = ServicePanel.text(pihole(), showcase = true)
        for (name in listOf("blocked.example.test", "192.0.2.10", "client-b.example.invalid", "upstream.example.invalid")) assertFalse(name in text, name)
        assertTrue("13.6 %" in text)
        val ha = ServicePanel.text(homeAssistant(), showcase = true)
        for (name in listOf("Kitchen Lights", "Outside Temperature", "sensor.power_consumption")) assertFalse(name in ha, name)
        assertTrue("125" in ha)
    }

    @Test fun theProbeChoosesTheRightVariantFromRecordedAnswers() {
        val origin = ServiceOrigin.parseOrThrow("http://192.0.2.5")
        fun probe(id: String, answer: (String) -> Pair<Int, String>) =
            ServiceClient.probe(ServiceTemplates.load(id), origin, WireTrust.PlainHttp(null), FakeService { r -> answer(r.path) }, { Instant.EPOCH }, "t")
        val v6 = probe("pihole") { p -> if (p == "/api/auth") 401 to sample("pihole-v6", "probe.json") else 400 to "{}" }
        assertEquals("v6", (v6 as ProbeVerdict.Found).variant.id)
        val v5Status = sample("pihole-v5", "auth-status.txt").trim().toInt()
        val v5 = probe("pihole") { p -> if (p == "/api/auth") v5Status to "<html>Not found</html>" else 200 to sample("pihole-v5", "probe.json") }
        assertEquals("v5", (v5 as ProbeVerdict.Found).variant.id)
        assertNotNull(v5.variant.unsupported)
        val haText = sample("homeassistant-rest", "probe-unauthenticated.txt").trim()
        val ha = probe("homeassistant") { haText.substringBefore(' ').toInt() to haText.substringAfter(' ') }
        assertEquals("rest", (ha as ProbeVerdict.Found).variant.id)
        assertIs<ProbeVerdict.NoVariant>(probe("homeassistant") { 200 to """{"message": "something else"}""" })
        assertIs<ProbeVerdict.NoVariant>(probe("pihole") { 404 to "{}" })
    }

    @Test fun answersWithEveryFieldRemovedShowApiChangedNotAnException() {
        for ((id, variant, dir) in listOf(Triple("pihole", "v6", "pihole-v6"), Triple("homeassistant", "rest", "homeassistant-rest"))) {
            val t = ServiceTemplates.load(id); val v = t.variant(variant)!!
            val empty = v.requests.keys.associateWith { RequestSnapshot(ResponseValue.Json(JsonObject(emptyMap())), Instant.EPOCH, Instant.EPOCH, 200) }
            val s = ServicePanel.evaluate(t, v, PanelInput(empty, Instant.EPOCH, haParams)).state
            assertEquals(PanelStatus.ApiChanged, s.status, id)
            assertTrue(s.problem!!.message.contains("not in the shape template 1.0.0 ($variant) expects"), s.problem!!.message)
            // and the same with each answer replaced by a list, a number and text
            for (junk in listOf("[]", "42", "\"x\"")) {
                val odd = v.requests.keys.associateWith { RequestSnapshot(ResponseValue.Json(Json.parseToJsonElement(junk)), Instant.EPOCH, Instant.EPOCH, 200) }
                assertEquals(PanelStatus.ApiChanged, ServicePanel.evaluate(t, v, PanelInput(odd, Instant.EPOCH, haParams)).state.status, "$id $junk")
            }
        }
    }

    @Test fun theCompactFormsReadOnlyTheirRequests() {
        val t = ServiceTemplates.load("pihole"); val v = t.variant("v6")!!
        val only = snapshots("pihole-v6", v.compactRequests, piholeNow())
        val s = ServicePanel.evaluate(t, v, PanelInput(only, piholeNow(), wanted = v.compactRequests)).state
        assertEquals("13.6 %", s.pin!!.text); assertEquals(Level.Good, s.pin!!.level)
        assertEquals(24, s.ticker!!.spark!!.size)
        assertEquals(PanelStatus.Ok, s.status, "the lists' requests are not needed for the pin")
    }
}
