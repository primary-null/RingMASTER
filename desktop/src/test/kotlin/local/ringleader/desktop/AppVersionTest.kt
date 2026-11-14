package local.ringleader.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class AppVersionTest {
    @Test fun displayedVersionMatchesBuildVersion() {
        assertEquals(System.getProperty("ringleader.buildVersion"), AppVersion.value)
    }
}
