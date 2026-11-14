package local.ringleader.desktop

import local.ringleader.desktop.web.WebServer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RingMASTER is AGPL-3.0-only. Every place that states the licence has to say the same thing. */
class LicenceTest {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "settings.gradle.kts").isFile) it else it.parentFile }
    private fun repo(path: String) = File(root, path).readText()

    @Test fun theAppJarCarriesTheLicenceText() {
        val text = AppData.licence()
        assertTrue(text.trimStart().startsWith("GNU AFFERO GENERAL PUBLIC LICENSE"), text.take(80))
        assertContains(text, "Version 3, 19 November 2007")
        assertContains(text, "13. Remote Network Interaction; Use with the GNU General Public License.")
        assertContains(text, "END OF TERMS AND CONDITIONS")
        // The packed copy is the root LICENSE file, whatever line endings the checkout gave it.
        assertEquals(repo("LICENSE").replace("\r\n", "\n"), text.replace("\r\n", "\n"))
    }

    @Test fun aboutGivesTheLegalNotice() {
        for (part in listOf("Copyright 2026", "GNU Affero General Public License", "AGPL-3.0-only", "no warranty", SOURCE_URL))
            assertContains(LICENCE_SUMMARY, part)
    }

    /** AGPL section 13: people using RingMASTER over a network are offered its source on the page itself. */
    @Test fun theBrowserPageOffersTheSource() {
        val page = WebServer::class.java.getResource("/web/index.html")!!.readText()
        assertContains(page, "<a href=\"$SOURCE_URL\" target=\"_blank\" rel=\"noopener noreferrer\"")
        assertContains(page, "Source (AGPL-3.0)")
        // The link sits outside the hashed script and style, and carries no inline style or handler the policy would block.
        val link = page.substringAfter("<a ").substringBefore("</a>")
        assertFalse(link.contains("style=") || link.contains("onclick"), link)
    }

    @Test fun packageMetadataAndDocumentsNameTheSameLicence() {
        assertContains(repo("desktop/build.gradle.kts"), "rpmLicenseType = \"AGPL-3.0-only\"")
        assertContains(repo("desktop/build.gradle.kts"), "copyright = \"Copyright (C) 2026 primary-null\"")
        assertContains(repo("NOTICE"), "Copyright (C) 2026 primary-null")
        assertContains(repo("packaging/arch/PKGBUILD"), "license=(AGPL-3.0-only)")
        assertContains(repo("NOTICE"), "SPDX-License-Identifier: AGPL-3.0-only")
        assertContains(repo("NOTICE"), SOURCE_URL)
        assertContains(repo("README.md"), "`AGPL-3.0-only`")
        assertContains(repo("RELEASING.md"), "AGPL-3.0-only")
        assertContains(repo("desktop/resources/common/THIRD-PARTY-NOTICES.txt"), "AGPL-3.0-only")
    }
}
