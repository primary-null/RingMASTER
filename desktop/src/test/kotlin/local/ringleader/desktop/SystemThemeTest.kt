package local.ringleader.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The operating system theme behind "System": Windows, the freedesktop portal, Xfce and GNOME. */
class SystemThemeTest {
    @Test fun themeParsers() {
        assertEquals(true, SystemTheme.parseWindows("    AppsUseLightTheme    REG_DWORD    0x1"))
        assertEquals(false, SystemTheme.parseWindows("    AppsUseLightTheme    REG_DWORD    0x0"))
        assertNull(SystemTheme.parseWindows("ERROR: The system was unable to find the specified registry key or value."))
        assertEquals(false, SystemTheme.parsePortal("(<<uint32 1>>,)"))
        assertEquals(true, SystemTheme.parsePortal("(<uint32 2>,)"))
        assertNull(SystemTheme.parsePortal("(<<uint32 0>>,)"))
        assertNull(SystemTheme.parsePortal("Error: GDBus.Error:org.freedesktop.DBus.Error.ServiceUnknown"))
        assertEquals(false, SystemTheme.parseGnome("'prefer-dark'\n"))
        assertEquals(true, SystemTheme.parseGnome("'prefer-light'\n"))
        assertEquals(true, SystemTheme.parseGnome("'default'\n"))
        assertEquals(true, SystemTheme.parseXfceTheme("Greybird\n"))
        assertEquals(false, SystemTheme.parseXfceTheme("Adwaita-dark\n"))
        assertNull(SystemTheme.parseXfceTheme("  \n"))
    }

    /** A fake tool runner: the first word of the command picks the answer; a missing entry is a missing tool. */
    private fun tools(vararg answers: Pair<String, String>): (List<String>) -> String? = { command ->
        val name = java.io.File(command.first()).name.removeSuffix(".exe")
        answers.firstOrNull { it.first == name }?.second
    }

    @Test fun linuxAsksThePortalThenXfceThenGnome() {
        // The portal answers for GNOME, KDE and others.
        assertTrue(SystemTheme.detect("Linux", "KDE", tools("gdbus" to "(<<uint32 2>>,)")))
        assertFalse(SystemTheme.detect("Linux", "GNOME", tools("gdbus" to "(<<uint32 1>>,)", "gsettings" to "'default'")))
        // No portal preference on Xfce: the theme name decides, before the GNOME schema (which says 'default' there).
        assertTrue(SystemTheme.detect("Linux", "XFCE", tools("gdbus" to "(<<uint32 0>>,)", "xfconf-query" to "Greybird", "gsettings" to "'prefer-dark'")))
        assertFalse(SystemTheme.detect("Linux", "XFCE", tools("xfconf-query" to "Adwaita-dark", "gsettings" to "'default'")))
        // GNOME without a portal behaves as before.
        assertTrue(SystemTheme.detect("Linux", "GNOME", tools("gsettings" to "'default'")))
        assertFalse(SystemTheme.detect("Linux", "GNOME", tools("gsettings" to "'prefer-dark'")))
        // Xfce's tool is not asked on another desktop.
        assertFalse(SystemTheme.detect("Linux", "GNOME", tools("xfconf-query" to "Greybird")))
        // Nothing answers: dark.
        assertFalse(SystemTheme.detect("Linux", null, tools()))
    }

    @Test fun windowsReadsAppsUseLightTheme() {
        assertTrue(SystemTheme.detect("Windows 11", null, tools("reg" to "    AppsUseLightTheme    REG_DWORD    0x1")))
        assertFalse(SystemTheme.detect("Windows 11", null, tools("reg" to "    AppsUseLightTheme    REG_DWORD    0x0")))
        assertFalse(SystemTheme.detect("Windows 11", null, tools()))
        assertFalse(SystemTheme.detect("Mac OS X", null, tools("reg" to "0x1")))
    }
}
