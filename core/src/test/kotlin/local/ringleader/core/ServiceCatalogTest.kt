package local.ringleader.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Notes as the Helper-Scripts write them (pve/backend.func `description()`, 2026), for a Jellyfin container. */
private val NOTES_2026 = """
<div align='center'>
  <a href='https://community-scripts.org' target='_blank' rel='noopener noreferrer'>
    <img src='https://raw.githubusercontent.com/community-scripts/core/main/images/logo-description.png' alt='Community-Scripts' width='192' height='112' style='width:192px;height:112px;'/>
  </a>

  <h2 style='font-size: 24px; margin: 20px 0;'>Jellyfin LXC</h2>

  <p style='margin: 16px 0;'>
    <a href='https://community-scripts.org/donate' target='_blank' rel='noopener noreferrer'>
      <img src='https://img.shields.io/badge/%E2%9D%A4%EF%B8%8F-Sponsoring%20%26%20Donations-FF5E5B' alt='Sponsoring and donations' />
    </a>
  </p>

  <p style='margin: 12px 0;'>
    <a href='https://community-scripts.org/scripts/jellyfin' target='_blank' rel='noopener noreferrer'>
      <img src='https://img.shields.io/badge/%F0%9F%93%A6-Open%20Script%20Page-00617f' alt='Open script page' />
    </a>
  </p>

  <span style='margin: 0 10px;'>
    <i class="fa fa-github fa-fw" style="color: #f5f5f5;"></i>
    <a href='https://github.com/community-scripts/ProxmoxVE' target='_blank' rel='noopener noreferrer' style='text-decoration: none; color: #00617f;'>GitHub</a>
  </span>
</div>
""".trimIndent()

/** The 2024–2026 community-scripts notes (misc/build.func), for Nginx Proxy Manager. No script link yet. */
private val NOTES_2025 = """
<div align='center'>
  <a href='https://Helper-Scripts.com' target='_blank' rel='noopener noreferrer'>
    <img src='https://raw.githubusercontent.com/community-scripts/ProxmoxVE/main/misc/images/logo-81x112.png' alt='Logo' style='width:81px;height:112px;'/>
  </a>

  <h2 style='font-size: 24px; margin: 20px 0;'>Nginx Proxy Manager LXC</h2>

  <p style='margin: 16px 0;'>
    <a href='https://ko-fi.com/community_scripts' target='_blank' rel='noopener noreferrer'>
      <img src='https://img.shields.io/badge/&#x2615;-Buy us a coffee-blue' alt='spend Coffee' />
    </a>
  </p>
</div>
""".trimIndent()

/** tteck's notes (tteck/Proxmox misc/build.func), for Pi-hole: a Markdown heading. */
private val NOTES_TTECK = """
<div align='center'><a href='https://Helper-Scripts.com' target='_blank' rel='noopener noreferrer'><img src='https://raw.githubusercontent.com/tteck/Proxmox/main/misc/images/logo-81x112.png'/></a>

  # Pihole LXC

  <a href='https://ko-fi.com/proxmoxhelperscripts'><img src='https://img.shields.io/badge/&#x2615;-Buy me a coffee-blue' /></a>
  </div>
""".trimIndent()

/** A VM's notes (pve/vm-core.func `set_description`): the heading has no "LXC". */
private val NOTES_HAOS_VM = """
<div align='center'>
  <a href='https://community-scripts.org' target='_blank' rel='noopener noreferrer'>
      <img src='https://raw.githubusercontent.com/community-scripts/core/main/images/logo-description.png' alt='Community-Scripts' width='192' height='112' style='width:192px;height:112px;'/>
  </a>
  <h2 style='font-size: 24px; margin: 20px 0;'>Home Assistant OS</h2>
    <a href='https://community-scripts.org/scripts/haos-vm' target='_blank' rel='noopener noreferrer'>
</div>
""".trimIndent()

class ServiceCatalogTest {
    private fun top(hints: GuestHints) = guessServices(hints).firstOrNull()

    @Test fun catalogueIsWellFormed() {
        assertTrue(SERVICE_CATALOGUE.size >= 50, "${SERVICE_CATALOGUE.size}")
        assertEquals(SERVICE_CATALOGUE.size, SERVICE_CATALOGUE.map { it.id }.distinct().size, "ids are unique")
        SERVICE_CATALOGUE.forEach { s ->
            s.names.forEach { assertEquals(serviceWord(it), it, "${s.id} name word $it") }
            assertTrue(s.titles.all { it == it.lowercase() }, s.id)
        }
        val ports = SERVICE_CATALOGUE.associate { it.id to it.main }
        assertEquals(ServiceEndpoint(8123), ports["homeassistant"])
        assertEquals(ServiceEndpoint(8000, secure = true), ports["vaultwarden"])
        assertEquals(ServiceEndpoint(80, path = "/admin"), ports["pihole"])
        assertEquals(ServiceEndpoint(32400, path = "/web"), ports["plex"])
        assertEquals(ServiceEndpoint(8007, secure = true), ports["proxmox-backup-server"])
        assertEquals(ServiceEndpoint(8443, secure = true), ports["proxmox-datacenter-manager"])
    }

