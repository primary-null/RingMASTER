package local.ringleader.core

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

enum class TraceSource { PveApi, PdmApi, Auth, Console, Terminal, Task, LiveLoop, Scan, Tools, Vpn, Bluetooth, Settings, App, Web }
enum class TraceOutcome { Ok, Warn, Error, Info }
data class TraceMoment(val at: Instant, val kind: String, val status: Int?, val outcome: TraceOutcome)

data class TraceEvent(val id: Long, val at: Instant, val source: TraceSource, val kind: String, val summary: String,
    val detail: Map<String,String> = emptyMap(), val durationMs: Long? = null, val bytesIn: Long? = null,
    val bytesOut: Long? = null, val status: Int? = null, val outcome: TraceOutcome = TraceOutcome.Info,
    val spanId: Long? = null, val correlation: String? = null,
    val endedAt: Instant? = null, val timeline: List<TraceMoment> = emptyList())
data class TraceLiveLoop(val state: String = "idle", val nextTickAt: Instant? = null, val intervalMs: Long = 0, val backoffSeconds: Long = 0)
data class TraceSession(val event: TraceEvent, val bytesIn: Long, val bytesOut: Long, val frames: Long, val framesPerSecond: Long)
data class TraceVitals(val requestsPerMinute: Int, val errorsPerMinute: Int, val inFlight: List<TraceEvent>,
    val sessions: List<TraceSession>, val requests: List<Int>, val errors: List<Int>)

