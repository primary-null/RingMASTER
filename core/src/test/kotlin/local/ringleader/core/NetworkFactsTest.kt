package local.ringleader.core

import kotlin.test.*
import kotlinx.coroutines.*

/**
 * The operating system's adapters, routes and neighbours: what the parsers take from real output, and what they do with output
 * that is odd or hostile (garbage, signs and foreign digits in numbers, huge or half lines). The addresses are documentation
 * ranges, and 10.20.30.0/24 style addresses only where the rule under test needs private space.
 */
class NetworkFactsTest {
    // ---- Windows: the PowerShell records ----------------------------------------------------------------------------------

    private val windows = """
        A {"i":7,"n":"Ethernet","d":"Intel(R) Ethernet Controller I226-V","v":false,"h":true}
        A {"i":23,"n":"vEthernet (Default Switch)","d":"Hyper-V Virtual Ethernet Adapter","v":true,"h":false}
        A {"i":15,"n":"Tailscale","d":"Tailscale Tunnel","v":true,"h":false}
        P {"i":7,"c":"Private"}
        P {"i":15,"c":"Public"}
        R {"p":"0.0.0.0/0","h":"192.0.2.1","i":7,"m":25}
        R {"p":"192.0.2.0/24","h":"0.0.0.0","i":7,"m":281}
        R {"p":"10.20.30.0/24","h":"100.64.0.2","i":15,"m":5}
        R {"p":"224.0.0.0/4","h":"0.0.0.0","i":7,"m":331}
        R {"p":"255.255.255.255/32","h":"0.0.0.0","i":7,"m":281}
    """.trimIndent()

    /** What the real script prints: one line per kind, each a JSON list. */
    private val windowsLines = """
        A [{"i":7,"n":"Ethernet","d":"Intel(R) Ethernet Controller I226-V","v":false,"h":true},{"i":23,"n":"vEthernet (Default Switch)","d":"Hyper-V Virtual Ethernet Adapter","v":true,"h":false},{"i":15,"n":"Tailscale","d":"Tailscale Tunnel","v":true,"h":false}]
        R [{"p":"0.0.0.0/0","h":"192.0.2.1","i":7,"m":25},{"p":"192.0.2.0/24","h":"0.0.0.0","i":7,"m":281},{"p":"10.20.30.0/24","h":"100.64.0.2","i":15,"m":5},{"p":"224.0.0.0/4","h":"0.0.0.0","i":7,"m":331},{"p":"255.255.255.255/32","h":"0.0.0.0","i":7,"m":281}]
        P [{"i":7,"c":"Private"},{"i":15,"c":"Public"}]
    """.trimIndent()

