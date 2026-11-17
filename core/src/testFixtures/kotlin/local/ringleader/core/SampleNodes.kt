package local.ringleader.core

/*
 * What the sample fleet's nodes answer: history, network interfaces, services, the system log, package updates and the
 * account's permissions. Handwritten in the shapes of the server's calls; none of it is a captured response.
 */

fun sampleNodeHistory(timeframe: String, cf: String): List<HistoryPoint> {
    historySuffix(timeframe, cf)
    val now = java.time.Instant.now().epochSecond.toDouble()
    return (0..119).map { n ->
        val wave = (1 + kotlin.math.sin(n/9.0))/2
        val scale = if(cf == "MAX") 1.3 else 1.0
        HistoryPoint(now-(119-n)*historySeconds(timeframe)/119, historyKeys.associateWith { k -> when(k) {
            "cpu" -> (.1+.4*wave)*scale; "iowait" -> .03*wave*scale; "loadavg" -> 1+wave*3
            "memused" -> 8e9+wave*2e9; "memtotal" -> 16e9; "swapused" -> wave*1e8; "swaptotal" -> 2e9
            "netin", "netout" -> (1e5+wave*5e6)*scale
            else -> if(k.startsWith("pressure")) wave*4*scale else null
        } })
    }
}

/** Two bridges, a bond, a VLAN, two wired ports and an unused wireless card. */
val sampleNodeNetwork: List<NodeInterface> = parseNodeNetwork("""{"data":[
 {"iface":"vmbr0","type":"bridge","active":1,"autostart":1,"cidr":"192.168.50.10/24","gateway":"192.168.50.1","bridge_ports":"bond0",
  "bridge_vlan_aware":1,"comments":"LAN"},
 {"iface":"vmbr1","type":"bridge","active":1,"autostart":1,"bridge_ports":"none","comments":"Isolated lab"},
 {"iface":"bond0","type":"bond","active":1,"autostart":1,"slaves":"enp1s0 enp2s0","bond_mode":"802.3ad","mtu":9000},
 {"iface":"vmbr0.20","type":"vlan","active":1,"autostart":1,"cidr":"10.20.0.10/24","vlan-raw-device":"vmbr0"},
 {"iface":"enp1s0","type":"eth","active":1,"autostart":0},
 {"iface":"enp2s0","type":"eth","active":1,"autostart":0},
 {"iface":"wlp3s0","type":"eth","active":0,"autostart":0}
]}""")

/** Handwritten sample rows in the shape of the services call. Not a captured response. */
val sampleNodeServices: List<NodeService> = parseNodeServices("""{"data":[
 {"service":"pveproxy","name":"pveproxy","desc":"PVE API Proxy Server","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"pvedaemon","name":"pvedaemon","desc":"PVE API Daemon","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"pvestatd","name":"pvestatd","desc":"PVE Status Daemon","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"pve-cluster","name":"pve-cluster","desc":"PVE Cluster Filesystem","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"corosync","name":"corosync","desc":"Corosync Cluster Engine","state":"dead","unit-state":"disabled","active-state":"inactive"},
 {"service":"pve-firewall","name":"pve-firewall","desc":"Proxmox VE firewall","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"pve-ha-crm","name":"pve-ha-crm","desc":"PVE Cluster HA Resource Manager Daemon","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"pve-ha-lrm","name":"pve-ha-lrm","desc":"PVE Local HA Resource Manager Daemon","state":"dead","unit-state":"enabled","active-state":"inactive"},
 {"service":"pvescheduler","name":"pvescheduler","desc":"Proxmox VE scheduler","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"spiceproxy","name":"spiceproxy","desc":"PVE SPICE Proxy Server","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"pve-guests","name":"pve-guests","desc":"PVE guests","state":"exited","unit-state":"enabled","active-state":"active"},
 {"service":"sshd","name":"sshd","desc":"OpenBSD Secure Shell server","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"chrony","name":"chrony","desc":"chrony, an NTP client/server","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"smartd","name":"smartd","desc":"Self Monitoring and Reporting Technology (SMART) Daemon","state":"failed","unit-state":"enabled",
  "active-state":"failed"}
]}""")

const val SAMPLE_SYSLOG_START_TOTAL = 1_240
private val sampleSyslogTemplates = listOf(
    "pvedaemon[2184]: <root@pam> successful auth for user 'root@pam'",
    "pvestatd[1190]: status update time (4.121 seconds)",
    "pveproxy[2201]: worker 2210 started",
    "systemd[1]: Started user@1000.service - User Manager for UID 1000.",
    "pvedaemon[2185]: <root@pam> starting task UPID:%NODE%:00002A3B:0003F0A1:6911F2C0:vncproxy:120:root@pam:",
    "pmxcfs[1042]: [status] notice: received log",
    "kernel: vmbr0: port 3(tap120i0) entered forwarding state",
    "pve-firewall[1183]: firewall update time (0.012 seconds)",
    "smartd[884]: Device: /dev/sda [SAT], SMART Prefailure Attribute: 1 Raw_Read_Error_Rate changed from 80 to 79",
    "cron[860]: (root) CMD (command -v debian-sa1 > /dev/null && debian-sa1 1 1)",
)

