package local.ringleader.desktop

import local.ringleader.core.*
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sin

/**
 * Invented fleet for the metric pictures and the frame bench: three nodes (one busy, one overloaded, one quiet) and
 * forty guests with varied load, plus the awkward cases a chart must survive: a guest that just started (two samples),
 * one with missing samples, one whose network counter reset, an idle one and one pinned near 100 %. Every name is
 * made up; no address appears. Time is fixed, so pictures repeat exactly.
 */
internal object MetricsFixtures {
    val now: Instant = Instant.parse("2026-11-16T14:20:00Z")
    private const val GIB = 1073741824L
    const val SAMPLES = 90

    data class Spec(val name: String, val kind: String, val node: String, val cpu: Double, val memGib: Double, val maxGib: Int,
        val status: String = "running", val template: Boolean = false, val tags: List<String> = emptyList())

    val nodes = listOf("node-alpha", "node-bravo", "node-charlie")
    /** CPU level, memory used GiB, memory GiB of each node. */
    private val nodeLoad = mapOf("node-alpha" to Triple(.46, 88.0, 128), "node-bravo" to Triple(.91, 119.0, 128), "node-charlie" to Triple(.04, 9.5, 64))

    val specs: List<Spec> = listOf(
        Spec("web-01", "CT", "node-alpha", .18, 1.2, 2, tags = listOf("edge")),
        Spec("web-02", "CT", "node-alpha", .21, 1.3, 2, tags = listOf("edge")),
        Spec("web-03", "CT", "node-bravo", .24, 1.4, 2, tags = listOf("edge")),
        Spec("db-primary", "VM", "node-bravo", .62, 27.0, 32, tags = listOf("data")),
        Spec("db-replica", "VM", "node-alpha", .31, 22.0, 32, tags = listOf("data")),
        Spec("cache-01", "CT", "node-bravo", .12, 6.8, 8),
        Spec("ci-runner-1", "VM", "node-bravo", .97, 14.6, 16, tags = listOf("ci")),
        Spec("ci-runner-2", "VM", "node-bravo", .84, 12.1, 16, tags = listOf("ci")),
        Spec("ci-runner-3", "VM", "node-alpha", .55, 9.4, 16, tags = listOf("ci")),
        Spec("ci-runner-4", "VM", "node-alpha", .0, .0, 16, status = "stopped", tags = listOf("ci")),
        Spec("mail", "VM", "node-alpha", .07, 3.1, 4),
        Spec("dns-1", "CT", "node-alpha", .02, .2, 1, tags = listOf("infra")),
        Spec("dns-2", "CT", "node-charlie", .003, .2, 1, tags = listOf("infra")),
        Spec("proxy", "CT", "node-alpha", .09, .6, 1, tags = listOf("edge")),
        Spec("grafana", "CT", "node-charlie", .05, 1.1, 2, tags = listOf("obs")),
        Spec("loki", "VM", "node-bravo", .38, 7.5, 8, tags = listOf("obs")),
        Spec("backup-agent", "VM", "node-charlie", .11, 2.2, 4),
        Spec("media", "VM", "node-charlie", .06, 5.1, 8),
        Spec("vpn-gw", "CT", "node-alpha", .03, .3, 1, tags = listOf("edge")),
        Spec("ldap", "CT", "node-alpha", .04, .9, 2, tags = listOf("infra")),
        Spec("git", "VM", "node-bravo", .27, 5.8, 8),
        Spec("wiki", "CT", "node-charlie", .02, .7, 2),
        Spec("jump", "CT", "node-alpha", .01, .3, 1),
        Spec("k8s-cp-1", "VM", "node-alpha", .33, 6.1, 8, tags = listOf("k8s")),
        Spec("k8s-cp-2", "VM", "node-bravo", .35, 6.3, 8, tags = listOf("k8s")),
        Spec("k8s-cp-3", "VM", "node-charlie", .3, 5.9, 8, tags = listOf("k8s")),
        Spec("k8s-w-1", "VM", "node-alpha", .71, 13.2, 16, tags = listOf("k8s")),
        Spec("k8s-w-2", "VM", "node-bravo", .78, 14.9, 16, tags = listOf("k8s")),
        Spec("k8s-w-3", "VM", "node-bravo", .88, 15.4, 16, tags = listOf("k8s")),
        Spec("k8s-w-4", "VM", "node-alpha", .0, .0, 16, status = "stopped", tags = listOf("k8s")),
        Spec("build-cache", "CT", "node-bravo", .16, 3.4, 4),
        Spec("metrics-store", "VM", "node-alpha", .42, 11.8, 16, tags = listOf("obs")),
        Spec("print", "CT", "node-charlie", .0, .0, 1, status = "stopped"),
        Spec("old-erp", "VM", "node-charlie", .0, .0, 8, status = "stopped"),
        Spec("sandbox", "VM", "node-alpha", .14, 2.6, 4),
        Spec("fresh-start", "VM", "node-charlie", .19, 1.1, 4),
        Spec("flaky-sensor", "CT", "node-charlie", .08, .4, 1),
        Spec("nat-box", "CT", "node-alpha", .05, .2, 1),
        Spec("debian-13-tpl", "VM", "node-charlie", .0, .0, 4, status = "stopped", template = true),
        Spec("ubuntu-tpl", "VM", "node-charlie", .0, .0, 4, status = "stopped", template = true),
    )

