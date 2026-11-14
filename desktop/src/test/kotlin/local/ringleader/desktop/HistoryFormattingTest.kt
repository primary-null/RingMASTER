package local.ringleader.desktop
import kotlin.test.*
class HistoryFormattingTest {
 @Test fun iecBoundary() {
  assertEquals("1000 B/s",compactIec(1000.0,true))
  assertEquals("1023 B",compactIec(1023.0,false))
  assertEquals("1.0 KiB/s",compactIec(1024.0,true))
 }
}