/** Bounded storage, O(1) publication, no collector required and no background worker. */
object Trace {
    const val CAPACITY = 10_000
    private val lock = Any()
    private val ids = AtomicLong()
    private val events = linkedMapOf<Long, TraceEvent>()
    private val active = linkedMapOf<Long, Span>()
    private val requestSeconds = LongArray(60) { Long.MIN_VALUE }
    private val requestCounts = IntArray(60)
    private val errorCounts = IntArray(60)
    private val revision = MutableStateFlow(0L)
    val changes: StateFlow<Long> = revision.asStateFlow()
    val liveLoop = MutableStateFlow(TraceLiveLoop())
    fun snapshot(): List<TraceEvent> = synchronized(lock) { events.values.toList() }
    /**
     * Drops every row except the begin rows of operations still in flight. Those keep their row, so the stream and the
     * in-flight count agree and the end still records a duration. Returns the number of rows removed.
     */
    fun clear(): Int = synchronized(lock) {
        val before = events.size
        events.keys.retainAll(active.keys)
        revision.value++
        before - events.size
    }
    fun emit(source: TraceSource, kind: String, summary: String, detail: Map<String,String> = emptyMap(),
        durationMs: Long? = null, bytesIn: Long? = null, bytesOut: Long? = null, status: Int? = null,
        outcome: TraceOutcome = TraceOutcome.Info, spanId: Long? = null, correlation: String? = null): TraceEvent = synchronized(lock) {
        val e = TraceEvent(ids.incrementAndGet(), Instant.now(), source, Redact.text(kind).take(80), Redact.text(summary).take(500),
            Redact.details(detail), durationMs, bytesIn, bytesOut, status, outcome, spanId, correlation?.let(Redact::text))
        val key = if (kind in setOf("begin", "end")) spanId ?: e.id else e.id
        val previous = events[key]
        val moment = TraceMoment(e.at, e.kind, e.status, e.outcome)
        val isSpanEnd = kind == "end" && spanId != null
        val operation = if (isSpanEnd && previous != null) e.copy(
            id = previous.id, at = previous.at, endedAt = e.at, timeline = previous.timeline + moment
        ) else e.copy(endedAt = if (kind == "begin") null else e.at,
            durationMs = durationMs ?: if (kind == "begin") null else 0L, timeline = listOf(moment))
        // An end never resurrects a row evicted from or cleared out of the bounded buffer.
        if (!isSpanEnd || previous != null) {
            if (key !in events && events.size == CAPACITY) events.remove(events.keys.first())
            events[key] = operation
        }
        val sec = e.at.epochSecond; val i = Math.floorMod(sec, 60L).toInt()
        if (requestSeconds[i] != sec) { requestSeconds[i] = sec; requestCounts[i] = 0; errorCounts[i] = 0 }
        if (kind == "end" && source in setOf(TraceSource.PveApi, TraceSource.PdmApi, TraceSource.Auth)) requestCounts[i]++
        if (outcome == TraceOutcome.Error) errorCounts[i]++
        revision.value++
        operation
    }
    fun begin(source: TraceSource, summary: String, detail: Map<String,String> = emptyMap(), correlation: String? = null,
        session: Boolean = false): Span = synchronized(lock) {
        val id = ids.incrementAndGet()
        val e = emit(source, "begin", summary, detail, spanId = id, correlation = correlation)
        Span(id, e, session).also { active[id] = it }
    }
    class Span internal constructor(val id: Long, val event: TraceEvent, val session: Boolean) : AutoCloseable {
        private val started = System.nanoTime()
        internal var incoming = 0L; internal var outgoing = 0L; internal var frames = 0L
        internal var frameSecond = 0L; internal var currentFrames = 0L; internal var fps = 0L
        private var ended = false
        fun bytes(bytesIn: Long = 0, bytesOut: Long = 0) = synchronized(lock) { incoming += bytesIn; outgoing += bytesOut }
        fun frame() = synchronized(lock) {
            val second = System.nanoTime() / 1_000_000_000L
            if (second != frameSecond) { fps = if (second == frameSecond + 1) currentFrames else 0; currentFrames = 0; frameSecond = second }
            currentFrames++; frames++
        }
        fun end(status: Int? = null, bytesIn: Long? = null, bytesOut: Long? = null, error: Throwable? = null) = synchronized(lock) {
            if (!ended) {
                ended = true; active.remove(id)
                emit(event.source, "end", event.summary, event.detail + if (error != null) mapOf("error" to error.javaClass.simpleName) else emptyMap(),
                    (System.nanoTime() - started) / 1_000_000, bytesIn ?: incoming, bytesOut ?: outgoing, status,
                    if (error != null || status != null && (status >= 400 || status in 1..99 && event.source in setOf(TraceSource.Tools, TraceSource.Vpn, TraceSource.Console))) TraceOutcome.Error else if (status != null && status != 0 && status !in 200..399) TraceOutcome.Warn else TraceOutcome.Ok, id, event.correlation)
            }
        }
        /** Process spans: only exit 0 is success, so an exit code is never read as an HTTP status. */
        fun endProcess(exitCode: Int) = end(
            status = exitCode,
            error = if (exitCode == 0) null else IllegalStateException("Process exited ($exitCode).")
        )
        override fun close() = end()
    }
    fun vitals(now: Instant = Instant.now()): TraceVitals = synchronized(lock) {
        val req = (59 downTo 0).map { ago -> val s=now.epochSecond-ago; val i=Math.floorMod(s,60L).toInt(); if(requestSeconds[i]==s) requestCounts[i] else 0 }
        val err = (59 downTo 0).map { ago -> val s=now.epochSecond-ago; val i=Math.floorMod(s,60L).toInt(); if(requestSeconds[i]==s) errorCounts[i] else 0 }
        TraceVitals(req.sum(), err.sum(), active.values.map { it.event }, active.values.filter { it.session }.map {
            TraceSession(it.event, it.incoming, it.outgoing, it.frames, if (System.nanoTime()/1_000_000_000L-it.frameSecond <= 1) it.fps else 0)
        }, req, err)
    }
    inline fun <T> call(source: TraceSource, summary: String, detail: Map<String,String> = emptyMap(), block: () -> T): T {
        val span = begin(source, summary, detail)
        try { return block().also { span.end(bytesIn=(it as? String)?.toByteArray(Charsets.UTF_8)?.size?.toLong()) } } catch (t: Throwable) { span.end(error=t); throw t }
    }
    fun jsonLine(e: TraceEvent): String = buildJsonObject {
        put("id",e.id); put("at",e.at.toString()); put("source",e.source.name); put("kind",e.kind); put("summary",Redact.text(e.summary))
        put("start", e.at.toString()); put("end", e.endedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("durationMs", e.durationMs?.let(::JsonPrimitive) ?: JsonNull)
        put("timeline", buildJsonArray { e.timeline.forEach { moment -> add(buildJsonObject {
            put("at", moment.at.toString()); put("kind", moment.kind); put("outcome", moment.outcome.name)
            moment.status?.let { put("status", it) }
        }) } })
        put("outcome",e.outcome.name); put("detail",buildJsonObject { Redact.details(e.detail).forEach { (k,v)->put(k,v) } })
        e.durationMs?.let { put("durationMs",it) }; e.bytesIn?.let { put("bytesIn",it) }; e.bytesOut?.let { put("bytesOut",it) }
        e.status?.let { put("status",it) }; e.spanId?.let { put("spanId",it) }; e.correlation?.let { put("correlation",Redact.text(it)) }
    }.toString()
    /** Owned by the caller's scope: cancel on leaving demo mode. No real I/O. */
    fun demo(scope: CoroutineScope): Job = scope.launch {
        var tick=0
        val session=begin(TraceSource.Console,"VNC · demo guest",mapOf("demo" to "true"),session=true)
        try { while (isActive) {
            val span=begin(TraceSource.PveApi, "GET /cluster/resources",mapOf("demo" to "true"))
            delay(41); span.end(status=200,bytesIn=18432)
            emit(TraceSource.Task,"poll","Task polling · running",mapOf("demo" to "true"))
            session.bytes(42000,120); session.frame()
            liveLoop.value=TraceLiveLoop("demo",Instant.now().plusSeconds(2),2000)
            if (++tick%7==0) emit(TraceSource.LiveLoop,"backoff","Refresh delayed · demo",mapOf("demo" to "true"),outcome=TraceOutcome.Warn)
            delay(1959)
        } } finally { session.end(); if(liveLoop.value.state=="demo") liveLoop.value=TraceLiveLoop() }
    }
}