    fun id(n: Int) = 200 + n
    val overloaded get() = specs.indexOfFirst { it.name == "ci-runner-1" }
    val justStarted get() = specs.indexOfFirst { it.name == "fresh-start" }
    val gappy get() = specs.indexOfFirst { it.name == "flaky-sensor" }
    val resetCounter get() = specs.indexOfFirst { it.name == "nat-box" }

    private fun wave(n: Int, seed: Int) = sin(n * (.21 + seed * .031) + seed) * .32 + sin(n * (.83 + seed * .047) + seed * 2.1) * .16

    /** Live samples, 10 s apart, newest last. */
    fun series(seed: Int, level: Double, memGib: Double, maxGib: Int, count: Int = SAMPLES, gaps: Set<Int> = emptySet(), reset: Int? = null, held: Boolean = true): Series =
        Series((0 until count).map { k ->
            val n = SAMPLES - count + k
            val w = wave(n, seed)
            val cpu = (level * (1 + w)).coerceIn(.002, .995)
            val gap = n in gaps
            Sample(now.minusSeconds((SAMPLES - 1 - n) * 10L), if (gap) null else cpu,
                if (gap) null else (memGib * GIB * (1 + sin(n * .09 + seed) * .025)).toLong(), maxGib * GIB,
                if (gap || n == reset) null else (60_000.0 + seed * 21_000) * (1 + w) * (1 + level * 6),
                if (gap || n == reset) null else (18_000.0 + seed * 7_000) * (1 + abs(sin(n * .37 + seed)) * .5) * (1 + level * 4),
                held = held && n % 4 == 2 && !gap)
        })

    fun overview(base: Overview): Overview {
        val guests = specs.mapIndexed { n, s ->
            val running = s.status == "running"
            Guest(SAMPLE_ADDRESS, id(n), s.name, s.node, s.kind, s.status, template = s.template, tags = s.tags,
                lock = if (s.name == "backup-agent") "backup" else null,
                metrics = GuestListMetrics(cpuFraction = if (running) s.cpu else null, maxCpu = if (s.maxGib >= 8) 8.0 else 2.0,
                    memoryBytes = if (running) (s.memGib * GIB).toLong() else null, memoryLimitBytes = s.maxGib * GIB,
                    diskBytes = if (running) (s.maxGib * 2.5 * GIB).toLong() else null, diskLimitBytes = s.maxGib * 4 * GIB,
                    uptimeSeconds = if (!running) null else if (n == justStarted) 20L else 86_400L * (1 + n % 9) + 3_600L * (n % 7),
                    netIn = if (running) 40_000L + n * 9_000 else null, netOut = if (running) 12_000L + n * 4_000 else null))
        }
        val members = nodes.map { name ->
            val (cpu, mem, max) = nodeLoad.getValue(name)
            RecordedMember(SAMPLE_ADDRESS, name, "online", null, cpu, if (name == "node-charlie") 16.0 else 64.0,
                (mem * GIB).toLong(), max * GIB, (40 * GIB), 98 * GIB, 86_400L * 23)
        }
        val storage = listOf(
            RecordedStorage(SAMPLE_ADDRESS, "node-alpha", "local", false, 34 * GIB, 100 * GIB, "available", "dir", "iso,vztmpl,backup,snippets"),
            RecordedStorage(SAMPLE_ADDRESS, "node-alpha", "local-lvm", false, 610 * GIB, 900 * GIB, "available", "lvmthin", "images,rootdir"),
            RecordedStorage(SAMPLE_ADDRESS, "node-bravo", "local-lvm", false, 862 * GIB, 900 * GIB, "available", "lvmthin", "images,rootdir"),
            RecordedStorage(SAMPLE_ADDRESS, "node-charlie", "local-lvm", false, 120 * GIB, 900 * GIB, "available", "lvmthin", "images,rootdir"),
        )
        return base.copy(nodes = 3, guests = guests, members = members, storage = storage)
    }

