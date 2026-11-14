package local.ringleader.desktop
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import local.ringleader.core.*
class TelemetryPresentationTest {
 @Test fun rampBoundaries() { assertEquals(Tech.Green,ramp(.499));assertEquals(Tech.Amber,ramp(.5));assertEquals(Tech.Amber,ramp(.85));assertEquals(Tech.Red,ramp(.851)) }
 @Test fun shortcutBounds() { assertNull(shortcutPage(0));assertEquals("Desk",shortcutPage(1));assertEquals("Fleet",shortcutPage(2));assertEquals("Storage",shortcutPage(3));assertEquals("Activity",shortcutPage(4));assertEquals("Scan",shortcutPage(5));assertEquals("Bluetooth",shortcutPage(6));assertEquals("VPN",shortcutPage(7));assertEquals("Tools",shortcutPage(8));assertEquals("Under the Hood",shortcutPage(9));assertNull(shortcutPage(10)) }
 @Test fun ratesAreNotCounters() { assertEquals("1.00 KiB/s",formatRate(1024.0));assertEquals("1.00 MiB/s",formatRate(1048576.0));assertEquals("0.99 MiB/s",formatRate(1014.9*1024));assertEquals("999 B/s",formatRate(999.0));assertEquals("0.98 KiB/s",formatRate(1000.0));assertEquals(formatRate(null),formatRate(-1.0));assertEquals(formatRate(null),formatRate(Double.NaN)) }
 @Test fun sortNumericAndMissing() {
  val a=demoOverview.guests.first().copy(id=2,metrics=GuestListMetrics(cpuFraction=.9))
  val b=a.copy(id=10,metrics=null)
  assertEquals(listOf(a,b),listOf(b,a).sortedWith(guestComparator("ID")))
  // The header's down arrow reverses this ascending comparator, so the busier guest leads.
  assertEquals(b,listOf(b,a).sortedWith(guestComparator("CPU")).first())
  assertEquals(a,listOf(b,a).sortedWith(guestComparator("CPU").reversed()).first())
 }
 @Test fun sortUsesLiveHistoryAndSkipsStopped() {
  val running=demoOverview.guests.first()
  val quiet=running.copy(id=1,metrics=GuestListMetrics(cpuFraction=.9,memoryBytes=500))
  val busy=running.copy(id=2,metrics=GuestListMetrics(cpuFraction=.1,memoryBytes=100))
  val stopped=running.copy(id=3,status="stopped",metrics=GuestListMetrics(cpuFraction=.99,memoryBytes=9_000))
  val history=FleetHistory(mapOf(
   quiet.resource to Series(listOf(Sample(java.time.Instant.EPOCH,.05,10,null,null,null))),
   busy.resource to Series(listOf(Sample(java.time.Instant.EPOCH,.80,900,null,null,null))),
  ))
  val byCpu=listOf(quiet,stopped,busy).sortedWith(guestComparator("CPU",history).reversed())
  assertEquals(listOf(2,1,3),byCpu.map { it.id })
  val byMem=listOf(quiet,busy).sortedWith(guestComparator("Memory",history).reversed())
  assertEquals(2,byMem.first().id)
 }
 @Test fun filtersMatchStripAndIncludeTags() {
  val running=demoOverview.guests.first()
  val template=running.copy(id=140,name="ubuntu-template",status="stopped",template=true,tags=listOf("lab"))
  assertTrue(guestVisible(template,"Templates",""))
  assertFalse(guestVisible(template,"Stopped",""))
  assertFalse(guestVisible(template,"Running",""))
  assertTrue(guestVisible(template,"All","lab"))
  assertFalse(guestVisible(running,"All","lab"))
  assertTrue(guestVisible(running,"Running",""))
 }
 @Test fun percentGibAndSparkRejectBadNumbers() {
  assertEquals("—",percent(null)); assertEquals("—",percent(Double.NaN)); assertEquals("—",percent(-0.2)); assertEquals("5.0%",percent(0.05)); assertEquals("50%",percent(0.5))
  assertEquals("—",gib(null)); assertEquals("—",gib(-1)); assertEquals("1.0",gib(1073741824))
  assertEquals(0.05,sparkTop(emptyList()),1e-9)
  assertEquals(0.05,sparkTop(listOf(null,Double.NaN,-1.0)),1e-9)
  assertEquals(0.25,sparkTop(listOf(0.2)),1e-9)
  assertEquals(1.0,sparkTop(listOf(0.9)),1e-9)
  assertEquals(0.05,sparkTop(listOf(0.0),floor=0.0,ceiling=0.0),1e-9)
  assertNull(memoryFraction(1,0)); assertNull(memoryFraction(-1,100)); assertEquals(0.5,memoryFraction(50,100)!!,1e-9)
  assertNull(latestCpu(null,Double.NaN)); assertNull(latestCpu(null,-1.0))
  assertEquals(0.2,latestCpu(Series(listOf(Sample(java.time.Instant.EPOCH,0.2,null,null,null,null))),0.9))
  assertEquals("—",shortUptime(null)); assertEquals("—",shortUptime(-5)); assertEquals("1m",shortUptime(90))
  assertEquals("awaiting data",syncedAgo(null,java.time.Instant.EPOCH))
  assertEquals("synced 3s ago",syncedAgo(java.time.Instant.EPOCH,java.time.Instant.EPOCH.plusSeconds(3)))
 }
}
