package local.ringleader.core

/**
 * Known self-hosted services and where they listen, for Pin a service. Plain data: add a line to [SERVICE_CATALOGUE]
 * to teach the app another service. Sources are in reviews/2026-11-12-round3/S1-SERVICE-PORTS.md (the Proxmox VE
 * Helper-Scripts pages and scripts, and each project's own documentation).
 */
data class ServiceEndpoint(val port: Int, val secure: Boolean = false, val path: String = "/") {
    init {
        require(port in 1..65535) { "Port out of range." }
        require(path.startsWith("/") && '?' !in path && '#' !in path) { "A path starts with / and has no query." }
    }
    val scheme get() = if (secure) "https" else "http"
}

/**
 * One service. [id] is its Helper-Scripts slug where it has one. [names] are hostname words, written as
 * [serviceWord] makes them (lower case letters and digits). [tags] are the tags the scripts set. [titles] are words
 * that its page title or answer contains. The first endpoint is where the browser should go.
 */
data class KnownService(
    val id: String,
    val name: String,
    val endpoints: List<ServiceEndpoint>,
    val names: List<String>,
    val tags: List<String> = emptyList(),
    val titles: List<String> = emptyList(),
    val note: String? = null,
) {
    init { require(endpoints.isNotEmpty() && names.isNotEmpty()) }
    val main get() = endpoints.first()
}

private fun http(port: Int, path: String = "/") = ServiceEndpoint(port, false, path)
private fun https(port: Int, path: String = "/") = ServiceEndpoint(port, true, path)