    @Test fun theScriptLinkInTheNotesWins() {
        val guess = top(GuestHints("media", listOf("community-script", "media"), NOTES_2026))!!
        assertEquals("jellyfin", guess.service.id)
        assertEquals(HintSource.NotesScript, guess.source)
        assertTrue(guess.score >= 100)
        assertEquals("jellyfin", helperScriptSlug(NOTES_2026))
        assertEquals("Jellyfin", helperScriptApp(NOTES_2026))
    }

    @Test fun theNotesHeadingNamesTheAppInOlderNotes() {
        assertEquals("Nginx Proxy Manager", helperScriptApp(NOTES_2025))
        assertEquals("nginxproxymanager", top(GuestHints("proxy-01", listOf("community-script", "proxy"), NOTES_2025))!!.service.id)
        assertEquals("Pihole", helperScriptApp(NOTES_TTECK))
        val pihole = top(GuestHints("dns", emptyList(), NOTES_TTECK))!!
        assertEquals("pihole", pihole.service.id)
        assertEquals(HintSource.NotesScript, pihole.source)
        assertEquals("haos-vm", top(GuestHints("homeassistant", listOf("community-script"), NOTES_HAOS_VM))!!.service.id)
    }

    @Test fun notesThatAreNotFromTheScriptsGiveNoHeading() {
        assertNull(helperScriptApp("<h2>Grafana</h2> my own notes"))
        // Free notes still count, more weakly.
        val guess = top(GuestHints("box", emptyList(), "Runs Grafana and nothing else."))!!
        assertEquals("grafana", guess.service.id)
        assertEquals(HintSource.Notes, guess.source)
        assertEquals(40, guess.score)
    }

    @Test fun hostnamesTheScriptsChooseMatch() {
        mapOf(
            // Two entries are Home Assistant on 8123 (the OS VM and the container); the more installed one comes first.
            "homeassistant" to "haos-vm", "nginxproxymanager" to "nginxproxymanager", "uptimekuma" to "uptimekuma",
            "proxmox-backup-server" to "proxmox-backup-server", "technitiumdns" to "technitiumdns", "paperless-ngx" to "paperless-ngx",
            "adguard" to "adguard", "vaultwarden" to "vaultwarden", "plex" to "plex", "openwebui" to "openwebui",
        ).forEach { (host, id) -> assertEquals(id, top(GuestHints(host))?.service?.id, host) }
    }

    @Test fun hostnamesPeopleTypeMatch() {
        assertEquals("jellyfin", top(GuestHints("jellyfin-01"))?.service?.id)
        assertEquals("jellyfin", top(GuestHints("Jellyfin2"))?.service?.id)
        assertEquals("grafana", top(GuestHints("mon-grafana"))?.service?.id)
        assertEquals("nginxproxymanager", top(GuestHints("npm"))?.service?.id)
        assertEquals("homeassistant", top(GuestHints("ha"))?.service?.let { if (it.name == "Home Assistant") "homeassistant" else it.id })
        assertEquals("proxmox-backup-server", top(GuestHints("pbs"))?.service?.id)
        assertEquals("vaultwarden", top(GuestHints("myvaultwardenbox"))?.service?.id)
        // A short word inside another word is not a match.
        assertTrue(guessServices(GuestHints("ha-proxy")).none { it.service.name == "Home Assistant" })
        assertTrue(guessServices(GuestHints("complex")).none { it.service.id == "plex" })
        assertTrue(guessServices(GuestHints("debian-13")).isEmpty())
    }

    @Test fun tagsNamingAServiceMatchAndCategoryTagsOnlyHelp() {
        assertEquals("pihole", top(GuestHints("dns1", listOf("pihole")))?.service?.id)
        val adblock = guessServices(GuestHints("dns1", listOf("community-script", "adblock")))
        assertEquals(listOf("adguard", "pihole"), adblock.map { it.service.id })
        assertTrue(adblock.all { it.score == 12 && it.source == HintSource.Tag })
        // With a strong hint, category-only guesses are dropped.
        val strong = guessServices(GuestHints("jellyfin", listOf("community-script", "media")))
        assertEquals(listOf("jellyfin"), strong.map { it.service.id })
        assertEquals(85, strong.single().score, "name 80 plus 5 for a second kind of hint")
        assertTrue(guessServices(GuestHints("box", listOf("community-script"))).isEmpty())
    }

    @Test fun addressesAndPortsComeOutRight() {
        val ha = SERVICE_CATALOGUE.first { it.id == "homeassistant" }
        assertEquals("http://192.0.2.10:8123", serviceAddress("192.0.2.10", ha.main))
        assertEquals("http://[2001:db8::5]:8123", serviceAddress("2001:db8::5", ha.main))
        assertEquals("http://192.0.2.10/admin", serviceAddress("192.0.2.10", ServiceEndpoint(80, path = "/admin")))
        assertEquals("https://192.0.2.10", serviceAddress("192.0.2.10", ServiceEndpoint(443, true)))
        assertEquals("https://192.0.2.10:8000", serviceAddress("192.0.2.10", ServiceEndpoint(8000, true)))
        val ports = portsToFind(guessServices(GuestHints("uptimekuma")))
        assertEquals(3001, ports.first())
        assertTrue(ports.containsAll(COMMON_WEB_PORTS))
        assertTrue(ports.contains(8123) && ports.size == ports.distinct().size && ports.size <= 64)
    }

