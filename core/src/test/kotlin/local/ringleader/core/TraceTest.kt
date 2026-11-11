package local.ringleader.core

import kotlin.test.*
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

class TraceTest {
    @Test fun secretFieldsAndBodiesAreDropped() {
        val keys=listOf("Authorization","Cookie","PVEAuthCookie","CSRFPreventionToken","password","TOTP","otp","vncticket","ticket","token","secret","VNC password","SPICE password","requestBody","responseBody","payload","privateKey","tfa-challenge")
        keys.forEach { key -> assertEquals(Redact.MASK,Redact.details(mapOf(key to "synthetic-sensitive-value"))[key],key) }
    }
    @Test fun realisticHeaderAndFormRedaction() {
        val cases=listOf(
            "\"Authorization\":\"Bearer synthetic-token\"",
            "Authorization: PVEAPIToken=demo@pve!reader=synthetic-token",
            "Authorization: PDMAPIToken=demo@pdm!reader:synthetic-token",
            "PVEAPIToken=demo@pve!reader=synthetic-token",
            "PDMAPIToken=demo@pdm!reader:synthetic-token",
            "Cookie: PVEAuthCookie=synthetic-token; other=synthetic-token",
            "password=synthetic-token&otp=synthetic-token",
            "{\"password\":\"synthetic-token\",\"ticket\":\"synthetic-token\"}",
            "CSRFPreventionToken: synthetic-token", "vncticket=synthetic-token",
            "SPICE password=synthetic-token", "VNC password: synthetic-token",
            "PVE:demo@pam:synthetic-token", "https://demo:synthetic-token@example.invalid/api"
        )
        cases.forEach { assertFalse("synthetic-token" in Redact.text(it),it.substringBefore('=')) }
    }
    @Test fun urlKeepsRoutingAndDropsSensitiveValues() {
        val url=Redact.url("https://example.invalid:8006/api2/json/nodes/demo/vncwebsocket?port=5900&vncticket=synthetic-one&password=synthetic-two&token=synthetic-three&secret=synthetic-four&ticket=synthetic-five")
        assertTrue(url.startsWith("https://example.invalid:8006/api2/json/nodes/demo/vncwebsocket?port=5900"))
        assertFalse("synthetic-" in url)
        assertFalse("synthetic" in Redact.url("https://example.invalid/?%74icket=synthetic"))
        assertFalse("synthetic" in Redact.url("https://example.invalid/#synthetic"))
        assertFalse("synthetic" in Redact.text("GET /api?%74icket=synthetic&port=5900"))
    }
    @Test fun processArgumentsOmitOpaqueScriptsAndCredentials() {
        assertFalse("synthetic-sensitive" in Redact.commandDetails(listOf("powershell.exe","-EncodedCommand","synthetic-sensitive")).toString())
        assertFalse("synthetic-sensitive" in Redact.commandDetails(listOf("iperf3","--password","synthetic-sensitive")).toString())
        assertTrue("demo.invalid" in Redact.commandDetails(listOf("ping","demo.invalid")).toString())
    }
    @Test fun boundedRingAndOrderedIds() {
        Trace.clear()
        repeat(Trace.CAPACITY+20) { Trace.emit(TraceSource.App,"test","event") }
        val events=Trace.snapshot(); assertEquals(Trace.CAPACITY,events.size)
        assertTrue(events.zipWithNext().all { (a,b)->a.id<b.id })
    }
    @Test fun spanLifecycleAndIdempotentEnd() {
        val span=Trace.begin(TraceSource.Console,"fixture",session=true)
        try {
            assertTrue(Trace.vitals().inFlight.any { it.spanId==span.id })
            span.bytes(24,12); span.frame()
            assertEquals(24L,Trace.vitals().sessions.first { it.event.spanId==span.id }.bytesIn)
            span.end(status=200); span.end()
            assertFalse(Trace.vitals().inFlight.any { it.spanId==span.id })
            val end=Trace.snapshot().single { it.spanId==span.id && it.kind=="end" }
            assertEquals(TraceOutcome.Ok,end.outcome); assertEquals(24L,end.bytesIn); assertNotNull(end.durationMs)
        } finally { span.end() }
    }
    @Test fun processExitCodesAreNeverReadAsHttpStatuses() {
        fun outcome(span: Trace.Span) = Trace.snapshot().single { it.spanId==span.id && it.kind=="end" }
        val ok=Trace.begin(TraceSource.Tools,"exit 0"); ok.endProcess(0)
        assertEquals(TraceOutcome.Ok,outcome(ok).outcome); assertEquals(0,outcome(ok).status)
        listOf(1,100,200,255).forEach { code ->
            val span=Trace.begin(TraceSource.Tools,"exit $code"); span.endProcess(code)
            val end=outcome(span)
            assertEquals(TraceOutcome.Error,end.outcome,"exit $code"); assertEquals(code,end.status)
            span.end(); assertEquals(TraceOutcome.Error,outcome(span).outcome,"exit $code stays ended")
        }
        val console=Trace.begin(TraceSource.Console,"viewer exit 200",session=true); console.endProcess(200)
        assertEquals(TraceOutcome.Error,outcome(console).outcome)
    }
    @Test fun httpStatusesKeepTheirClassification() {
        // SocketTools records real HTTP statuses under Tools; a 200 there is still success.
        listOf(TraceSource.PveApi,TraceSource.Tools).forEach { source ->
            val span=Trace.begin(source,"GET fixture"); span.end(status=200)
            assertEquals(TraceOutcome.Ok,Trace.snapshot().single { it.spanId==span.id && it.kind=="end" }.outcome,source.name)
        }
    }
    @Test fun oneRowStableIdentityTimelineAndExport() {
        Trace.clear()
        val span=Trace.begin(TraceSource.PveApi,"GET fixture",correlation="fixture")
        val running=Trace.snapshot().single()
        assertNull(running.endedAt)
        Trace.emit(TraceSource.Task,"poll","related",spanId=span.id,correlation="fixture")
        span.end(status=503,bytesIn=2048)
        val rows=Trace.snapshot()
        assertEquals(2,rows.size)
        val done=rows.first()
        assertEquals(running.id,done.id)
        assertEquals(running.at,done.at)
        assertEquals(listOf("begin","end"),done.timeline.map { it.kind })
        assertEquals(TraceOutcome.Error,done.outcome)
        val json=Json.parseToJsonElement(Trace.jsonLine(done)).jsonObject
        assertEquals(done.at.toString(),json["start"]!!.jsonPrimitive.content)
        assertEquals(done.endedAt.toString(),json["end"]!!.jsonPrimitive.content)
        assertEquals(2,json["timeline"]!!.jsonArray.size)
        assertNotNull(json["durationMs"])
    }
    @Test fun clearAndEvictionDoNotResurrectSpans() {
        Trace.clear()
        val span=Trace.begin(TraceSource.Tools,"cleared")
        Trace.clear(); span.end()
        assertTrue(Trace.snapshot().isEmpty())
        val evicted=Trace.begin(TraceSource.Tools,"evicted")
        repeat(Trace.CAPACITY) { Trace.emit(TraceSource.App,"test","event") }
        evicted.end()
        assertEquals(Trace.CAPACITY,Trace.snapshot().size)
        assertFalse(Trace.snapshot().any { it.spanId==evicted.id })
    }
    @Test fun metricsPercentilesBucketsSourcesAndExpiry() {
        val now=Instant.parse("2026-01-01T00:05:00Z")
        val rows=listOf(5L,20L,70L,200L,700L,1200L).mapIndexed { index,ms ->
            TraceEvent(index.toLong(),now.minusSeconds(2),TraceSource.PveApi,"end","fixture",durationMs=ms,
                endedAt=now.minusSeconds(1),outcome=if(index==5) TraceOutcome.Error else TraceOutcome.Ok)
        }
        val metrics=TraceMetrics.from(rows,now)
        assertEquals(70L,metrics.p50); assertEquals(1200L,metrics.p95)
        assertEquals(List(6) { 1 },metrics.histogram)
        assertEquals(listOf(TraceSource.PveApi to 6),metrics.sources)
        assertEquals(listOf(1200L,700L,200L,70L,20L),metrics.slowest.map { it.durationMs })
        assertEquals(1,metrics.errors.size)
        val later=TraceMetrics.from(rows,now.plusSeconds(61))
        assertNull(later.p50); assertTrue(later.sources.isEmpty()); assertEquals(5,later.slowest.size)
        assertTrue(TraceMetrics.from(rows,now.plusSeconds(301)).errors.isEmpty())
        assertTrue(TraceMetrics.from(rows,now.plusSeconds(301)).slowest.isEmpty())
    }
    @Test fun errorsAndRollingWindow() {
        val before=Trace.vitals().requestsPerMinute
        val span=Trace.begin(TraceSource.PveApi,"GET fixture"); span.end(status=503)
        assertTrue(Trace.vitals().requestsPerMinute>=before+1)
        assertTrue(Trace.vitals().errorsPerMinute>=1)
        assertEquals(0,Trace.vitals(Instant.now().plusSeconds(61)).requestsPerMinute)
        assertEquals(0,Trace.vitals(Instant.now().plusSeconds(61)).errorsPerMinute)
    }
    @Test fun concurrentProducersAndSpans() {
        val pool=Executors.newFixedThreadPool(8)
        val futures=(0 until 8).map { pool.submit { repeat(2000) { Trace.begin(TraceSource.Tools,"parallel").end() } } }
        try { futures.forEach { it.get(30,TimeUnit.SECONDS) } } finally { pool.shutdownNow() }
        val events=Trace.snapshot(); assertEquals(Trace.CAPACITY,events.size); assertEquals(events.size,events.map { it.id }.toSet().size)
        assertFalse(Trace.vitals().inFlight.any { it.summary=="parallel" })
    }
    @Test fun exportIsJsonAndRedacted() {
        val e=Trace.emit(TraceSource.Auth,"test","password=synthetic-sensitive",mapOf("body" to "synthetic-sensitive","ticket" to "synthetic-sensitive"))
        val line=Trace.jsonLine(e); assertFalse("synthetic-sensitive" in line)
        assertEquals("Auth",Json.parseToJsonElement(line).jsonObject["source"]!!.jsonPrimitive.content)
    }
}
