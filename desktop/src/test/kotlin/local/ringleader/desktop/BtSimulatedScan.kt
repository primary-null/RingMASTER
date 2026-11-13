package local.ringleader.desktop

import java.time.Instant
import java.util.Random
import local.ringleader.core.BtAdapter
import local.ringleader.core.BtDevice
import local.ringleader.core.BtKind
import local.ringleader.core.BtLiveScan
import local.ringleader.core.BtScan
import local.ringleader.core.BtSource

/**
 * A live Bluetooth scan made of invented devices, for the benchmark, the regression tests and the concept pictures.
 * Nothing here touches a radio. Names and addresses are made up; the addresses use the locally administered range.
 *
 * Each device appears at its own moment (most in the first seconds, a few late), reports at its own rate with a slow
 * random walk around its own signal level, and some go quiet before the window ends. [advanceTo] feeds the readings
 * into the real [BtLiveScan], so snapshots have the same shape as a real scan's.
 */
internal class BtSimulatedScan(val count: Int, val windowMs: Long = 20_000L, seed: Long = 42L, val at: Instant = Instant.parse("2026-11-12T09:30:00Z"),
    /** More devices arriving through the whole window (the concept pictures; the benchmark keeps the default). */
    private val lateArrivals: Boolean = false, private val simultaneous: Boolean = false) {
    private class Sim(val device: BtDevice, val appearAt: Long, val periodMs: Long, val baseRssi: Float, val quietAt: Long, var next: Long, var level: Float)

    private val rnd = Random(seed)
    private val live = BtLiveScan(BtSource.Local, windowMs, at).apply {
        adapters = listOf(BtAdapter("bt-0", "Example Bluetooth Adapter", "02:00:00:00:00:01", powered = true, discovering = true))
    }
    private val sims: List<Sim> = (0 until count).map { i -> makeSim(i) }
    var nowMs = 0L
        private set

    private fun makeSim(i: Int): Sim {
        val (base, category, kind) = Catalogue[i % Catalogue.size]
        val named = i % 4 != 3
        val name = if (!named) null else if (i < Catalogue.size) base else "$base ${i / Catalogue.size + 1}"
        val address = "%02X:%02X:%02X:%02X:%02X:%02X".format(0x02 or ((i * 4) and 0xF0), 0x1A, (i * 37) and 0xFF, (i * 91 + 7) and 0xFF, (i * 13 + 3) and 0xFF, i and 0xFF)
        val close = i % 9 == 0
        val baseRssi = if (close) -42f - rnd.nextInt(14) else -62f - rnd.nextInt(34)
        val appear = if (simultaneous) 120L else when {
            i == 0 -> 120L
            i % 7 == 6 -> 6_000L + rnd.nextInt(10_000)
            lateArrivals && i % 6 == 2 && i > 2 -> 3_000L + rnd.nextInt(12_000)
            else -> (rnd.nextInt(5_000)).toLong()
        }
        val period = 180L + rnd.nextInt(1_600)
        val quiet = if (!simultaneous && i % 5 == 4) appear + 2_500L + rnd.nextInt(6_000) else Long.MAX_VALUE
        val device = BtDevice(address, name, paired = if (i == 1 || i == 2) true else null, connected = if (i == 1) true else null,
            kind = if (category == null) BtKind.LowEnergy else kind, category = category, adapterId = "bt-0")
        return Sim(device, appear, period, baseRssi, quiet, appear, baseRssi)
    }

    /** Readings up to [ms] into the scan, in time order. */
    fun advanceTo(ms: Long) {
        val until = ms.coerceAtMost(windowMs)
        while (true) {
            val s = sims.filter { it.next <= until && it.next < it.quietAt }.minByOrNull { it.next } ?: break
            s.level = (s.level + (rnd.nextFloat() - .5f) * 5f + (s.baseRssi - s.level) * .15f).coerceIn(-99f, -30f)
            live.sight(s.device.copy(rssi = s.level.toInt()), s.next)
            s.next += s.periodMs + rnd.nextInt((s.periodMs / 3 + 1).toInt())
        }
        nowMs = until
    }

    fun address(index: Int): String = sims[index].device.address

    fun snapshot(finished: Boolean = false): BtScan = live.snapshot(if (finished) windowMs else nowMs, finished)

    companion object {
        /** Invented, generic device names (no brands) with the categories the table draws glyphs for. */
        val Catalogue: List<Triple<String, String?, BtKind>> = listOf(
            Triple("Desk keyboard", "input-keyboard", BtKind.Dual),
            Triple("Travel headphones", "audio-headphones", BtKind.Dual),
            Triple("Office mouse", "input-mouse", BtKind.LowEnergy),
            Triple("Kitchen speaker", "audio-card", BtKind.Classic),
            Triple("Pocket phone", "phone", BtKind.Dual),
            Triple("Fitness band", null, BtKind.LowEnergy),
            Triple("Living room TV", "video-display", BtKind.LowEnergy),
            Triple("Work laptop", "computer-laptop", BtKind.Dual),
            Triple("Door sensor", null, BtKind.LowEnergy),
            Triple("Plant monitor", null, BtKind.LowEnergy),
            Triple("Game pad", null, BtKind.Classic),
            Triple("Car kit", "audio-headset", BtKind.Classic),
            Triple("Tablet", "computer", BtKind.Dual),
            Triple("Thermostat", null, BtKind.LowEnergy),
            Triple("Bike computer", null, BtKind.LowEnergy),
            Triple("Earbuds", "audio-headset", BtKind.Dual),
        )
    }
}