/** Ordered by how often the Helper-Scripts install them (30-day counts, 2026-11-12), then by kind. Order breaks ties. */
val SERVICE_CATALOGUE: List<KnownService> = listOf(
    KnownService("immich", "Immich", listOf(http(2283)), listOf("immich"), listOf("photos"), listOf("immich")),
    KnownService("adguard", "AdGuard Home", listOf(http(3000), http(80)), listOf("adguard", "adguardhome", "agh"), listOf("adblock"), listOf("adguard"),
        "Port 3000 is the first-run wizard. After it, the admin page is on the port chosen there, 80 by default."),
    KnownService("jellyfin", "Jellyfin", listOf(http(8096)), listOf("jellyfin"), listOf("media"), listOf("jellyfin")),
    KnownService("haos-vm", "Home Assistant", listOf(http(8123)), listOf("homeassistant", "homeassistantos", "haos", "hass", "ha"), listOf("smarthome", "automation"), listOf("home assistant", "home-assistant")),
    KnownService("nginxproxymanager", "Nginx Proxy Manager", listOf(http(81)), listOf("nginxproxymanager", "npm", "proxymanager"), listOf("proxy"), listOf("nginx proxy manager"),
        "Ports 80 and 443 are the proxy itself. The admin page is on 81."),
    KnownService("pihole", "Pi-hole", listOf(http(80, "/admin"), https(443, "/admin")), listOf("pihole"), listOf("adblock"), listOf("pi-hole"),
        "The admin page is under /admin."),
    KnownService("frigate", "Frigate", listOf(http(5000), https(8971)), listOf("frigate"), listOf("nvr"), listOf("frigate")),
    KnownService("ollama", "Ollama", listOf(http(11434)), listOf("ollama"), listOf("ai"), listOf("ollama"), "An API, not a web page."),
    KnownService("vaultwarden", "Vaultwarden", listOf(https(8000)), listOf("vaultwarden", "bitwarden", "vw"), listOf("password-manager"), listOf("vaultwarden", "bitwarden"),
        "https with a self-signed certificate; it does not work over http."),
    KnownService("openwebui", "Open WebUI", listOf(http(8080)), listOf("openwebui", "owui"), listOf("ai", "interface"), listOf("open webui")),
    KnownService("paperless-ngx", "Paperless-ngx", listOf(http(8000)), listOf("paperlessngx", "paperless"), listOf("document", "management"), listOf("paperless")),
    KnownService("uptimekuma", "Uptime Kuma", listOf(http(3001)), listOf("uptimekuma", "kuma"), listOf("monitoring", "analytics"), listOf("uptime kuma")),
    KnownService("portainer", "Portainer", listOf(https(9443), http(9000)), listOf("portainer"), emptyList(), listOf("portainer"),
        "https with a self-signed certificate. Usually inside another container."),
    KnownService("unifi-os-server", "UniFi OS Server", listOf(https(11443)), listOf("unifiosserver", "unifios", "uos"), listOf("network"), listOf("unifi")),
    KnownService("nextcloud-vm", "Nextcloud", listOf(https(443), http(80)), listOf("nextcloud", "nextcloudvm", "nextcloudpi", "ncp"), listOf("cloud"), listOf("nextcloud"),
        "Opens only on an address listed in its trusted domains."),
    KnownService("qbittorrent", "qBittorrent", listOf(http(8090), http(8080)), listOf("qbittorrent", "qbit"), listOf("torrent"), listOf("qbittorrent")),
    KnownService("plex", "Plex", listOf(http(32400, "/web")), listOf("plex", "plexmediaserver", "pms"), listOf("media"), listOf("plex")),
    KnownService("homepage", "Homepage", listOf(http(3000)), listOf("homepage"), listOf("dashboard"), listOf("homepage"),
        "Answers only on the addresses it was set up for."),
    KnownService("proxmox-backup-server", "Proxmox Backup Server", listOf(https(8007)), listOf("proxmoxbackupserver", "pbs"), listOf("backup"), listOf("proxmox backup server")),
    KnownService("proxmox-ve", "Proxmox VE", listOf(https(8006)), listOf("pve", "proxmoxve"), emptyList(), listOf("proxmox virtual environment"),
        "https with a self-signed certificate unless one was installed."),
    KnownService("n8n", "n8n", listOf(http(5678)), listOf("n8n"), listOf("automation"), listOf("n8n")),
    KnownService("homarr", "Homarr", listOf(http(7575)), listOf("homarr"), listOf("arr", "dashboard"), listOf("homarr")),
    KnownService("technitiumdns", "Technitium DNS", listOf(http(5380)), listOf("technitiumdns", "technitium"), listOf("dns"), listOf("technitium")),
    KnownService("grafana", "Grafana", listOf(http(3000)), listOf("grafana"), listOf("monitoring", "visualization"), listOf("grafana")),
    KnownService("homeassistant", "Home Assistant", listOf(http(8123)), listOf("homeassistant", "hass", "ha"), listOf("smarthome", "automation", "docker"), listOf("home assistant", "home-assistant")),
    KnownService("zabbix", "Zabbix", listOf(http(80, "/zabbix")), listOf("zabbix"), listOf("monitoring"), listOf("zabbix")),
    KnownService("radarr", "Radarr", listOf(http(7878)), listOf("radarr"), listOf("arr"), listOf("radarr")),
    KnownService("sonarr", "Sonarr", listOf(http(8989)), listOf("sonarr"), listOf("arr"), listOf("sonarr")),
    KnownService("prowlarr", "Prowlarr", listOf(http(9696)), listOf("prowlarr"), listOf("arr"), listOf("prowlarr")),
    KnownService("lidarr", "Lidarr", listOf(http(8686)), listOf("lidarr"), listOf("arr"), listOf("lidarr")),
    KnownService("bazarr", "Bazarr", listOf(http(6767)), listOf("bazarr"), listOf("arr"), listOf("bazarr")),
    KnownService("seerr", "Seerr", listOf(http(5055)), listOf("seerr", "overseerr", "jellyseerr"), listOf("media"), listOf("seerr")),
    KnownService("npmplus", "NPMplus", listOf(https(81)), listOf("npmplus"), listOf("proxy", "nginx"), listOf("npmplus")),
    KnownService("omada", "Omada Controller", listOf(https(8043)), listOf("omada", "omadacontroller"), listOf("tp-link", "controller"), listOf("omada")),
    KnownService("zigbee2mqtt", "Zigbee2MQTT", listOf(http(9442), http(8080)), listOf("zigbee2mqtt", "z2m"), listOf("zigbee", "smarthome", "mqtt"), listOf("zigbee2mqtt")),
    KnownService("stirling-pdf", "Stirling-PDF", listOf(http(8080)), listOf("stirlingpdf", "stirling"), listOf("pdf-editor"), listOf("stirling")),
    KnownService("navidrome", "Navidrome", listOf(http(4533)), listOf("navidrome"), listOf("music"), listOf("navidrome")),
    KnownService("proxmox-datacenter-manager", "Proxmox Datacenter Manager", listOf(https(8443)), listOf("proxmoxdatacentermanager", "pdm"), listOf("datacenter"), listOf("proxmox datacenter manager")),
    KnownService("forgejo", "Forgejo", listOf(http(3000)), listOf("forgejo"), listOf("git"), listOf("forgejo")),
    KnownService("gitea", "Gitea", listOf(http(3000)), listOf("gitea"), listOf("git"), listOf("gitea")),
    KnownService("apache-guacamole", "Apache Guacamole", listOf(http(8080, "/guacamole")), listOf("apacheguacamole", "guacamole"), listOf("remote"), listOf("guacamole")),
    KnownService("mealie", "Mealie", listOf(http(9000)), listOf("mealie"), listOf("recipes"), listOf("mealie")),
    KnownService("actualbudget", "Actual Budget", listOf(https(5006)), listOf("actualbudget", "actual"), listOf("finance"), listOf("actual")),
    KnownService("authentik", "authentik", listOf(https(9443), http(9000)), listOf("authentik"), listOf("auth"), listOf("authentik")),
    KnownService("heimdall-dashboard", "Heimdall", listOf(http(7990)), listOf("heimdalldashboard", "heimdall"), listOf("dashboard"), listOf("heimdall")),
    KnownService("beszel", "Beszel", listOf(http(8090)), listOf("beszel"), listOf("monitoring"), listOf("beszel")),
    KnownService("audiobookshelf", "Audiobookshelf", listOf(http(13378)), listOf("audiobookshelf", "abs"), listOf("audiobook", "podcast"), listOf("audiobookshelf")),
    KnownService("syncthing", "Syncthing", listOf(http(8384)), listOf("syncthing"), listOf("sync"), listOf("syncthing")),
    KnownService("checkmk", "Checkmk", listOf(http(80, "/monitoring")), listOf("checkmk"), listOf("monitoring"), listOf("checkmk")),
    KnownService("dockge", "Dockge", listOf(http(5001)), listOf("dockge"), listOf("docker"), listOf("dockge")),
    KnownService("wikijs", "Wiki.js", listOf(http(3000)), listOf("wikijs", "wiki"), listOf("wiki"), listOf("wiki.js")),
    KnownService("sabnzbd", "SABnzbd", listOf(http(7777), http(8080)), listOf("sabnzbd", "sab"), listOf("downloader"), listOf("sabnzbd")),
    KnownService("wireguard", "WGDashboard", listOf(http(10086)), listOf("wireguard", "wgdashboard", "wg"), listOf("vpn"), listOf("wgdashboard")),
    KnownService("cockpit", "Cockpit", listOf(https(9090)), listOf("cockpit"), emptyList(), listOf("cockpit")),
    KnownService("node-red", "Node-RED", listOf(http(1880)), listOf("nodered"), listOf("automation"), listOf("node-red")),
    KnownService("tautulli", "Tautulli", listOf(http(8181)), listOf("tautulli"), listOf("media"), listOf("tautulli")),
    KnownService("transmission", "Transmission", listOf(http(9091, "/transmission")), listOf("transmission"), listOf("torrent"), listOf("transmission")),
    KnownService("esphome", "ESPHome", listOf(http(6052)), listOf("esphome"), listOf("automation"), listOf("esphome")),
    KnownService("emby", "Emby", listOf(http(8096)), listOf("emby"), listOf("media"), listOf("emby")),
    KnownService("proxmox-mail-gateway", "Proxmox Mail Gateway", listOf(https(8006)), listOf("proxmoxmailgateway", "pmg"), listOf("mail"), listOf("proxmox mail gateway")),
    KnownService("unifi", "UniFi Network", listOf(https(8443)), listOf("unifi", "unifinetwork", "unificontroller"), listOf("network"), listOf("unifi")),
)

