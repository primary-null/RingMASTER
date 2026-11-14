package local.ringleader.core
import kotlin.test.*
class HistoryTest {
 @Test fun paths() {
  historyTimeframes.forEach { t -> historyConsolidations.forEach { cf -> assertEquals("/rrddata?timeframe=$t&cf=$cf",historySuffix(t,cf)) } }
  assertFailsWith<IllegalArgumentException> { historySuffix("hour&evil", "MAX") }
  assertFailsWith<IllegalArgumentException> { historySuffix("hour", "MAXIMUM") }
 }
 @Test fun fieldsAndGaps() {
  val body="""{"data":[{"time":2,"cpu":null},{"time":1,"maxcpu":4,"maxmem":1024,"memused":512,"memtotal":1024,"swapused":1,"swaptotal":2,"loadavg":3,"iowait":0.1,"pressurecpusome":1,"pressureiosome":2,"pressureiofull":3,"pressurememorysome":4,"pressurememoryfull":5}]}"""
  val p=parseHistory(body)
  assertEquals(1.0,p.first().time); assertEquals(5.0,p.first().values["pressurememoryfull"])
  assertNull(p.last().values["cpu"]); assertNull(p.last().values["pressurecpusome"])
 }
 @Test fun zoomNearestCsv() {
  val p=listOf(HistoryPoint(1.0,mapOf("cpu" to null)),HistoryPoint(3.0,mapOf("cpu" to .5)))
  assertEquals(1.0..3.0,historyRange(3.0,1.0)); assertNull(historyRange(1.0,1.0))
  assertEquals(p.last(),nearestHistory(p,2.8)); assertNull(nearestHistory(emptyList(),1.0))
  assertEquals("time,time_utc,\"cpu\"\n1,1970-01-01T00:00:01Z,\n3,1970-01-01T00:00:03Z,0.5\n",historyCsv(p,listOf("cpu")))
 }
 @Test fun csvTimeIsIsoUtcBesideTheNumber() {
  assertEquals("2026-11-11T09:30:00Z",historyUtc(1794389400.0))
  assertEquals("2026-11-11T09:30:00.500Z",historyUtc(1794389400.5))
  assertEquals("",historyUtc(Double.NaN)); assertEquals("",historyUtc(Double.POSITIVE_INFINITY))
  val csv=historyCsv(listOf(HistoryPoint(1794389400.0,mapOf("netin" to 12.0))),listOf("netin")).lines()
  assertEquals("time,time_utc,\"netin\"",csv[0]); assertEquals("1794389400,2026-11-11T09:30:00Z,12.0",csv[1])
 }
 @Test fun theCursorSnapsToOneSample() {
  val p=listOf(HistoryPoint(0.0,emptyMap()),HistoryPoint(600.0,emptyMap()),HistoryPoint(3600.0,emptyMap()))
  // A pointer at 40% of an hour is 1440 s: the nearest sample is the one at 600 s, not the pointer's own time.
  assertEquals(600.0,historyAt(p,.4)!!.time)
  assertEquals(3600.0,historyAt(p,.9)!!.time)
  assertEquals(0.0,historyAt(p,-3.0)!!.time); assertEquals(3600.0,historyAt(p,7.0)!!.time); assertEquals(0.0,historyAt(p,Double.NaN)!!.time)
  assertNull(historyAt(emptyList(),.5))
 }
 @Test fun axisTimeFollowsTheVisibleSpan() {
  assertEquals("HH:mm:ss",historyAxisPattern(historySeconds("hour")))
  assertEquals("dd MMM HH:mm",historyAxisPattern(historySeconds("day")))
  assertEquals("dd MMM HH:mm",historyAxisPattern(historySeconds("week")))
  assertEquals("dd MMM",historyAxisPattern(historySeconds("month")))
  assertEquals("dd MMM yy",historyAxisPattern(historySeconds("year")))
  // A week or month zoomed into a few hours shows times, not the same date five times.
  assertEquals("dd MMM HH:mm",historyAxisPattern(5*3600.0))
  assertEquals("HH:mm:ss",historyAxisPattern(40*60.0))
  assertEquals("HH:mm:ss",historyReadoutPattern(historySeconds("hour")))
  assertEquals("dd MMM HH:mm",historyReadoutPattern(historySeconds("month")))
  assertEquals("dd MMM yy HH:mm",historyReadoutPattern(historySeconds("year")))
 }
 @Test fun demoWindows() { historyTimeframes.forEach { t -> historyConsolidations.forEach { cf -> val p=demoNodeHistory(t,cf); assertEquals(historySeconds(t),p.last().time-p.first().time,.01); assertNotNull(p.first().values["pressurecpusome"]) } } }
}