    @Test fun answersAreIdentifiedByTitleAndPort() {
        assertEquals("grafana", identifyService(3000, "Grafana")?.id)
        assertEquals("homepage", identifyService(3000, "Homepage")?.id)
        assertEquals("adguard", identifyService(3000, "Setup AdGuard Home")?.id)
        assertEquals("Home Assistant", identifyService(8123, "Home Assistant")?.name)
        assertNull(identifyService(8080, "Complex things"))
        assertEquals("vaultwarden", identifyService(8000, "Vaultwarden Web")?.id)
        assertNull(identifyService(8000, null))
    }

    @Test fun notesAddressesKeepLocalOnesAndDropTokens() {
        val notes = "Admin: http://192.0.2.20:8080/admin?token=abc and https://ui.home.arpa/#/x, docs https://docs.example.com/x, " + NOTES_2026
        assertEquals(listOf("http://192.0.2.20:8080/admin", "https://ui.home.arpa"), notesAddresses(notes))
        assertEquals(listOf("http://media:8096"), notesAddresses("see http://media:8096"))
        assertTrue(notesAddresses(NOTES_2025).isEmpty())
        assertTrue(notesAddresses("http://user:pw@192.0.2.1/").isEmpty())
    }

    @Test fun rankingPutsConfirmedGuessesFirstAndClosedOnesLast() {
        val guesses = guessServices(GuestHints("homeassistant", listOf("community-script", "smarthome")))
        val hosts = listOf("192.0.2.10")
        val before = rankSuggestions(guesses, hosts)
        assertEquals("http://192.0.2.10:8123", before.first().url)
        assertEquals(HintSource.Name, before.first().source)
        val answers = listOf(
            ServiceAnswer("192.0.2.10", 8123, PortState.Open, secure = false, status = 200, title = "Home Assistant"),
            ServiceAnswer("192.0.2.10", 9443, PortState.Open, secure = true, untrusted = "self-signed"),
            ServiceAnswer("192.0.2.10", 8080, PortState.Open, secure = false, status = 200, title = "Some page"),
            ServiceAnswer("192.0.2.10", 22, PortState.Open),
        )
        val after = rankSuggestions(guesses, hosts, answers = answers)
        assertEquals(listOf("http://192.0.2.10:8123", "https://192.0.2.10:9443", "http://192.0.2.10:8080"), after.map { it.url })
        assertEquals("Home Assistant", after[0].name)
        assertEquals(HintSource.Found, after[0].source)
        assertEquals("Portainer", after[1].name)
        assertTrue(after[1].detail.contains("certificate not trusted: self-signed"), after[1].detail)
        assertEquals("Some page", after[2].name)

        val closed = rankSuggestions(guesses, hosts, answers = listOf(ServiceAnswer("192.0.2.10", 8123, PortState.Closed),
            ServiceAnswer("192.0.2.10", 3000, PortState.Open, secure = false, status = 200, title = "Grafana")))
        assertEquals("Grafana", closed.first().name)
        assertEquals("Port 8123 did not answer.", closed.last().detail)
    }

    @Test fun notesAddressesRankAboveGuessesAndSuggestionsCarryNoQuery() {
        val guesses = guessServices(GuestHints("grafana"))
        val list = rankSuggestions(guesses, listOf("192.0.2.30"), notes = notesAddresses("http://192.0.2.30:3001/?k=v"))
        assertEquals(listOf("http://192.0.2.30:3001", "http://192.0.2.30:3000"), list.map { it.url })
        assertTrue(list.all { '?' !in it.url && '#' !in it.url })
        assertTrue(rankSuggestions(guesses, emptyList()).isEmpty(), "no host, no address to suggest")
    }

    @Test fun hostsAreOrderedForABrowserOnThisComputer() {
        assertEquals(listOf("192.168.1.20", "10.23.45.20", "172.17.0.1", "100.64.0.7", "198.51.100.4", "media.lan", "2001:db8::5"),
            preferredHosts(listOf("2001:db8::5", "100.64.0.7", "172.17.0.1", "media.lan", "10.23.45.20", "198.51.100.4", "192.168.1.20", "10.23.45.20")))
    }

    @Test fun aNestedProxmoxHostOnPort8006IsNamedProxmoxVeBeforeTheMailGateway() {
        // Found live on a Proxmox VE host: its certificate was not trusted, so no title was read and the port alone named it.
        assertEquals("Proxmox VE", servicesOnPort(8006).first().name)
        assertEquals("Proxmox VE", identifyService(8006, "node-a - Proxmox Virtual Environment")?.name)
        assertEquals("Proxmox Mail Gateway", identifyService(8006, "mail - Proxmox Mail Gateway")?.name)
        assertEquals("Proxmox VE", guessServices(GuestHints("pve-nested")).first().service.name)
    }
}
