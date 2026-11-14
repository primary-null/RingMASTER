package local.ringleader.core
import kotlin.test.*
import java.time.*
class PresentationTest {
 @Test fun sizesAndRates() { assertEquals("1.0 KiB", iec(1024.0)); assertEquals("1.0 MiB/s",iec(1048576.0,true)); assertEquals("Unavailable",iec(Double.NaN)); assertEquals("0 B",iec(0.0)) }
 @Test fun percentagesAndDuration() { assertEquals("25.0%",percentage(.25)); assertEquals("1d 3h 36m",duration(99360)); assertEquals("Yes",yesNo(true)); assertEquals("No",yesNo(false)) }
 @Test fun localDateAndAge() { assertEquals("1970-01-01 00:00:00 Z · 2h ago",dateAndAge(0,Instant.ofEpochSecond(7200),ZoneOffset.UTC)) }
 @Test fun releaseNames() { assertEquals("Ubuntu 26.04.1 LTS",parseOsRelease("NAME=Ubuntu\nPRETTY_NAME=\"Ubuntu 26.04.1 LTS\"")); assertEquals("Debian",parseOsRelease("# ignored\nNAME='Debian'")); assertNull(parseOsRelease("OTHER=x")) }
    @kotlin.test.Test fun taskDurationKeepsSecondsUnderAnHour() {
        kotlin.test.assertEquals("45s", taskDuration(45))
        kotlin.test.assertEquals("2m 10s", taskDuration(130))
        kotlin.test.assertEquals("1h 1m", taskDuration(3660))
        kotlin.test.assertEquals("Unavailable", taskDuration(null))
    }
}