    fun history(overview: Overview): FleetHistory {
        val guests = overview.guests.mapIndexedNotNull { n, g ->
            val s = specs[n]
            if (s.status != "running") return@mapIndexedNotNull null
            g.resource to when (n) {
                justStarted -> series(n + 1, s.cpu, s.memGib, s.maxGib, count = 2)
                gappy -> series(n + 1, s.cpu, s.memGib, s.maxGib, gaps = (40..47).toSet() + (70..72).toSet())
                resetCounter -> series(n + 1, s.cpu, s.memGib, s.maxGib, reset = 60)
                else -> series(n + 1, s.cpu, s.memGib, s.maxGib)
            }
        }.toMap()
        val perNode = nodes.mapIndexed { n, name -> val (cpu, mem, max) = nodeLoad.getValue(name); name to series(60 + n, cpu, mem, max) }.toMap()
        return FleetHistory(guests, perNode, series(80, .49, 216.5, 320))
    }

    /** Server history in the shape rrddata returns, [step] seconds apart: a gap, a restart and a steady climb. */
    fun guestHistory(step: Long, count: Int = 70, seed: Int = 3, level: Double = .55, maxGib: Int = 16, restartAt: Int? = null, gap: IntRange? = null): List<HistoryPoint> =
        (0 until count).map { n ->
            val t = (now.epochSecond - (count - 1 - n) * step).toDouble()
            if (gap != null && n in gap) return@map HistoryPoint(t, mapOf("maxcpu" to 8.0, "maxmem" to (maxGib * GIB).toDouble()))
            val w = wave(n, seed)
            val off = restartAt != null && n in restartAt..(restartAt + 1)
            val cpu = if (off) 0.0 else (level * (1 + w) + n * .002).coerceIn(.004, .99)
            HistoryPoint(t, mapOf("maxcpu" to 8.0, "cpu" to cpu, "maxmem" to (maxGib * GIB).toDouble(),
                "mem" to if (off) .4 * GIB else (maxGib * .58 + w * 2.0 + n * .02) * GIB,
                "netin" to 1_400_000.0 * (1 + w) + 90_000.0 * abs(sin(n * .5)), "netout" to 320_000.0 * (1 + abs(sin(n * .4 + seed)) * .6),
                "diskread" to 2_600_000.0 * abs(sin(n * .5 + seed)), "diskwrite" to 900_000.0 + 700_000.0 * abs(sin(n * .37 + seed * 1.3))))
        }

    fun nodeHistory(step: Long, count: Int = 70): List<HistoryPoint> = (0 until count).map { n ->
        val w = wave(n, 9)
        val cpu = (.86 * (1 + w * .4)).coerceIn(.05, .99)
        HistoryPoint((now.epochSecond - (count - 1 - n) * step).toDouble(), mapOf(
            "cpu" to cpu, "iowait" to (.04 + abs(w) * .05), "loadavg" to 38.0 * (1 + w * .5), "memused" to (112 + w * 6) * GIB, "memtotal" to 128.0 * GIB,
            "swapused" to (1.1 + abs(w)) * GIB, "swaptotal" to 8.0 * GIB, "netin" to 48_000_000.0 * (1 + w), "netout" to 21_000_000.0 * (1 + abs(sin(n * .3))),
            "pressurecpusome" to 22.0 * (1 + w), "pressureiosome" to 4.0 * (1 + abs(w)), "pressureiofull" to 1.5 * (1 + abs(w)),
            "pressurememorysome" to .8 * (1 + abs(w)), "pressurememoryfull" to .2 * (1 + abs(w))))
    }

    fun storageHistory(step: Long, count: Int = 70): List<HistoryPoint> = (0 until count).map { n ->
        HistoryPoint((now.epochSecond - (count - 1 - n) * step).toDouble(), mapOf("used" to (700.0 + n * 2.3 + sin(n * .3) * 6) * GIB, "total" to 900.0 * GIB))
    }

    /** Ping round trips in ms: a quiet line, a burst of jitter and two losses. iperf throughput in Mbit/s. */
    fun pingSamples(): List<ToolChartSample> = (0 until 60).mapNotNull { n ->
        if (n == 23 || n == 41) null
        else ToolChartSample("gateway", n.toDouble(), 0.6 + abs(sin(n * .7)) * .3 + (if (n in 30..36) 6.0 * abs(sin(n * 1.3)) else 0.0), "ms")
    }
    fun iperfSamples(): List<ToolChartSample> = (0 until 30).flatMap { n ->
        listOf(ToolChartSample("send", n.toDouble(), 920.0 + sin(n * .6) * 30, "Mbit/s"), ToolChartSample("receive", n.toDouble(), 880.0 + sin(n * .5 + 1) * 45, "Mbit/s"))
    }
}
