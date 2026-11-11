package local.ringleader.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Handwritten bodies shaped like the API viewer schema for services, syslog and apt/update. Not captured server responses. */
class NodeToolsParsingTest {
    private fun line(n: Int) = SyslogLine(n, "line $n")
    private fun page(range: IntRange, total: Int) = SyslogPage(range.map(::line), total)

    // ---- services ----

    @Test fun servicesReadBothTheOldAndTheNewShape() {
        val rows = parseNodeServices("""{"data":[
            {"service":"pveproxy","name":"pveproxy","desc":"PVE API Proxy Server","state":"running","unit-state":"enabled","active-state":"active"},
            {"service":"sshd","name":"ssh","desc":"OpenBSD Secure Shell server","state":"running"},
            {"name":"corosync","state":"dead","unit-state":"disabled","active-state":"inactive"},
            {"service":"pveproxy","name":"pveproxy","desc":"duplicate"},
            {"desc":"no name"}, 5, "x", null
        ]}""")
        assertEquals(listOf("corosync", "pveproxy", "ssh"), rows.map { it.name })
        val proxy = rows.single { it.name == "pveproxy" }
        assertEquals("PVE API Proxy Server", proxy.description)
        assertNull(proxy.unit, "The unit is kept only when it differs from the name")
        assertEquals("enabled", proxy.unitState)
        assertEquals("active", proxy.activeState)
        val ssh = rows.single { it.name == "ssh" }
        assertEquals("sshd", ssh.unit)
        assertNull(ssh.unitState)
        assertNull(ssh.activeState)
        assertEquals("", rows.single { it.name == "corosync" }.description)
    }

    @Test fun serviceTextHasControlCharactersRemoved() {
        val row = parseNodeServices("""{"data":[{"name":"x\u0000y","desc":"a\u001b[31m   b\n c","state":"run\u0007ning"}]}""").single()
        assertEquals("xy", row.name)
        assertEquals("a[31m b c", row.description)
        assertEquals("running", row.state)
    }

    @Test fun servicesNeedADataArray() {
        assertEquals(NODE_SERVICES_EMPTY_BODY, assertFailsWith<IllegalStateException> { parseNodeServices("""{}""") }.message)
        assertFailsWith<IllegalStateException> { parseNodeServices("""{"data":null}""") }
        assertFailsWith<IllegalStateException> { parseNodeServices("""{"data":{}}""") }
        assertFailsWith<IllegalStateException> { parseNodeServices("nope") }
        assertTrue(parseNodeServices("""{"data":[]}""").isEmpty())
    }

    @Test fun serviceHealthFollowsSystemdStates() {
        fun health(state: String?, unit: String?, active: String?) = serviceHealth(NodeService("x", null, "", state, unit, active))
        assertEquals(ServiceHealth.Running, health("running", "enabled", "active"))
        assertEquals(ServiceHealth.Exited, health("exited", "enabled", "active"))
        assertEquals(ServiceHealth.Exited, health("success", "enabled", "inactive"), "A finished one-shot is normal")
        assertEquals(ServiceHealth.Failed, health("failed", "enabled", "failed"))
        assertEquals(ServiceHealth.Failed, health("exit-code", "enabled", "failed"))
        assertEquals(ServiceHealth.EnabledNotRunning, health("dead", "enabled", "inactive"))
        assertEquals(ServiceHealth.EnabledNotRunning, health("dead", "enabled-runtime", "inactive"))
        assertEquals(ServiceHealth.Stopped, health("dead", "disabled", "inactive"))
        assertEquals(ServiceHealth.NotInstalled, health("dead", "not-found", "inactive"))
        assertEquals(ServiceHealth.Transitioning, health("start", "enabled", "activating"))
        // Older servers send the state alone.
        assertEquals(ServiceHealth.Running, health("running", null, null))
        assertEquals(ServiceHealth.Stopped, health("dead", null, null))
        assertEquals(ServiceHealth.Failed, health("failed", null, null))
        assertEquals(ServiceHealth.Unknown, health("unknown", null, null))
    }

