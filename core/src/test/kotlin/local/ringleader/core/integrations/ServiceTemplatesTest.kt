package local.ringleader.core.integrations

import kotlin.test.*

/** The built-in templates: they load, and their structural rules hold. Golden states are in ServiceTemplateGoldenTest. */
class ServiceTemplatesTest {
    @Test fun everyBuiltInTemplateLoadsAndValidates() {
        for (id in ServiceTemplates.BUILT_IN) {
            val t = ServiceTemplates.load(id)
            assertEquals(id, t.id)
            for (v in t.variants.filter { it.unsupported == null }) {
                assertTrue(v.requests.values.all { it.path.startsWith("/") && !it.path.contains("//") }, "$id/${v.id}: paths")
                assertTrue(v.readings.any { it.required }, "$id/${v.id}: a required reading")
                assertTrue(v.compact?.pin != null && v.compact.ticker != null, "$id/${v.id}: compact forms")
                assertTrue(v.auth.ask.isNotBlank() && v.auth.power.isNotBlank() && v.auth.where.isNotEmpty(), "$id/${v.id}: credential words")
                assertTrue(v.requests.values.all { it.maxBytes <= SERVICE_ANSWER_DEFAULT })
            }
        }
    }

    @Test fun piholeIsFullPowerAndItsV5IsRecognisedNotRead() {
        val t = ServiceTemplates.load("pihole")
        assertEquals(listOf("v6", "v5"), t.variants.map { it.id })
        assertTrue(t.variant("v6")!!.auth.fullPower)
        assertEquals(AuthScheme.SessionPassword, t.variant("v6")!!.auth.scheme)
        assertNotNull(t.variant("v5")!!.unsupported)
        assertEquals(setOf("summary", "blocking", "history"), t.variant("v6")!!.compactRequests, "the pin and ticker poll three requests, not the lists")
    }

    @Test fun homeAssistantSendsABearerTokenAndIsNotMarkedFullPower() {
        val v = ServiceTemplates.load("homeassistant").variant("rest")!!
        assertEquals(AuthScheme.HeaderKey, v.auth.scheme); assertEquals("Authorization", v.auth.header); assertEquals("Bearer ", v.auth.prefix)
        assertFalse(v.auth.fullPower)
        assertEquals(listOf("homeassistant"), ServiceTemplates.forCatalogue("haos-vm").map { it.id })
    }
}