/** Web ports worth a look on any guest, after the ones a guess points at. */
val COMMON_WEB_PORTS = listOf(80, 443, 8080, 8443, 3000, 8000, 8081, 8888, 5000, 9000, 9090, 9443)

/** Lower-case letters and digits only: "Nginx Proxy-Manager" becomes "nginxproxymanager". */
fun serviceWord(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

/** What the app knows about a guest that hints at what it runs. */
data class GuestHints(val name: String, val tags: List<String> = emptyList(), val description: String? = null)

/** Where a suggestion came from, strongest first. */
enum class HintSource(val label: String) {
    Found("answered"), NotesAddress("address in the notes"), NotesScript("helper-script notes"), Notes("the notes"),
    Name("the guest name"), Tag("a tag"), Port("an open port"),
}

data class ServiceGuess(val service: KnownService, val score: Int, val source: HintSource, val why: String)

/** The Helper-Scripts slug from the notes' "Open script page" link (2026 and later). */
private val SCRIPT_LINK = Regex("community-scripts\\.org/scripts/([a-z0-9][a-z0-9-]{0,60})", RegexOption.IGNORE_CASE)
/** The app heading: `<h2 …>Jellyfin LXC</h2>` (2024 on) or `# Jellyfin LXC` (tteck). */
private val SCRIPT_HEADING = Regex("<h2[^>]*>([^<]{1,80})</h2>|^\\s*#\\s+([^\\n<]{1,80})$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
private val HELPER_MARK = Regex("helper-scripts\\.com|community-scripts|tteck/proxmox", RegexOption.IGNORE_CASE)

/** True when the notes look like the ones a Helper-Scripts install writes. */
fun helperScriptNotes(description: String?): Boolean = description != null && HELPER_MARK.containsMatchIn(description)

/** The app the notes name, as written ("Home Assistant"), without the " LXC" or " VM" suffix. */
fun helperScriptApp(description: String?): String? {
    if (!helperScriptNotes(description)) return null
    val match = SCRIPT_HEADING.find(description!!) ?: return null
    val raw = (match.groupValues[1].ifBlank { match.groupValues[2] }).trim()
    return raw.replace(Regex("\\s+(LXC|VM|CT)$", RegexOption.IGNORE_CASE), "").trim().ifBlank { null }
}

fun helperScriptSlug(description: String?): String? = description?.let { SCRIPT_LINK.find(it)?.groupValues?.get(1)?.lowercase() }

/**
 * Ranked guesses from the guest's notes, name and tags. Scores: the script link in the notes 100, the notes' heading 90,
 * the hostname equal to a service word 80 (with a trailing number 75), a hostname word 70, a longer word inside the
 * hostname 55, a tag naming the service 60, the service named in free notes 40, a category tag 12. A service hinted
 * at by more than one kind of hint gains 5 for each extra kind. Ties keep catalogue order.
 */
fun guessServices(hints: GuestHints, catalogue: List<KnownService> = SERVICE_CATALOGUE, limit: Int = 5): List<ServiceGuess> {
    val name = hints.name.trim().lowercase()
    val whole = serviceWord(name)
    val bare = whole.trimEnd { it.isDigit() }
    // Words of three letters or more: "ha-proxy" is not Home Assistant, but "ha" on its own is.
    val parts = name.split(Regex("[^a-z0-9]+")).map { it.trimEnd { c -> c.isDigit() } }.filter { it.length >= 3 }.toSet()
    val tags = hints.tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() && it != "community-script" }
    val description = hints.description.orEmpty()
    val slug = helperScriptSlug(description)
    val app = helperScriptApp(description)?.let(::serviceWord)
    val plainNotes = description.replace(Regex("<[^>]*>"), " ").lowercase()
    val guesses = catalogue.mapIndexedNotNull { index, service ->
        val found = mutableListOf<Triple<Int, HintSource, String>>()
        if (slug != null && slug == service.id) found += Triple(100, HintSource.NotesScript, "The notes link to its helper script.")
        if (app != null && (app == serviceWord(service.name) || app in service.names)) found += Triple(90, HintSource.NotesScript, "The notes name it.")
        when {
            whole.isNotEmpty() && whole in service.names -> found += Triple(80, HintSource.Name, "The guest is named after it.")
            bare.isNotEmpty() && bare != whole && bare in service.names -> found += Triple(75, HintSource.Name, "The guest is named after it.")
            parts.any { it in service.names } -> found += Triple(70, HintSource.Name, "The guest name contains it.")
            service.names.any { it.length >= 5 && it in whole } -> found += Triple(55, HintSource.Name, "The guest name contains it.")
        }
        if (tags.any { serviceWord(it) in service.names }) found += Triple(60, HintSource.Tag, "A tag names it.")
        if (slug == null && app == null && serviceWord(service.name).length >= 4 &&
            Regex("\\b${Regex.escape(service.name.lowercase())}\\b").containsMatchIn(plainNotes)) found += Triple(40, HintSource.Notes, "The notes mention it.")
        val category = tags.filter { it in service.tags }
        if (category.isNotEmpty()) found += Triple(12, HintSource.Tag, "Tagged ${category.first()}.")
        if (found.isEmpty()) return@mapIndexedNotNull null
        val best = found.maxBy { it.first }
        val kinds = found.map { it.second }.toSet().size
        index to ServiceGuess(service, best.first + 5 * (kinds - 1), best.second, best.third)
    }
    // A strong hint makes category-only guesses noise: "media" would otherwise list every media server.
    val strong = guesses.any { it.second.score >= 40 }
    return guesses.filter { !strong || it.second.score >= 40 }
        .sortedWith(compareByDescending<Pair<Int, ServiceGuess>> { it.second.score }.thenBy { it.first })
        .map { it.second }.take(limit)
}

/** An http(s) address for [host] (an IPv6 literal gets brackets). The default port for the scheme is left out. */
fun serviceAddress(host: String, endpoint: ServiceEndpoint): String {
    val literal = if (':' in host && !host.startsWith("[")) "[$host]" else host
    val port = if ((endpoint.secure && endpoint.port == 443) || (!endpoint.secure && endpoint.port == 80)) "" else ":${endpoint.port}"
    val path = if (endpoint.path == "/") "" else endpoint.path
    return "${endpoint.scheme}://$literal$port$path"
}

/** The catalogue services that list [port], best guesses first. */
fun servicesOnPort(port: Int, guesses: List<ServiceGuess> = emptyList(), catalogue: List<KnownService> = SERVICE_CATALOGUE): List<KnownService> {
    val guessed = guesses.map { it.service }.filter { s -> s.endpoints.any { it.port == port } }
    return (guessed + catalogue.filter { s -> s.endpoints.any { it.port == port } }).distinct()
}

/**
 * The service an answer comes from: a catalogue service on that port whose title words appear in the page title or the
 * Server header, else any catalogue service whose title words appear, else null.
 */
fun identifyService(port: Int, title: String?, server: String? = null, catalogue: List<KnownService> = SERVICE_CATALOGUE): KnownService? {
    val text = "${title.orEmpty()} ${server.orEmpty()}".lowercase()
    if (text.isBlank()) return null
    // Whole words only: "plex" is not in "Complex".
    fun hit(s: KnownService) = s.titles.any { word -> Regex("(^|[^a-z0-9])${Regex.escape(word)}($|[^a-z0-9])").containsMatchIn(text) }
    return catalogue.firstOrNull { s -> s.endpoints.any { it.port == port } && hit(s) } ?: catalogue.firstOrNull(::hit)
}

/**
 * The ports to look at, in order: the ports of the guesses, the common web ports, then every other catalogue port.
 * At most [limit].
 */
fun portsToFind(guesses: List<ServiceGuess>, catalogue: List<KnownService> = SERVICE_CATALOGUE, limit: Int = 64): List<Int> =
    (guesses.flatMap { g -> g.service.endpoints.map { it.port } } + COMMON_WEB_PORTS + catalogue.flatMap { s -> s.endpoints.map { it.port } })
        .distinct().take(limit)

/**
 * Addresses written in the notes that point at this guest: an IP literal, a single-label name, a `.local`, `.lan`,
 * `.home`, `.internal` or `.home.arpa` name, or one of [hosts]. Links to public sites (the script's GitHub, badges) are
 * not service addresses and are left out.
 */
fun notesAddresses(description: String?, hosts: List<String> = emptyList()): List<String> {
    if (description.isNullOrBlank()) return emptyList()
    val found = Regex("https?://[^\\s\"'<>()]+", RegexOption.IGNORE_CASE).findAll(description).map { it.value.trimEnd('.', ',', ';', ')') }
    return found.mapNotNull { raw ->
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return@mapNotNull null
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return@mapNotNull null
        val local = isIpLiteral(host) || '.' !in host || hosts.any { it.equals(host, true) } ||
            listOf(".local", ".lan", ".home", ".internal", ".home.arpa").any { host.endsWith(it) }
        if (!local || uri.rawUserInfo != null) return@mapNotNull null
        // Only scheme, host, port and path: a query or fragment can carry a token.
        val path = uri.rawPath.orEmpty().let { if (it == "/") "" else it }
        val literal = if (':' in host) "[$host]" else host
        "${uri.scheme.lowercase()}://$literal${if (uri.port > 0) ":${uri.port}" else ""}$path"
    }.distinct().take(3).toList()
}

private fun isIpLiteral(host: String): Boolean =
    ScanRules.ipv4(host) != null || (':' in host && runCatching { java.net.InetAddress.getByName(host) is java.net.Inet6Address }.getOrDefault(false))

/** One line the form offers. [url] has no query, fragment or user-info. */
data class ServiceSuggestion(val name: String, val url: String, val source: HintSource, val detail: String, val score: Int)

/**
 * The form's suggestions from the guesses, the notes and, after Find services, the answers. An answer that confirms a
 * guess ranks first; an answer on its own next (a known service before a plain web page); then addresses in the notes,
 * then the unconfirmed guesses. A guess whose port was found closed drops to the end with that said. One line per
 * address, at most [limit].
 */
fun rankSuggestions(
    guesses: List<ServiceGuess>, hosts: List<String>, notes: List<String> = emptyList(), answers: List<ServiceAnswer>? = null, limit: Int = 6,
): List<ServiceSuggestion> {
    val host = hosts.firstOrNull()
    val out = mutableListOf<ServiceSuggestion>()
    val web = answers.orEmpty().filter { it.state == PortState.Open && it.secure != null }
    web.forEach { answer ->
        // The page title names the service best; then a guess that listens on this port; then, with no title to go by
        // (an untrusted certificate stops the exchange before the page), the catalogue's service for this port.
        val identified = identifyService(answer.port, answer.title, answer.server)
        val onPort = guesses.firstOrNull { g -> g.service.endpoints.any { it.port == answer.port } }
        val service = identified ?: onPort?.service ?: if (answer.title == null) servicesOnPort(answer.port).firstOrNull() else null
        val guess = guesses.firstOrNull { it.service == identified }
        val endpoint = service?.endpoints?.firstOrNull { it.port == answer.port && it.secure == answer.secure }
        val url = serviceAddress(answer.host, ServiceEndpoint(answer.port, answer.secure == true, endpoint?.path ?: "/"))
        val name = service?.name ?: answer.title?.take(32)?.trim()?.ifBlank { null } ?: "Web page on ${answer.port}"
        val score = when {
            guess != null -> 300 + guess.score
            identified == null && onPort != null -> 250 + onPort.score
            identified != null -> 200
            service != null -> 170
            else -> 150
        }
        out += ServiceSuggestion(name, url, HintSource.Found, answerDetail(answer), score)
    }
    notes.forEach { out += ServiceSuggestion("From the notes", it, HintSource.NotesAddress, "Written in the guest's notes.", 120) }
    if (host != null) guesses.forEach { guess ->
        val endpoint = guess.service.main
        val closed = answers?.any { it.host == host && it.port == endpoint.port && it.state != PortState.Open } == true
        val detail = if (closed) "Port ${endpoint.port} did not answer." else guess.why + (guess.service.note?.let { " $it" } ?: "")
        out += ServiceSuggestion(guess.service.name, serviceAddress(host, endpoint), guess.source, detail, if (closed) guess.score - 100 else guess.score)
    }
    return out.sortedByDescending { it.score }.distinctBy { it.url.lowercase() }.take(limit)
}

/** A short line for an answer: scheme, certificate and status. */
fun answerDetail(answer: ServiceAnswer): String = buildList {
    add(if (answer.secure == true) "https" else "http")
    answer.untrusted?.let { add("certificate not trusted: $it") }
    answer.status?.let { add("HTTP $it") }
    answer.title?.let { add("“${it.take(40)}”") }
}.joinToString(" · ")

/**
 * Guest addresses in the order a browser on this computer is most likely to reach them: home-network IPv4 first
 * (192.168, then 10, then 172.16/12), then a tailnet's 100.64/10, other IPv4, names, and IPv6 last. Order is kept within a rank.
 */
fun preferredHosts(hosts: List<String>): List<String> = hosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sortedBy { host ->
    val n = ScanRules.ipv4(host)
    when {
        n == null && ':' in host -> 6
        n == null -> 5
        n ushr 16 == 0xc0a8L -> 0
        n ushr 24 == 10L -> 1
        n ushr 20 == 0xac1L -> 2
        n ushr 22 == 0x191L -> 3
        else -> 4
    }
}
