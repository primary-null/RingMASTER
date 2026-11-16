package local.ringleader.core

import kotlin.test.*

/**
 * Tailscale changes after the 2026-11-16 audit: the command shapes, the single status read after a failure, the message
 * table for refusals, the sign-in link and the per-state advice. The fake runner records every command; nothing runs.
 */
class TailscaleMutationTest {
    private fun status(state: String, authUrl: String? = null) =
        """{"BackendState":"$state","AuthURL":${authUrl?.let { "\"$it\"" } ?: "null"},"Self":{"ID":"s","TailscaleIPs":["100.64.0.1"]},
            "Peer":{"x":{"ID":"x","HostName":"exit-gw","TailscaleIPs":["100.64.0.30"],"Online":true,"ExitNodeOption":true}}}"""

    private class Recorder(val os: String, var answer: (List<String>) -> String) {
        val calls = mutableListOf<Pair<List<String>, Long>>()
        val backend = LocalVpnBackend(os, { c, t -> calls += c.map { it.substringAfterLast('\\') } to t; answer(c) },
            { if (it.contains("WireGuard")) null else it }, hostActions = true)
        fun tailscaleCalls() = calls.filter { it.first.firstOrNull()?.startsWith("tailscale") == true }
    }

    private val tunnel = VpnTunnel("tailscale", VpnKind.Tailscale, "Tailscale", VpnState.Down, canToggle = true)

    @Test fun commandShapes() {
        for (os in listOf("Linux", "Windows 11")) {
            val r = Recorder(os) { c -> if (c.last() == "--json") status("Running") else "" }
            r.backend.setTunnel(tunnel, true)
            r.backend.setTunnel(tunnel.copy(state = VpnState.Up), false)
            val peer = parseTailscaleStatus(status("Running")).peers.single()
            r.backend.setExitNode(peer, allowLanAccess = true)
            r.backend.setExitNode(null)
            val exe = if (os == "Linux") "tailscale" else "tailscale.exe"
            assertEquals(listOf(
                listOf(exe, "up") to TAILSCALE_UP_SECONDS,
                listOf(exe, "down") to 30L,
                listOf(exe, "set", "--exit-node=100.64.0.30", "--exit-node-allow-lan-access=true") to 30L,
                listOf(exe, "set", "--exit-node=") to 30L,
            ), r.tailscaleCalls(), os)
            assertTrue(r.tailscaleCalls().none { (c, _) -> c.any { it.startsWith("--timeout") } }, "bare up stays Tailscale's simple up")
        }
    }

    @Test fun aFailedChangeIsReadBackOnceAndNeverRetried() {
        var ups = 0
        val r = Recorder("Linux") { c -> if (c.getOrNull(1) == "up") { ups++; error("Tailscale already in use by DESK\\someone, pid 4242") } else status("Stopped") }
        val e = assertFailsWith<IllegalStateException> { r.backend.setTunnel(tunnel, true) }
        assertEquals(1, ups)
        assertEquals(listOf(listOf("tailscale", "up"), listOf("tailscale", "status", "--json")), r.tailscaleCalls().map { it.first })
        assertEquals("Tailscale is in use by another user signed in to this computer. Only that user can change it now. Tailscale now reports: stopped.", e.message)
        assertFalse(e.message!!.contains("someone"), "the other user's name is not shown")
    }

    @Test fun anUnreadableStatusAfterAFailureIsSaid() {
        val r = Recorder("Linux") { error("connection refused") }
        val e = assertFailsWith<IllegalStateException> { r.backend.setExitNode(null) }
        assertEquals("connection refused Its current state could not be read.", e.message)
    }