/** Line [n] of a node's log. Deterministic, so the log can grow without storing anything. */
fun sampleSyslogLine(node: String, n: Int): SyslogLine {
    val second = n % 60
    val minute = (n / 60) % 60
    val text = String.format(java.util.Locale.ROOT, "Nov 09 14:%02d:%02d %s %s", minute, second, node,
        sampleSyslogTemplates[n % sampleSyslogTemplates.size].replace("%NODE%", node))
    return SyslogLine(n, text)
}

fun sampleSyslogPage(node: String, start: Int, limit: Int, total: Int): SyslogPage =
    SyslogPage(((start + 1)..minOf(total, start + limit)).map { sampleSyslogLine(node, it) }, total)

/** Handwritten sample rows in the shape of the update list. Not a captured response. */
val samplePackageUpdates: List<PackageUpdate> = parsePackageUpdates("""{"data":[
 {"Package":"pve-manager","Title":"pve-manager","Description":"Proxmox Virtual Environment Management Tools","Version":"9.2.23","OldVersion":"9.2.21",
  "Arch":"all","Origin":"Proxmox","Priority":"optional","Section":"admin"},
 {"Package":"proxmox-kernel-6.14","Title":"proxmox-kernel-6.14","Description":"Latest Proxmox Kernel Image","Version":"6.14.11-6",
  "OldVersion":"6.14.11-4","Arch":"all","Origin":"Proxmox","Priority":"optional","Section":"admin"},
 {"Package":"qemu-server","Title":"qemu-server","Description":"Qemu Server Tools","Version":"9.2.6","OldVersion":"9.2.4","Arch":"amd64",
  "Origin":"Proxmox","Priority":"optional","Section":"admin"},
 {"Package":"libpve-common-perl","Title":"libpve-common-perl","Description":"Proxmox VE base library","Version":"9.2.9","OldVersion":"9.2.8",
  "Arch":"all","Origin":"Proxmox","Priority":"optional","Section":"perl"},
 {"Package":"openssl","Title":"openssl","Description":"Secure Sockets Layer toolkit - cryptographic utility","Version":"3.5.4-1~deb13u1",
  "OldVersion":"3.5.1-1","Arch":"amd64","Origin":"Debian","Priority":"optional","Section":"utils"},
 {"Package":"curl","Title":"curl","Description":"command line tool for transferring data with URL syntax","Version":"8.14.1-2+deb13u2",
  "OldVersion":"8.14.1-2","Arch":"amd64","Origin":"Debian","Priority":"optional","Section":"web"},
 {"Package":"tzdata","Title":"tzdata","Description":"time zone and daylight-saving time data","Version":"2025c-0+deb13u1","OldVersion":"2025b-5",
  "Arch":"all","Origin":"Debian","Priority":"required","Section":"localization"}
]}""")

/** The PVEAuditor role as pve-access-control builds it (the `audit` group of every category), set with Propagate. */
private const val SAMPLE_AUDITOR =
    "\"Sys.Audit\":1,\"VM.Audit\":1,\"VM.GuestAgent.Audit\":1,\"Datastore.Audit\":1,\"Pool.Audit\":1,\"SDN.Audit\":1,\"Mapping.Audit\":1"

/**
 * A permission dump shaped like the real response: PVEAuditor on / (so the fixed top-level paths list the
 * auditor set), part of PVEVMUser added on /vms, and three explicit paths without Propagate. A path that is not listed
 * (such as /vms/120 or /mapping/notifications) is answered from its parent. The sample shows every state in the table.
 * It is not a captured response.
 */
val samplePermissions: PermissionDump = parsePermissions("""{"data":{
 "/":{$SAMPLE_AUDITOR},
 "/access":{$SAMPLE_AUDITOR},
 "/nodes":{$SAMPLE_AUDITOR},
 "/pool":{$SAMPLE_AUDITOR},
 "/sdn":{$SAMPLE_AUDITOR},
 "/storage":{$SAMPLE_AUDITOR},
 "/vms":{$SAMPLE_AUDITOR,"VM.Console":1,"VM.PowerMgmt":1,"VM.Backup":1,"VM.Config.CDROM":1,"VM.Config.Cloudinit":1},
 "/nodes/demo-node-a":{"Sys.Audit":0,"Sys.Console":0,"Sys.Syslog":0},
 "/storage/shared-data":{"Datastore.Audit":0,"Datastore.AllocateSpace":0},
 "/vms/130":{"VM.Audit":0,"VM.GuestAgent.Audit":0}
}}""")