    @Test fun servicesPathChecksAndEncodesTheNode() {
        assertEquals("/api2/json/nodes/node-a/services", nodeServicesPath("node-a"))
        for (bad in listOf("node.example", "a/b", "", "https://node", "node_a", "-a")) {
            assertFailsWith<IllegalArgumentException>(bad) { nodeServicesPath(bad) }
        }
    }

    // ---- syslog ----

    @Test fun syslogPageKeepsLineNumbersAndTheServerTotal() {
        val parsed = parseSyslogPage("""{"data":[{"n":3,"t":"c"},{"n":1,"t":"a"},{"n":2,"t":"b"}],"total":10}""")
        assertEquals(listOf(1, 2, 3), parsed.lines.map { it.n })
        assertEquals(listOf("a", "b", "c"), parsed.lines.map { it.text })
        assertEquals(10, parsed.total)
    }

    @Test fun aMissingTotalFallsBackToTheHighestLineNumber() {
        assertEquals(6, parseSyslogPage("""{"data":[{"n":5,"t":"x"},{"n":6,"t":"y"}]}""").total)
        assertEquals(6, parseSyslogPage("""{"data":[{"n":5,"t":"x"},{"n":6,"t":"y"}],"total":"99"}""").total, "A string total is not trusted")
        assertEquals(0, parseSyslogPage("""{"data":[]}""").total)
    }

    @Test fun anEmptyJournalIsNotALogLine() {
        for (body in listOf("""{"data":[{"n":1,"t":"no content"}],"total":1}""", """{"data":[{"n":1,"t":"no content"}]}""")) {
            val empty = parseSyslogPage(body)
            assertTrue(empty.lines.isEmpty(), body)
            assertEquals(0, empty.total)
        }
        // The same words as a real line are kept when the log is longer than one line.
        val real = parseSyslogPage("""{"data":[{"n":1,"t":"no content"}],"total":50}""")
        assertEquals(1, real.lines.size)
        assertEquals(50, real.total)
        assertEquals(1, parseSyslogPage("""{"data":[{"n":2,"t":"no content"}],"total":2}""").lines.size)
    }

    @Test fun syslogRowsAreCleanedAndBadRowsSkipped() {
        val parsed = parseSyslogPage("""{"data":[
            {"n":1,"t":"a\u0007b\tc\r\n"},{"n":"2","t":"string number"},{"n":0,"t":"zero"},{"n":4},{"t":"no number"},7,{"n":5,"t":5},{"n":6,"t":""}
        ],"total":6}""")
        assertEquals(listOf(1 to "ab\tc", 6 to ""), parsed.lines.map { it.n to it.text })
        assertEquals(6, parsed.total)
        assertEquals(8_192, parseSyslogPage("""{"data":[{"n":1,"t":"${"x".repeat(20_000)}"}]}""").lines.single().text.length)
    }

    @Test fun syslogNeedsADataArray() {
        assertEquals(NODE_SYSLOG_EMPTY_BODY, assertFailsWith<IllegalStateException> { parseSyslogPage("""{"total":1}""") }.message)
        assertFailsWith<IllegalStateException> { parseSyslogPage("""{"data":{}}""") }
        assertFailsWith<IllegalStateException> { parseSyslogPage("[]") }
    }

    @Test fun syslogPathCarriesStartLimitAndAnOptionalUnit() {
        assertEquals("/api2/json/nodes/node-a/syslog?start=0&limit=1", syslogPath("node-a", 0, 1))
        assertEquals("/api2/json/nodes/node-a/syslog?start=700&limit=500", syslogPath("node-a", 700, 500))
        assertEquals("/api2/json/nodes/node-a/syslog?start=5&limit=10&service=pveproxy", syslogPath("node-a", 5, 10, "pveproxy"))
        assertTrue(syslogPath("node-a", 0, 10, "user@1000.service").endsWith("&service=user%401000.service"))
    }

