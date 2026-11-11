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
  assertEquals("time,\"cpu\"\n1.0,\n3.0,0.5\n",historyCsv(p,listOf("cpu")))
 }
 @Test fun demoWindows() { historyTimeframes.forEach { t -> historyConsolidations.forEach { cf -> val p=demoNodeHistory(t,cf); assertEquals(historySeconds(t),p.last().time-p.first().time,.01); assertNotNull(p.first().values["pressurecpusome"]) } } }
}
