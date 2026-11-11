package local.ringleader.desktop

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import local.ringleader.core.FeatureStatus
import local.ringleader.core.PackageUpdate
import local.ringleader.core.ServiceHealth
import local.ringleader.core.VOLUME_TYPE_ALL
import local.ringleader.core.VOLUME_TYPE_OTHER

/** Pure helpers behind the read-only panels. The rendering itself is checked by [ReadOnlyPanelsProbeTest] when it is switched on. */
class ReadOnlyViewFormattingTest {
    @Test fun sizesUseBinaryUnitsAndDashForUnknown() {
        assertEquals("—", storageSize(null))
        assertEquals("—", storageSize(-1))
        assertEquals("0 B", storageSize(0))
        assertEquals("1023 B", storageSize(1023))
        assertEquals("1 KiB", storageSize(1024))
        assertEquals("1.0 MiB", storageSize(1L shl 20))
        assertEquals("783 MiB", storageSize(783L shl 20))
        assertEquals("2.3 GiB", storageSize((2.3 * (1L shl 30)).toLong()))
        assertEquals("100 GiB", storageSize(100L shl 30))
        assertEquals("1.00 TiB", storageSize(1L shl 40))
    }

    @Test fun clocksAndDatesFallBackToADash() {
        assertEquals("—", readClock(null))
        assertEquals("—", volumeDate(null))
        assertTrue(Regex("\\d\\d:\\d\\d:\\d\\d").matches(readClock(Instant.parse("2026-11-09T14:03:22Z"))))
        assertTrue(Regex("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d").matches(volumeDate(Instant.parse("2026-11-09T14:03:22Z"))))
    }

    @Test fun logLinesAreToneByPlainKeywordsOnly() {
        assertEquals(SyslogTone.Error, syslogTone("Nov 09 pveproxy[1]: Failed to start"))
        assertEquals(SyslogTone.Error, syslogTone("kernel: segfault at 0"))
        assertEquals(SyslogTone.Error, syslogTone("sshd: permission DENIED"))
        assertEquals(SyslogTone.Warning, syslogTone("pvestatd: WARN storage 'x' timed out"))
        assertEquals(SyslogTone.Plain, syslogTone("pvedaemon[2184]: <root@pam> successful auth for user 'root@pam'"))
        assertEquals(SyslogTone.Plain, syslogTone(""))
        // Whole words only: SMART attribute names and "Prefailure" are not failures.
        assertEquals(SyslogTone.Plain, syslogTone("smartd[884]: SMART Prefailure Attribute: 1 Raw_Read_Error_Rate changed from 80 to 79"))
        assertEquals(SyslogTone.Plain, syslogTone("systemd[1]: Started fail2ban.service"))
        assertEquals(SyslogTone.Error, syslogTone("systemd[1]: pveproxy.service: Failed with result 'exit-code'."))
    }

    @Test fun versionTextShowsTheMoveOrTheNewPackage() {
        fun update(old: String?, new: String?) = PackageUpdate("p", null, null, new, old, null, null, null, null)
        assertEquals("9.2.21 → 9.2.23", updateVersionText(update("9.2.21", "9.2.23")))
        assertEquals("new 1.0", updateVersionText(update(null, "1.0")))
        assertEquals("1.0", updateVersionText(update("1.0", null)))
        assertEquals("—", updateVersionText(update(null, null)))
    }

    @Test fun everyServiceHealthHasALabelAndATone() {
        for (health in ServiceHealth.entries) {
            assertTrue(serviceHealthLabel(health).isNotBlank())
            serviceHealthTone(health)
        }
        assertEquals("Not running", serviceHealthLabel(ServiceHealth.EnabledNotRunning))
        assertEquals(Tech.Red, serviceHealthTone(ServiceHealth.Failed))
        assertEquals(Tech.Green, serviceHealthTone(ServiceHealth.Running))
    }

    @Test fun chipLabelsNameTheContentTypes() {
        assertEquals("All", volumeChipLabel(VOLUME_TYPE_ALL))
        assertEquals("Other", volumeChipLabel(VOLUME_TYPE_OTHER))
        assertEquals(listOf("ISO", "Templates", "Backups", "Disks", "Containers", "Snippets", "Import"),
            listOf("iso", "vztmpl", "backup", "images", "rootdir", "snippets", "import").map(::volumeChipLabel))
        assertEquals("future-type", volumeChipLabel("future-type"))
    }

    @Test fun permissionTonesMatchTheTags() {
        assertEquals(Tech.Green, permissionTone(FeatureStatus.Allowed))
        assertEquals(Tech.Amber, permissionTone(FeatureStatus.Partial))
        assertEquals(Tech.Red, permissionTone(FeatureStatus.Missing))
        assertEquals(Tech.Muted, permissionTone(FeatureStatus.Unknown))
    }
}