    @Test fun syslogPathRefusesWhatTheServerWouldMisread() {
        assertFailsWith<IllegalArgumentException> { syslogPath("node-a", -1, 10) }
        assertFailsWith<IllegalArgumentException> { syslogPath("node-a", 0, 0) } // the node reads limit 0 as 50
        assertFailsWith<IllegalArgumentException> { syslogPath("node-a", 0, 2_001) }
        for (bad in listOf("a b", "a/b", "", "x;y", "a&b=c", "x".repeat(129))) assertFailsWith<IllegalArgumentException>(bad) { syslogPath("node-a", 0, 10, bad) }
        assertFailsWith<IllegalArgumentException> { syslogPath("node.example", 0, 10) }
        assertNull(syslogServiceOrNull("a b"))
        assertEquals("pve-cluster", syslogServiceOrNull(" pve-cluster "))
    }

    // ---- follow ----

    @Test fun theTailStartsOnePageBeforeTheEnd() {
        assertEquals(700, syslogTailStart(1_200))
        assertEquals(0, syslogTailStart(100))
        assertEquals(0, syslogTailStart(500))
        assertEquals(1, syslogTailStart(501))
        assertEquals(0, syslogTailStart(0))
        assertEquals(70, syslogTailStart(100, 30))
    }

    @Test fun followRereadsTheHeldTailLineAndWhatComesAfterIt() {
        assertNull(syslogFollowRequest(emptyList()))
        assertEquals(0 to 501, syslogFollowRequest(listOf(line(1))))
        assertEquals(1_199 to 501, syslogFollowRequest(listOf(line(1_199), line(1_200))))
        // The node returns lines with n > start, so start = held - 1 returns the held line first.
        assertEquals(SYSLOG_PAGE + 1, syslogFollowRequest(listOf(line(5)))!!.second)
    }

    @Test fun aVerifiedFollowReadAppendsOnlyWhatIsNew() {
        val result = checkSyslogFollow(line(1_200), page(1_200..1_203, 1_203))
        val appended = result as SyslogFollowResult.Appended
        assertEquals(listOf(1_201, 1_202, 1_203), appended.lines.map { it.n })
        assertEquals(1_203, appended.total)
        assertFalse(appended.more)
        assertEquals(SyslogFollowResult.NothingNew(1_200), checkSyslogFollow(line(1_200), page(1_200..1_200, 1_200)))
        val busy = checkSyslogFollow(line(10), page(10..510, 9_999)) as SyslogFollowResult.Appended
        assertEquals(500, busy.lines.size)
        assertTrue(busy.more, "The node still has lines after this page")
    }

    @Test fun followResyncsWhenTheNumberingMovedUnderneath() {
        // Same number, different text: lines before it were removed and others took its place.
        assertEquals(SyslogFollowResult.Resync, checkSyslogFollow(line(1_200), SyslogPage(listOf(SyslogLine(1_200, "other"), line(1_201)), 1_201)))
        // The first line returned is not the held one.
        assertEquals(SyslogFollowResult.Resync, checkSyslogFollow(line(1_200), page(1_100..1_200, 1_300)))
        // The log is shorter than the line held (rotation, vacuum).
        assertEquals(SyslogFollowResult.Resync, checkSyslogFollow(line(1_200), SyslogPage(emptyList(), 1_000)))
        assertEquals(SyslogFollowResult.Resync, checkSyslogFollow(line(1_200), page(1..50, 50)))
        // An empty answer for a log that should be long enough, and a gap after the held line.
        assertEquals(SyslogFollowResult.Resync, checkSyslogFollow(line(1_200), SyslogPage(emptyList(), 1_200)))
        assertEquals(SyslogFollowResult.Resync, checkSyslogFollow(line(1_200), SyslogPage(listOf(line(1_200), line(1_202)), 1_202)))
        assertEquals(SyslogFollowResult.Resync, checkSyslogFollow(line(1_200), SyslogPage(listOf(line(1_200), line(1_201), line(1_203)), 1_203)))
    }

