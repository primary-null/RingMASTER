package local.ringleader.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import local.ringleader.core.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/** Opt-in, test-classpath only. Never connects to a server. */
class UiScreenshotsTest {
    @Test fun screenshots() {
        if (System.getenv("RINGMASTER_SCREENSHOTS") != "1") return
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        workspace.exploreDemo()
        workspace.select(workspace.state.value.overview!!.guests.first())
        workspace.inspectGuest("hour")
        workspace.setLive(true,2)
        runBlocking { delay(6500) }
        val base = workspace.state.value
        val guests = (0..9).map { n -> base.overview!!.guests[n%3].copy(id=120+n,name=listOf("debian-workbench","dns-service","ubuntu-template","build-runner","metrics","archive","gateway","test-lab","web","database")[n], node="demo-node-${'a'+n%3}",template=n==2, metrics=GuestListMetrics(cpuFraction=.12,memoryBytes=1073741824,memoryLimitBytes=4294967296)) }
        val now=java.time.Instant.now()
        val inspection=base.inspection!!.copy(snapshots=InspectionSection((0..3).map { n -> GuestSnapshot("checkpoint-$n",if(n==0) null else "checkpoint-${n-1}",now.epochSecond-n*3600,"Sample checkpoint",n==2,false) } + GuestSnapshot("current","checkpoint-3",null,null,false,true)),backups=InspectionSection((0..4).map { n -> GuestBackup("sample-$n","backup-store",now.epochSecond-n*86400,2147483648,"Scheduled backup",n==1) }))
        val dense = Series((0..47).map { n -> Sample(now.minusSeconds((47-n)*2L), .08 + .06 * kotlin.math.sin(n*.7),1073741824,4294967296,1200.0,800.0) })
        val state = base.copy(selected=guests.first(),history=FleetHistory(guests.associate { it.resource to dense },guests.map { it.node }.distinct().associateWith { dense },dense),overview=base.overview!!.copy(nodes=3,guests=guests,members=base.overview!!.members + base.overview!!.members.first().copy(name="demo-node-c")),inspection=inspection.copy(resource=guests.first().resource,node=guests.first().node),tasks=(0..11).map { n ->
            val outcome=TaskOutcome.entries[n%4]; val end=if(outcome==TaskOutcome.Succeeded || outcome==TaskOutcome.Unsuccessful) now else null
            ClusterTask(DEMO_REMOTE_KEY,"demo:fixture$n","demo-node-a",listOf("vzdump","qmstart","qmstop","lab-maintenance")[n%4],"${120+n}","demo@ringmaster",now.minusSeconds(120+n*60L),end,when(outcome) { TaskOutcome.Running -> "running"; TaskOutcome.Succeeded -> "OK"; TaskOutcome.Unsuccessful -> "Backup storage unavailable"; else -> null },outcome)
        })
        val temp = Files.createTempDirectory("ringmaster-ui")
        val pins = PinStore(temp.resolve("pins.json"))
        pins.toggleGuest("demo", guests[0])
        pins.toggleView("demo", guests[0], "Console")
        pins.addService("demo", guests[1], "AdGuard UI", "http://10.1.2.3:3000")
        pins.toggleGuest("demo", guests[2])
        pins.toggleGuest("demo", guests[4])
        pins.toggleGuest("demo", Guest(DEMO_REMOTE_KEY, 404, "retired-guest", "gone", "VM", "stopped"))
        pins.toggleView("demo", guests[5], "Snapshots")
        pins.toggleGuest("demo", guests[6])
        pins.toggleView("demo", guests[7], "Backups")
        val desk = ProfileDesk(workspace, ProfileStore(temp.resolve("profiles.json")))
        val hits = (1..5).map { n -> ScanHit("192.168.50.${n+10}",8006,true,when(n) { 3 -> ScanKind.PDM; 4 -> ScanKind.PBS; else -> ScanKind.PVE },"Proxmox 9.2",if(n==5) null else "AB:".repeat(31)+"CD","CN=PVE$n.local,O=Proxmox Virtual Environment,OU=PVE Cluster Node",n==1) } +
            (1..8).map { n -> ScanHit("192.168.50.${n+30}",if(n%2==0) 443 else 80,n%2==0,ScanKind.entries[3+n%4],"Service $n") }
        val plan = NetworkPlan(listOf(ScanNetwork(ScanInterface("Ethernet","192.168.50.2",24),"192.168.50.0/24",null,(1..254).map { "192.168.50.$it" })))
        val screens = linkedMapOf<String,@Composable () -> Unit>()
        listOf("idle","mid","complete").forEach { phase ->
            val session = ScanSession(scope)
            if(phase!="idle") {
                if(phase=="mid") session.javaClass.getDeclaredField("running\$delegate").apply { isAccessible=true }.get(session).let { (it as MutableState<Boolean>).value=true }
                val progress = NetworkProgress(plan,if(phase=="mid") 100 else 254,hits=if(phase=="mid") hits.take(3) else hits,finished=phase=="complete")
                session.javaClass.getDeclaredField("progress\$delegate").apply { isAccessible=true }.get(session).let { (it as MutableState<NetworkProgress?>).value=progress }
            }
            screens["scan-$phase"] = { ScanScreen(session,emptyList()) {} }
            if(phase=="complete") screens["scan-services-scrolled"] = { ScanScreen(session,emptyList(),initialScroll=1000) {} }
        }
        val terminalModel = TerminalModel(100,32)
        terminalModel.feed(("\u001b[H\u001b[32m  CPU [||||||||||||||||    42%]    Tasks: 112, 1 running\r\n" +
            "\u001b[36m  Mem [||||||||           1.2G/8G]  Uptime: 2 days\r\n\r\n" +
            "\u001b[7m  PID USER      PRI  NI    VIRT    RES  CPU%  MEM%  Command\u001b[0m\r\n" +
            "\u001b[32m  812 root       20   0    164M    24M   4.2   0.3  htop\r\n" +
            "\u001b[33m  109 root       20   0    128M    12M   0.3   0.1  systemd\u001b[0m\r\n").toByteArray())
        Thread.sleep(150)
        screens["terminal"] = { TerminalPane(terminalModel,null,"Connected 100?32 (sample)",{}) }
        screens["fleet-overview"] = { InventoryScreen(state.copy(selected=null),workspace,null,"",{},"All",{},{},{},pins,"demo") }
        screens["fleet"] = { InventoryScreen(state,workspace,null,"",{},"All",{},{},{},pins,"demo") }
        listOf("Summary","Hardware","Snapshots","Backups").forEach { tab -> screens["guest-${tab.lowercase()}"] = { GuestDetailPage(state,workspace,{},{_,_->},initialTab=tab,pins=pins,connectionKey="demo") } }
        val profiles=listOf(ConnectionProfile("11111111-1111-1111-1111-111111111111","Lab cluster",DIRECT_PVE_KIND,"https://pve.example.test:8006","demo@pve!desktop",null),ConnectionProfile("22222222-2222-2222-2222-222222222222","Datacenter",PDM_KIND,"https://pdm.example.test:8443","demo@pdm!desktop",null))
        screens["desk"] = { DeskScreen(state,desk.state.value.copy(profiles=profiles),desk) {} }
        screens["desk-form-scrolled"] = { DeskScreen(state,desk.state.value.copy(profiles=profiles),desk,initialScroll=700) {} }
        screens["guest-snapshots-scrolled"] = { GuestDetailPage(state,workspace,{},{_,_->},initialTab="Snapshots",initialScroll=280,pins=pins,connectionKey="demo") }
        screens["activity"] = { ActivityScreen(state,workspace,null,{},{}) }
        val btAt = java.time.Instant.parse("2026-11-08T18:04:05Z")
        val btScan = BtScan(BtSource.Local, listOf(BtAdapter("bt-0","MediaTek Bluetooth Adapter","AA:BB:CC:DD:EE:01",powered=true,discovering=false)), listOf(
            BtDevice("11:22:33:44:55:01","Keychron K2",rssi=-40,paired=true,connected=true,kind=BtKind.Classic,category="input-keyboard"),
            BtDevice("11:22:33:44:55:02","MX Master 3",rssi=-61,paired=true,connected=false,kind=BtKind.LowEnergy,category="input-mouse"),
            BtDevice("11:22:33:44:55:03","UGREEN-BT505",rssi=-57,paired=true,kind=BtKind.Classic,category="audio-card"),
            BtDevice("11:22:33:44:55:04","Pixel 8",rssi=-73,kind=BtKind.Dual,category="phone"),
            BtDevice("11:22:33:44:55:05",rssi=-93,kind=BtKind.LowEnergy),
            BtDevice("11:22:33:44:55:06",rssi=-98,kind=BtKind.LowEnergy),
            BtDevice("11:22:33:44:55:07","Living Room TV",rssi=-84,paired=true,kind=BtKind.Classic,category="video-display"),
            BtDevice("11:22:33:44:55:08","Framework Laptop",rssi=-49,paired=true,kind=BtKind.Dual,category="computer"),
        ), btAt)
        screens["bluetooth-empty"] = { BluetoothScreen(null) }
        screens["bluetooth-results"] = { BtScanView(btScan, scanning = false, platform = "Windows") }
        screens["bluetooth-note"] = { BtScanView(BtScan(BtSource.Local, emptyList(), emptyList(), btAt, BT_NO_ADAPTER), scanning = false, platform = "Windows") }
        screens["components"] = { Column { Row { listOf(.04,.62,.93).forEach { ArcGauge(it,"CPU") } }; Sparkline(Series(base.history.cluster.samples + Sample(now,null,null,null,null,null) + base.history.cluster.samples),Modifier.fillMaxWidth().height(80.dp)) } }
        screens["history-guest"] = { Column(Modifier.verticalScroll(rememberScrollState())) { HistoryGroup(state.copy(chartHistory=InspectionSection(demoInspection(guests.first(),"hour").history.value)),workspace) } }
        screens["history-node"] = { Column(Modifier.verticalScroll(rememberScrollState())) { HistoryGroup(state.copy(chartHistory=InspectionSection(demoNodeHistory("hour","AVERAGE"))),workspace,"sample-node") } }
        val phase=System.getenv("RINGMASTER_UI_PHASE") ?: "before"
        screens["guest-bluetooth"] = { GuestDetailPage(state,workspace,{},{_,_->},initialTab="Bluetooth",pins=pins,connectionKey="demo") }
        // Dark at every size, then light and showcase variants of the main screens at 1920x1080.
        val looks = listOf(Triple("", false, false)) + listOf(Triple("light-", true, false), Triple("showcase-", false, true))
        val lookScreens = setOf("history-guest", "history-node", "fleet", "fleet-overview", "guest-summary", "guest-actions-light", "desk", "activity", "bluetooth-results", "scan-complete", "guest-hardware", "guest-bluetooth")
        for((prefix, light, showcase) in looks) for((w,h) in if (prefix.isEmpty()) listOf(1920 to 1080,1600 to 900,960 to 640) else listOf(1920 to 1080)) for((screen,content) in screens.filterKeys { prefix.isEmpty() || it in lookScreens }) for(expanded in if (prefix.isEmpty()) listOf(false,true) else listOf(false)) {
            Appearance.preview(light, showcase)
            val name = prefix + screen
            val scene=ImageComposeScene(w,h) {
                MaterialTheme(colorScheme=ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                    Surface(Modifier.fillMaxSize()) { Column {
                        Row(Modifier.fillMaxWidth().height(32.dp)) { Text("\u25c8 RingMASTER",Modifier.weight(1f),color=Tech.Amber);WindowControl("\u2014") {};WindowControl("\u25a1") {};WindowControl("\u00d7") {} }
                        Row(Modifier.weight(1f)) {
                        NavigationRail(state,when { screen.startsWith("scan") -> "Scan"; screen=="desk" -> "Desk"; screen=="activity" -> "Activity"; screen.startsWith("bluetooth") -> "Bluetooth"; else -> "Fleet" },"Screenshot / Windows",workspace,initialExpanded=expanded,pins=pins,connectionKey="demo") {}
                        Column(Modifier.weight(1f).padding(16.dp)) {
                            Row(Modifier.height(40.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) { Text("WORKSPACE / ${name.uppercase()}",fontSize=Tech.Label,color=Tech.Muted);Text("RingMASTER / $name",fontSize=Tech.Title) }
                            HorizontalDivider(color=Tech.Edge,modifier=Modifier.padding(vertical=Tech.Space12))
                            Box(Modifier.weight(1f)) { content() }
                        }
                    } } }
                } }
            }
            try { val file=File("../artifacts/ui/$phase/$name-${if(expanded) "expanded" else "collapsed"}-${w}x$h.png");file.parentFile.mkdirs();file.writeBytes(scene.render().encodeToData()!!.bytes) } finally { scene.close() }
        }
        Appearance.preview(false)
        desk.close();workspace.close();scope.cancel()
    }
}