    @Test fun messageTable() {
        val running = parseTailscaleStatus(status("Running"))
        val stopped = parseTailscaleStatus(status("Stopped"))
        fun m(error: String, windows: Boolean = false, now: TailscaleStatus? = running, timedOut: Boolean = false) = tailscaleChangeFailure(error, timedOut, 20, now, windows)
        assertEquals("$TAILSCALE_OPERATOR_HINT Tailscale now reports: running.", m("Access denied: prefs write access denied"))
        assertEquals("Tailscale refused the change: access denied. Tailscale now reports: running.", m("Access denied: prefs write access denied", windows = true))
        assertFalse(m("open /var/lib/tailscale: permission denied").contains("operator"), "only the CLI's prefix gets the operator advice")
        assertFalse(m("access denied (lower case, not the CLI prefix)").contains("operator"))
        assertEquals("Tailscale is in use by another user signed in to this computer. Only that user can change it now. Tailscale now reports: stopped.",
            m("Tailscale already in use by DESK\\other, pid 99", windows = true, now = stopped))
        assertEquals("Tailscale is kept always on by your organisation's policy, so it cannot be brought down here. Tailscale now reports: running.",
            m("disconnect not allowed: always-on mode is enabled", windows = true))
        assertEquals("Your organisation's policy asks for a reason to disconnect. Bring Tailscale down from its own app, which asks for one. Tailscale now reports: running.",
            m("disconnect not allowed: reason required", windows = true))
        assertEquals("This computer offers itself as an exit node, so it cannot use another one. Turn off running as an exit node in Tailscale first. Tailscale now reports: running.",
            m("Cannot advertise an exit node and use an exit node at the same time.", windows = true))
        assertEquals("Tailscale did not finish the change within 20 seconds. Tailscale now reports: starting.",
            m("VPN command timed out.", timedOut = true, now = parseTailscaleStatus(status("Starting"))))
        assertEquals("The Tailscale command failed. Its current state could not be read.", m("VPN command failed.", now = null))
        assertEquals("something new Tailscale now reports: NoState.", m("something new", now = parseTailscaleStatus(status("NoState"))))
        // A sign-in or approval wait wins over whatever the CLI said, including a timeout.
        val login = parseTailscaleStatus(status("NeedsLogin", "https://login.example/a/x"))
        assertEquals("Tailscale needs a sign-in before it can connect. Open the sign-in link on this page.", m("VPN command timed out.", timedOut = true, now = login))
        assertEquals("Tailscale needs a sign-in before it can connect.", m("To authenticate, visit:", now = parseTailscaleStatus(status("NeedsLogin"))))
        assertEquals("Tailscale is signed in, but an administrator must approve this computer in the Tailscale admin console.",
            m("VPN command timed out.", timedOut = true, now = parseTailscaleStatus(status("NeedsMachineAuth"))))
    }

    @Test fun signInLinkIsOnlyAPlainHttpsUrl() {
        fun link(url: String?) = signInLink(parseTailscaleStatus(status("NeedsLogin", url)))
        assertEquals("https://login.example/a/abc123", link("https://login.example/a/abc123"))
        assertNull(link(null)); assertNull(link(""))
        assertNull(link("http://login.example/a/x"), "plain http is not offered")
        assertNull(link("file:///etc/passwd")); assertNull(link("javascript:alert(1)"))
        assertNull(link("https://"), "no host"); assertNull(link("https://login.example/a/x y"))
        assertNull(link("https://login.example/" + "a".repeat(3000)))
        assertNull(signInLink(null))
    }

    @Test fun adviceDependsOnStateAndOperatingSystem() {
        fun advice(state: String, platform: String, url: String? = null) = tailscaleStateAdvice(parseTailscaleStatus(status(state, url)), platform)
        assertNull(advice("Running", "Windows"))
        assertTrue(advice("NeedsLogin", "Windows", "https://login.example/a/x")!!.contains("sign-in link below"))
        assertTrue(advice("NeedsLogin", "Linux", "https://login.example/a/x")!!.contains("sign-in link below"))
        assertTrue(advice("NeedsLogin", "Windows")!!.contains("Tailscale icon in the notification area"))
        assertTrue(advice("NeedsLogin", "Linux")!!.contains("sudo tailscale login"), "Linux has no tray app to open")
        assertFalse(advice("NeedsLogin", "Linux")!!.contains("app"))
        for (os in listOf("Windows", "Linux")) assertTrue(advice("NeedsMachineAuth", os)!!.contains("administrator must approve"), "machine approval is not a sign-in")
        assertEquals("Tailscale is stopped. Bring it up above to reach the tailnet.", advice("Stopped", "Linux"))
        assertEquals("Tailscale is starting.", advice("Starting", "Linux"))
        assertEquals("Tailscale is nostate.", advice("NoState", "Linux"))
    }
}