    @Test fun theBufferKeepsTheNewestLinesWhenItGrowsPastItsCap() {
        val held = (1..SYSLOG_MAX_LINES).map(::line)
        val grown = appendSyslog(held, listOf(line(SYSLOG_MAX_LINES + 1), line(SYSLOG_MAX_LINES + 2), line(SYSLOG_MAX_LINES + 3)))
        assertEquals(SYSLOG_MAX_LINES, grown.size)
        assertEquals(4, grown.first().n)
        assertEquals(SYSLOG_MAX_LINES + 3, grown.last().n)
        assertEquals(held, appendSyslog(held, emptyList()))
    }

    @Test fun appendingAPageThatWasAlreadyAppliedAddsNothing() {
        val held = (1..10).map(::line)
        assertEquals(held, appendSyslog(held, (8..10).map(::line)))
        assertEquals((1..12).toList(), appendSyslog(held, (8..12).map(::line)).map { it.n })
        assertEquals(listOf(1, 2), appendSyslog(emptyList(), listOf(line(1), line(2))).map { it.n })
        assertEquals(held, appendSyslog(held, emptyList()))
    }

    @Test fun aRepeatedLineNumberInOnePageIsKeptOnce() {
        val parsed = parseSyslogPage("""{"data":[{"n":2,"t":"first"},{"n":2,"t":"second"},{"n":1,"t":"zero"}],"total":2}""")
        assertEquals(listOf(1, 2), parsed.lines.map { it.n })
        assertEquals("first", parsed.lines[1].text)
    }

    @Test fun loadEarlierAsksForThePageJustBeforeTheOldestLine() {
        assertNull(syslogEarlierRequest(emptyList()))
        assertNull(syslogEarlierRequest(listOf(line(1))))
        assertEquals(199 to 500, syslogEarlierRequest(listOf(line(700))))
        assertEquals(0 to 299, syslogEarlierRequest(listOf(line(300))))
        assertEquals(0 to 1, syslogEarlierRequest(listOf(line(2))))
        assertNull(syslogEarlierRequest((1..SYSLOG_MAX_LINES).map { line(it + 10) }), "A full buffer does not grow backwards")
    }

    @Test fun earlierLinesJoinOnlyWhenTheyEndRightBeforeTheOldestOne() {
        val held = listOf(line(700), line(701))
        val joined = applySyslogEarlier(held, page(200..699, 1_000))!!
        assertEquals(502, joined.size)
        assertEquals(200, joined.first().n)
        assertEquals(701, joined.last().n)
        assertNull(applySyslogEarlier(held, page(200..600, 1_000)), "A gap before the oldest line means the numbering moved")
        assertNull(applySyslogEarlier(held, SyslogPage(listOf(line(200), line(202), line(699)), 1_000)))
        assertNull(applySyslogEarlier(held, SyslogPage(emptyList(), 1_000)))
        assertNull(applySyslogEarlier(emptyList(), page(1..5, 5)))
    }

    @Test fun theViewKnowsWhetherThereIsAnEarlierPage() {
        fun view(lines: List<SyslogLine>) = SyslogView("node-a", null, lines, lines.lastOrNull()?.n ?: 0)
        assertFalse(view(emptyList()).canLoadEarlier)
        assertFalse(view(listOf(line(1), line(2))).canLoadEarlier)
        assertTrue(view(listOf(line(10))).canLoadEarlier)
        assertFalse(view((1..SYSLOG_MAX_LINES).map { line(it + 5) }).canLoadEarlier)
    }

    @Test fun theLineFilterIsASubstringMatchWithoutCase() {
        val lines = listOf(SyslogLine(1, "pvedaemon: ok"), SyslogLine(2, "PVEPROXY failed"), SyslogLine(3, "kernel: x"))
        assertEquals(lines, filterSyslog(lines, "  "))
        assertEquals(listOf(2), filterSyslog(lines, "proxy").map { it.n })
        assertEquals(listOf(1, 2), filterSyslog(lines, "pve").map { it.n })
        assertTrue(filterSyslog(lines, "zzz").isEmpty())
    }