    @Test fun theRealScriptsOneLinePerKindIsReadTheSameAsOneRecordPerLine() {
        val lines = parseWindowsNetworkFacts(windowsLines); val records = parseWindowsNetworkFacts(windows)
        assertEquals(records.adapters, lines.adapters); assertEquals(records.routes, lines.routes)
        // A single adapter or route comes as a list of one; a broken list is skipped and the others stand.
        assertEquals(1, parseWindowsNetworkFacts("""A [{"i":1,"n":"Only","h":true}]""").adapters.size)
        assertEquals(1, parseWindowsNetworkFacts("""A {"i":1,"n":"Only","h":true}""").adapters.size)
        val mixed = parseWindowsNetworkFacts("A [1,\"x\",null,{\"i\":2,\"n\":\"Good\"}]\nR [\n")
        assertEquals(listOf("Good"), mixed.adapters.map { it.name }); assertTrue(mixed.routes.isEmpty())
        assertTrue(parseWindowsNetworkFacts("A []\nR []\nP []").let { it.adapters.isEmpty() && it.routes.isEmpty() })
        assertTrue(parseWindowsNetworkFacts("""A "text"""").adapters.isEmpty())
    }

    @Test fun windowsRecordsBecomeAdaptersRoutesAndCategories() {
        val facts = parseWindowsNetworkFacts(windows)
        assertEquals(listOf("Ethernet", "vEthernet (Default Switch)", "Tailscale"), facts.adapters.map { it.name })
        val ethernet = facts.adapters.first { it.index == 7 }
        assertEquals(false, ethernet.virtual); assertEquals(true, ethernet.hardware); assertEquals("Private", ethernet.category)
        assertTrue(facts.adapters.first { it.index == 15 }.isPublic); assertTrue(facts.adapters.first { it.index == 15 }.isTailscale)
        assertNull(facts.adapters.first { it.index == 23 }.category)
        assertEquals(5, facts.routes.size)
        val default = facts.routes.first { it.isDefault }
        assertEquals("192.0.2.1", default.nextHop); assertEquals(7, default.interfaceIndex)
        // 0.0.0.0 as a next hop means on-link, which is no next hop at all.
        assertNull(facts.routes.first { it.cidr == "192.0.2.0/24" }.nextHop)
        assertEquals("100.64.0.2", facts.routes.first { it.cidr == "10.20.30.0/24" }.nextHop)
        assertTrue(facts.ownsDefaultRoute(ethernet)); assertFalse(facts.ownsDefaultRoute(facts.adapters.first { it.index == 15 }))
        assertTrue(facts.viaTailscale(facts.routes.first { it.cidr == "10.20.30.0/24" })); assertFalse(facts.viaTailscale(default))
    }

    @Test fun windowsOutputWithNoiseAndBrokenLinesKeepsWhatIsGood() {
        val text = "﻿" + """
            WARNING: something went wrong
            A {"i":7,"n":"Ethernet","d":"x","v":false,"h":true}
            A {"i":"seven","n":"Broken index","v":false}
            A {"n":"No index"}
            A not json at all
            A {"i":9,"n":"","d":"empty name"}
            R {"p":"+10.0.0.0/8","h":"0.0.0.0","i":7}
            R {"p":"10.0.0.0/+8","h":"0.0.0.0","i":7}
            R {"p":"٣.0.0.0/8","h":"0.0.0.0","i":7}
            R {"p":"10.0.0.0/33","h":"0.0.0.0","i":7}
            R {"p":"10.0.0.0/8","h":"0.0.0.0","i":7}
            R {"p":"::/0","h":"::","i":7}
            Z {"p":"10.1.0.0/16"}
            RR {"p":"10.2.0.0/16"}
            R{"p":"10.3.0.0/16"}
            R
            ${"R {\"p\":\"10.4.0.0/16\",\"h\":\"0.0.0.0\",\"i\":7,\"pad\":\"" + "x".repeat(50_000) + "\"}"}
        """.trimIndent()
        val facts = parseWindowsNetworkFacts(text)
        assertEquals(listOf("Ethernet"), facts.adapters.map { it.name })
        // Signs, foreign digits, a prefix over 32 and an IPv6 prefix are not routes; the plain ones are.
        assertEquals(setOf("10.0.0.0/8", "10.4.0.0/16"), facts.routes.map { it.cidr }.toSet())
        assertTrue(parseWindowsNetworkFacts("").let { it.adapters.isEmpty() && it.routes.isEmpty() })
        assertTrue(parseWindowsNetworkFacts("garbage\n\u0000\u0001\n").routes.isEmpty())
    }

    @Test fun windowsRecordCountsAreBounded() {
        val many = (1..9000).joinToString("\n") { """R {"p":"10.${it / 256 % 256}.${it % 256}.0/24","h":"0.0.0.0","i":7}""" }
        assertTrue(parseWindowsNetworkFacts(many).routes.size <= 4096)
        val adapters = (1..2000).joinToString("\n") { """A {"i":$it,"n":"a$it","v":false,"h":true}""" }
        assertTrue(parseWindowsNetworkFacts(adapters).adapters.size <= 512)
        // A name is cut to a sane length and loses its control characters.
        val name = parseWindowsNetworkFacts("""A {"i":1,"n":"${"n".repeat(500)}\u0007x","h":true}""").adapters.single().name
        assertTrue(name.length <= 160 && name.none { it.isISOControl() })
    }

    @Test fun theWindowsScriptIsFixedTextThatOnlyReads() {
        val script = windowsNetworkFactsScript()
        assertTrue(Regex("Get-Net[A-Za-z]+").findAll(script).map { it.value }.toSet() == setOf("Get-NetAdapter", "Get-NetRoute", "Get-NetConnectionProfile"), script)
        for (verb in listOf("Set-", "New-", "Remove-", "Start-", "Stop-", "Invoke-", "Add-", "Enable-", "Disable-", "Restart-", "Out-File", "Add-Type", "iex", "Invoke-Expression", "Write-", "Export-", "Import-", "Register-", "Unregister-", "Clear-", "Reset-", "Copy-", "Move-", "Rename-"))
            assertFalse(script.contains(verb, ignoreCase = true), verb)
        // The side-by-side reads are runspaces of this one PowerShell, started and collected by it; no other program runs.
        assertTrue("[powershell]::Create()" in script && "BeginInvoke()" in script && "EndInvoke(" in script)
        assertFalse(Regex("(?i)\\b(cmd|wmic|reg|netsh|curl|wget|start-process)\\b").containsMatchIn(script), script)
        assertTrue(vpnPowerShell(script).contains("-EncodedCommand") && vpnPowerShell(script).contains("-NonInteractive"))
    }

    // ---- Windows: route print (the fallback) -------------------------------------------------------------------------------

    private val routePrint = """
        ===========================================================================
        Interface List
          7...aa bb cc dd ee ff ......Intel(R) Ethernet Controller I226-V
          1...........................Software Loopback Interface 1
        ===========================================================================

        IPv4 Route Table
        ===========================================================================
        Active Routes:
        Network Destination        Netmask          Gateway       Interface  Metric
                  0.0.0.0          0.0.0.0        192.0.2.1       192.0.2.5     25
                127.0.0.0        255.0.0.0         On-link         127.0.0.1    331
              192.0.2.0    255.255.255.0         On-link         192.0.2.5    281
             10.20.30.0    255.255.255.0       192.0.2.254       192.0.2.5     26
                224.0.0.0        240.0.0.0         On-link         192.0.2.5    281
        ===========================================================================
        Persistent Routes:
          Network Address          Netmask  Gateway Address  Metric
                  0.0.0.0          0.0.0.0        192.0.2.1  Default
                10.99.0.0      255.255.0.0      192.0.2.254       5
    """.trimIndent()

    @Test fun routePrintRowsAreReadByTheirNumbersWhateverTheLanguage() {
        val routes = parseWindowsRoutePrint(routePrint)
        assertEquals(setOf("0.0.0.0/0", "127.0.0.0/8", "192.0.2.0/24", "10.20.30.0/24", "224.0.0.0/4"), routes.map { it.cidr }.toSet())
        assertEquals("192.0.2.1", routes.first { it.isDefault }.nextHop); assertEquals("192.0.2.5", routes.first { it.isDefault }.interfaceAddress)
        assertNull(routes.first { it.cidr == "192.0.2.0/24" }.nextHop)
        assertEquals("192.0.2.254", routes.first { it.cidr == "10.20.30.0/24" }.nextHop)
        // The same table in another language: the gateway word is different and has a space in it.
        val german = routePrint.replace("On-link", "Auf Verbindung")
        assertEquals(routes.map { it.cidr to it.nextHop }, parseWindowsRoutePrint(german).map { it.cidr to it.nextHop })
        // A mask that is not a prefix, a foreign digit or a sign, and a metric that is not a number are skipped.
        val hostile = """
            10.0.0.0   255.0.255.0   On-link   10.0.0.5   10
            +10.1.0.0   255.255.0.0   On-link   10.1.0.5   10
            10.2.0.0   255.255.0.0   On-link   10.2.0.5   ten
            10.3.0.0   255.255.0.0   On-link   10.3.0.5   10
        """.trimIndent()
        assertEquals(listOf("10.3.0.0/16"), parseWindowsRoutePrint(hostile).map { it.cidr })
    }

    // ---- Linux: ip route ---------------------------------------------------------------------------------------------------

    private val ipRouteJson = """[
      {"type":"local","dst":"127.0.0.0/8","dev":"lo","table":"local","protocol":"kernel","scope":"host","prefsrc":"127.0.0.1"},
      {"type":"broadcast","dst":"192.0.2.255","dev":"eth0","table":"local","protocol":"kernel","scope":"link"},
      {"dst":"default","gateway":"192.0.2.1","dev":"eth0","protocol":"dhcp","prefsrc":"192.0.2.5","metric":100,"flags":[]},
      {"dst":"192.0.2.0/24","dev":"eth0","protocol":"kernel","scope":"link","prefsrc":"192.0.2.5","metric":100},
      {"dst":"10.20.30.0/24","gateway":"192.0.2.254","dev":"eth0","metric":50},
      {"dst":"10.30.0.0/16","dev":"tailscale0","table":"52","protocol":"static"},
      {"dst":"100.64.0.2","dev":"tailscale0","table":"52"},
      {"type":"unreachable","dst":"10.66.0.0/16"},
      {"dst":"10.40.0.0/16","nexthops":[{"gateway":"192.0.2.253","dev":"eth0","weight":1},{"gateway":"192.0.2.252","dev":"eth0","weight":1}]}
    ]"""

    @Test fun ipRouteJsonKeepsNormalRoutesAndDropsTheLocalTableAndOddTypes() {
        val routes = parseIpRouteJson(ipRouteJson)!!
        assertEquals(setOf("0.0.0.0/0", "192.0.2.0/24", "10.20.30.0/24", "10.30.0.0/16", "100.64.0.2/32", "10.40.0.0/16"), routes.map { it.cidr }.toSet())
        assertEquals("eth0", routes.first { it.isDefault }.interfaceName); assertEquals("192.0.2.1", routes.first { it.isDefault }.nextHop)
        assertEquals("tailscale0", routes.first { it.cidr == "10.30.0.0/16" }.interfaceName)
        // A host route has a bare address as its destination; a multipath route names its first next hop.
        assertEquals(32, routes.first { it.cidr == "100.64.0.2/32" }.prefix)
        assertEquals("192.0.2.253", routes.first { it.cidr == "10.40.0.0/16" }.nextHop); assertEquals("eth0", routes.first { it.cidr == "10.40.0.0/16" }.interfaceName)
    }

    @Test fun ipRouteJsonRefusesWhatIsNotAListOfRoutes() {
        for (text in listOf("", "not json", "{}", "{\"dst\":\"default\"}", "null", "[1,2,3]x")) assertNull(parseIpRouteJson(text), text)
        assertEquals(emptyList(), parseIpRouteJson("[]"))
        assertEquals(emptyList(), parseIpRouteJson("[1,\"a\",null,{\"dst\":5},{\"dst\":\"+10.0.0.0/8\"},{\"dst\":\"10.0.0.0/+8\"},{\"dst\":\"10.0.0.0/40\"}]"))
        assertNull(parseIpRouteJson("[" + "{\"dst\":\"10.0.0.0/8\"},".repeat(200_000) + "{}]"), "an answer over 4 MB is not read")
        assertTrue(parseIpRouteJson("[" + (1..9000).joinToString(",") { "{\"dst\":\"10.${it / 256 % 256}.${it % 256}.0/24\",\"dev\":\"eth0\"}" } + "]")!!.size <= 4096)
    }

    @Test fun ipRouteTextIsReadForAnOlderIp() {
        val text = """
            default via 192.0.2.1 dev eth0 proto dhcp metric 100
            192.0.2.0/24 dev eth0 proto kernel scope link src 192.0.2.5 metric 100
            10.20.30.0/24 via 192.0.2.254 dev eth0 metric 50
            10.30.0.0/16 dev tailscale0 table 52
            local 127.0.0.1 dev lo table local proto kernel scope host src 127.0.0.1
            broadcast 192.0.2.255 dev eth0 table local proto kernel scope link src 192.0.2.5
            unreachable 10.66.0.0/16 metric 1000
            10.77.0.0/16 dev eth0 table local
            +10.88.0.0/16 dev eth0
            10.99.0.0/16 via +192.0.2.9 dev eth0
        """.trimIndent()
        val routes = parseIpRouteText(text)
        assertEquals(listOf("0.0.0.0/0", "192.0.2.0/24", "10.20.30.0/24", "10.30.0.0/16", "10.99.0.0/16"), routes.map { it.cidr })
        assertEquals("192.0.2.254", routes[2].nextHop); assertEquals("eth0", routes[2].interfaceName); assertEquals(50, routes[2].metric)
        assertNull(routes[4].nextHop, "a next hop with a sign is no address")
        assertEquals(emptyList(), parseIpRouteText("garbage\n\n   \n\u0000"))
    }

    // ---- neighbours --------------------------------------------------------------------------------------------------------

    @Test fun arpTableGivesTheAddressesOfLiveNeighbours() {
        val text = """
            Interface: 192.0.2.5 --- 0x7
              Internet Address      Physical Address      Type
              192.0.2.1             aa-bb-cc-dd-ee-ff     dynamic
              192.0.2.9             12-22-33-44-55-66     dynamisch
              192.0.2.10            00-00-00-00-00-00     invalid
              192.0.2.255           ff-ff-ff-ff-ff-ff     static
              224.0.0.22            01-00-5e-00-00-16     static
              192.0.2.11            03-00-00-00-00-01     static
              +192.0.2.12           aa-bb-cc-dd-ee-01     dynamic
              192.0.2.13            aa-bb-cc-dd-ee       dynamic
              192.0.2.14            ZZ-bb-cc-dd-ee-ff     dynamic
        """.trimIndent()
        assertEquals(setOf("192.0.2.1", "192.0.2.9"), parseArpTable(text))
        assertEquals(emptySet(), parseArpTable("")); assertEquals(emptySet(), parseArpTable("no entries found\n\u0000"))
    }

    @Test fun ipNeighbourStatesDecideWhoIsAlive() {
        val json = """[{"dst":"192.0.2.1","dev":"eth0","lladdr":"aa:bb:cc:dd:ee:ff","state":["REACHABLE"]},
          {"dst":"192.0.2.2","dev":"eth0","lladdr":"aa:bb:cc:dd:ee:01","state":["STALE"]},
          {"dst":"192.0.2.3","dev":"eth0","lladdr":"aa:bb:cc:dd:ee:02","state":"DELAY"},
          {"dst":"192.0.2.4","dev":"eth0","state":["FAILED"]},
          {"dst":"192.0.2.5","dev":"eth0","state":["INCOMPLETE"]},
          {"dst":"192.0.2.6","dev":"eth0","lladdr":"aa:bb:cc:dd:ee:03","state":["PERMANENT"]},
          {"dst":"fe80::1","dev":"eth0","lladdr":"aa:bb:cc:dd:ee:04","state":["REACHABLE"]},
          {"dst":"+192.0.2.7","dev":"eth0","lladdr":"aa:bb:cc:dd:ee:05","state":["REACHABLE"]},
          {"dev":"eth0"}, 7]"""
        assertEquals(setOf("192.0.2.1", "192.0.2.2", "192.0.2.3"), parseIpNeighJson(json))
        for (text in listOf("", "x", "{}", "7")) assertNull(parseIpNeighJson(text), text)
        val plain = """
            192.0.2.1 dev eth0 lladdr aa:bb:cc:dd:ee:ff REACHABLE
            192.0.2.2 dev eth0 lladdr aa:bb:cc:dd:ee:01 STALE
            192.0.2.4 dev eth0  FAILED
            192.0.2.5 dev eth0  INCOMPLETE
            192.0.2.6 dev eth0 lladdr aa:bb:cc:dd:ee:03 PERMANENT
        """.trimIndent()
        assertEquals(setOf("192.0.2.1", "192.0.2.2"), parseIpNeighText(plain))
    }

    // ---- the reader --------------------------------------------------------------------------------------------------------

    private fun tool(answer: (List<String>) -> ToolResult): ToolRunner = { command, _ -> answer(command) }
    private fun reader(windows: Boolean, run: ToolRunner, which: (String) -> String? = { it }, devices: () -> List<Pair<String, Boolean>> = { emptyList() }) = LocalNetworkReader(windows, run, which, devices)

    @Test fun windowsReadUsesOnePowerShellStartAndNothingElse() = runBlocking {
        val seen = mutableListOf<List<String>>()
        val read = reader(true, tool { seen += it; ToolResult.Text(windows) }).read()
        assertNull(read.problem); assertEquals(3, read.facts!!.adapters.size)
        assertEquals(1, seen.size); assertTrue(seen.single().first().endsWith("powershell.exe")); assertTrue("-EncodedCommand" in seen.single())
    }

    @Test fun whenPowerShellFailsTheRouteTableIsStillRead() = runBlocking {
        val calls = mutableListOf<String>()
        val read = reader(true, tool { c -> calls += c.first(); if (c.first().endsWith("powershell.exe")) ToolResult.Failed("It did not answer in time.") else ToolResult.Text(routePrint) }).read()
        val facts = read.facts!!
        assertTrue(facts.adapters.isEmpty()); assertTrue(facts.routes.isNotEmpty())
        assertTrue(read.problem!!.contains("Adapter details could not be read") && read.problem!!.contains("did not answer in time"), read.problem)
        assertEquals(2, calls.size); assertTrue(calls[1].endsWith("route.exe"))
    }

    @Test fun whenNothingCanBeReadTheAnswerSaysSoAndNeverThrows() = runBlocking {
        for (windows in listOf(true, false)) {
            val read = reader(windows, tool { ToolResult.Failed("It could not be started.") }).read()
            assertNull(read.facts); assertTrue(read.problem!!.startsWith("This computer's routes could not be read"), read.problem)
        }
        assertNull(reader(false, tool { ToolResult.Text("[]") }, which = { null }).read().facts)
        assertNull(reader(true, tool { ToolResult.Text("nothing useful") }).read().facts)
    }

    @Test fun linuxReadsTheTableAndTheHardwareOfEachDevice() = runBlocking {
        val seen = mutableListOf<List<String>>()
        val devices = listOf("eth0" to true, "docker0" to false, "tailscale0" to false, "vmbr0" to false)
        val read = reader(false, tool { seen += it; ToolResult.Text(ipRouteJson) }, devices = { devices }).read()
        val facts = read.facts!!
        assertEquals(listOf("-j", "-4", "route", "show", "table", "all"), seen.single().drop(1))
        val eth0 = facts.adapters.first { it.name == "eth0" }; val docker = facts.adapters.first { it.name == "docker0" }
        assertEquals(true, eth0.hardware); assertEquals(false, eth0.virtual); assertEquals(true, docker.virtual)
        assertTrue(facts.ownsDefaultRoute(eth0)); assertFalse(facts.ownsDefaultRoute(docker))
        assertTrue(facts.viaTailscale(facts.routes.first { it.cidr == "10.30.0.0/16" }))
    }

    @Test fun anIpThatDoesNotKnowJsonIsReadAsText() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val text = "default via 192.0.2.1 dev eth0\n10.20.30.0/24 via 192.0.2.254 dev eth0\n"
        val read = reader(false, tool { c -> calls += c; if ("-j" in c) ToolResult.Failed("It stopped with code 1.") else ToolResult.Text(text) }).read()
        assertEquals(listOf("0.0.0.0/0", "10.20.30.0/24"), read.facts!!.routes.map { it.cidr }); assertEquals(2, calls.size)
    }

    @Test fun neighboursComeFromArpOrIpAndAMissingToolIsNoAnswer() = runBlocking {
        val arp = LocalNeighbours(true, tool { ToolResult.Text("192.0.2.1  aa-bb-cc-dd-ee-ff  dynamic") }, { it }).read()
        assertEquals(setOf("192.0.2.1"), arp)
        val json = LocalNeighbours(false, tool { ToolResult.Text("""[{"dst":"192.0.2.2","lladdr":"aa:bb:cc:dd:ee:ff","state":["STALE"]}]""") }, { it }).read()
        assertEquals(setOf("192.0.2.2"), json)
        val text = LocalNeighbours(false, tool { c -> if ("-j" in c) ToolResult.Failed("x") else ToolResult.Text("192.0.2.3 dev eth0 lladdr aa:bb:cc:dd:ee:ff REACHABLE") }, { it }).read()
        assertEquals(setOf("192.0.2.3"), text)
        assertNull(LocalNeighbours(true, tool { ToolResult.Failed("x") }, { it }).read())
        assertNull(LocalNeighbours(false, tool { ToolResult.Text("[]") }, { null }).read())
        assertNull(NeighbourSource.NONE.read())
    }

    // ---- the process runner ------------------------------------------------------------------------------------------------

    private val javaExe = java.nio.file.Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString()

    /** A program that runs for half a minute: the system's own ping (Windows) or sleep, by absolute path. */
    private val sleeper: List<String> = if (System.getProperty("os.name").startsWith("Windows")) listOf(windowsSystemRoot() + "\\System32\\PING.EXE", "-n", "30", "127.0.0.1")
        else listOf(listOf("/bin/sleep", "/usr/bin/sleep").first { java.nio.file.Files.isRegularFile(java.nio.file.Path.of(it)) }, "30")

    @Test fun onlyAnAbsolutePathToARealFileRuns() = runBlocking {
        for (command in listOf(listOf("java", "--version"), listOf("./java", "--version"), listOf("..\\java.exe"), listOf(javaExe + ".missing", "--version"), emptyList(), listOf(javaExe, "a\u0000b")))
            assertTrue(runBoundedTool(command, 5_000) is ToolResult.Failed, command.toString())
    }

    @Test fun aRealToolRunsAndItsOutputIsCapped() = runBlocking {
        val ok = runBoundedTool(listOf(javaExe, "--version"), 30_000)
        assertTrue(ok is ToolResult.Text && ok.text.isNotBlank(), ok.toString())
        val capped = runBoundedTool(listOf(javaExe, "--version"), 30_000, maxBytes = 4)
        assertTrue(capped is ToolResult.Failed && capped.reason.contains("too large"), capped.toString())
    }

    @Test fun aToolThatDoesNotAnswerInTimeIsStopped() = runBlocking {
        val started = System.nanoTime()
        val result = runBoundedTool(sleeper, 1_500)
        assertTrue(result is ToolResult.Failed && result.reason.contains("in time"), result.toString())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 15_000)
    }