    // ---- package updates ----

    @Test fun updatesKeepTheVersionsAsSentAndListProxmoxFirst() {
        val rows = parsePackageUpdates("""{"data":[
            {"Package":"zlib1g","Title":"zlib1g","Description":"compression library","Version":"1:1.3.dfsg+really1.3.1-1","OldVersion":"1:1.2.13","Arch":"amd64","Origin":"Debian","Priority":"required","Section":"libs"},
            {"Package":"pve-manager","Title":"pve-manager","Description":"Proxmox Virtual Environment Management Tools","Version":"9.2.23","OldVersion":"9.2.21","Arch":"all","Origin":"Proxmox","Priority":"optional","Section":"admin"},
            {"Package":"new-dependency","Version":"1.0","Arch":"amd64","Origin":"Debian"},
            {"Title":"no package name"}, 1
        ]}""")
        assertEquals(listOf("pve-manager", "new-dependency", "zlib1g"), rows.map { it.name })
        assertEquals("9.2.21" to "9.2.23", rows[0].oldVersion to rows[0].newVersion)
        assertNull(rows[1].oldVersion, "A package that is new to the node has no old version")
        assertEquals("1:1.3.dfsg+really1.3.1-1", rows[2].newVersion)
    }

    @Test fun updatesAcceptAnEmptyListAndRefuseAMissingOne() {
        assertTrue(parsePackageUpdates("""{"data":[]}""").isEmpty())
        assertTrue(parsePackageUpdates("""{"data":null}""").isEmpty())
        assertEquals(NODE_UPDATES_EMPTY_BODY, assertFailsWith<IllegalStateException> { parsePackageUpdates("""{}""") }.message)
        assertFailsWith<IllegalStateException> { parsePackageUpdates("""{"data":{}}""") }
        assertFailsWith<IllegalStateException> { parsePackageUpdates("nope") }
    }

    @Test fun theUpdatePathIsTheGetPathAndRefusesBodies() {
        assertEquals("/api2/json/nodes/node-a/apt/update", aptUpdatePath("node-a"))
        assertFailsWith<IllegalArgumentException> { aptUpdatePath("node.example") }
        assertTrue(isAptListRefresh(aptUpdatePath("node-a"), hasBody = true))
        assertTrue(isAptListRefresh("/api2/json/nodes/node-a/apt/update?quiet=1", hasBody = true))
        assertFalse(isAptListRefresh(aptUpdatePath("node-a"), hasBody = false))
        assertFalse(isAptListRefresh("/api2/json/nodes/node-a/apt/versions", hasBody = true))
    }

    // ---- demo ----

    @Test fun demoSamplesAreDeterministicAndShowEveryServiceState() {
        assertEquals(demoSyslogLine("demo-node-a", 5), demoSyslogLine("demo-node-a", 5))
        assertTrue(demoSyslogLine("demo-node-a", 5).text.contains("demo-node-a"))
        assertEquals((11..15).toList(), demoSyslogPage("demo-node-a", 10, 5, 100).lines.map { it.n })
        assertEquals(listOf(99, 100), demoSyslogPage("demo-node-a", 98, 5, 100).lines.map { it.n })
        assertTrue(demoSyslogPage("demo-node-a", 100, 5, 100).lines.isEmpty())
        val health = demoNodeServices.map { serviceHealth(it) }.toSet()
        assertTrue(ServiceHealth.Running in health && ServiceHealth.Failed in health && ServiceHealth.Exited in health)
        assertTrue(ServiceHealth.EnabledNotRunning in health && ServiceHealth.Stopped in health)
        assertEquals(demoNodeServices.map { it.name.lowercase() }.sorted(), demoNodeServices.map { it.name.lowercase() })
        assertEquals("Proxmox", demoPackageUpdates.first().origin)
        assertTrue(demoPackageUpdates.any { it.origin == "Debian" })
    }
}