    @Test fun cancellingTheCallerStopsTheTool() = runBlocking {
        val job = launch(Dispatchers.Default) { runBoundedTool(sleeper, 60_000) }
        delay(700)
        withTimeout(10_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    // ---- small helpers -----------------------------------------------------------------------------------------------------

    @Test fun strictNumbersTakeAsciiDigitsOnly() {
        assertEquals(0x0a141e01L, strictIpv4("10.20.30.1")); assertEquals("10.20.30.1", ipv4Text(0x0a141e01L))
        for (bad in listOf("+10.20.30.1", "10.20.30.256", "10.20.30", "10.20.30.1.1", "١.0.0.1", " 10.20.30.1", "10.20.30.1 ", "1e1.0.0.1", "10.20.30.-1", ""))
            assertNull(strictIpv4(bad), bad)
        assertEquals(24, strictPrefix("24")); assertEquals(0, strictPrefix("0")); assertNull(strictPrefix("33")); assertNull(strictPrefix("+24")); assertNull(strictPrefix("٢٤")); assertNull(strictPrefix("024x"))
        assertEquals(0x0a141e00L to 24, parseCidr("10.20.30.99/24")); assertEquals(0x0a141e63L to 32, parseCidr("10.20.30.99")); assertNull(parseCidr("10.20.30.99/"))
    }
}
