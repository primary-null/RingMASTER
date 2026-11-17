package local.ringleader.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.ringleader.core.WIFI_SCAN_SECONDS_DEFAULT
import local.ringleader.core.WifiIssue
import local.ringleader.core.WifiScan
import local.ringleader.core.WifiScanner

/** How many signal readings of the current link are kept for its line. */
const val WIFI_HISTORY_CAP = 120

/** How long the Wi-Fi view waits between its own readings of this computer. */
const val WIFI_FOLLOW_MILLIS = 4_000L

/** With no adapter there is no signal to follow: the view looks again this seldom, in case one is plugged in. */
private const val NO_ADAPTER_MILLIS = 30_000L

/** The first reading is a little longer than the ones that follow, so the adapters and the link are surely there. */
private const val FIRST_READ_SECONDS = 2
private const val FOLLOW_READ_SECONDS = 1

/**
 * Wi-Fi of this computer. While the Wi-Fi view is shown it is read every few seconds by itself; a full scan runs when the
 * user asks. Each pass updates the panel and adds a reading to the current link's line.
 *
 * [guests] are the VMs that hold a Wi-Fi adapter, offered as sources that lead to their own tab. [fix] gives the action
 * that opens the system page for an issue. [onScan] receives every result, so the board can show this computer's
 * adapters. Every call arrives on the window's thread.
 */
internal class LiveWifiFeed(
    private val scope: CoroutineScope,
    private val scanner: WifiScanner?,
    private val guests: () -> List<WifiSourceView> = { emptyList() },
    private val fix: ((WifiIssue) -> (() -> Unit)?)? = null,
    private val followMillis: Long = WIFI_FOLLOW_MILLIS,
    private val onScan: (WifiScan) -> Unit = {},
) : WifiFeed {
    private val thisComputer = WifiSourceView(THIS_COMPUTER_KEY, THIS_COMPUTER_LABEL)
    private val noScanner = RadioNote(WIFI_NO_SCANNER)
    private val state = MutableStateFlow(WifiPanel(listOf(thisComputer), THIS_COMPUTER_KEY, refusal = noScanner.takeIf { scanner == null }))
    override val panel: StateFlow<WifiPanel> get() = state

    private var source = THIS_COMPUTER_KEY
    private var last: WifiScan? = null
    private val history = ArrayDeque<Int?>()
    private var job: Job? = null
    private var failure: String? = null

    /** Counts scans, so a pass that arrives after its scan ended (or after the next one began) is recognised and dropped. */
    private var run = 0

    private val scanning get() = job?.isActive == true
    private val onThisComputer get() = source == THIS_COMPUTER_KEY

    init {
        if (scanner != null) scope.launch { followWhileShown() }
    }

    /** While the Wi-Fi view is on screen this computer is read again and again; when the view goes away the reading stops. */
    private suspend fun followWhileShown() {
        val shown = state.subscriptionCount.map { it > 0 }.distinctUntilChanged()
        shown.collectLatest { visible ->
            while (visible) {
                if (onThisComputer && !scanning) scan(if (last == null) FIRST_READ_SECONDS else FOLLOW_READ_SECONDS)
                val nothingToFollow = last?.issue == WifiIssue.NoAdapter
                delay(if (nothingToFollow) maxOf(followMillis, NO_ADAPTER_MILLIS) else followMillis)
            }
        }
    }

    override fun choose(source: String) {
        this.source = source
        publish(scanning && onThisComputer)
    }

    override fun refresh() = scan(WIFI_SCAN_SECONDS_DEFAULT)

    /** A short first reading, so the adapters and the current link are there before the user asks for a full scan. */
    fun readOnce() {
        if (last == null) scan(FIRST_READ_SECONDS)
    }

    /** The guest sources come from the board: call when it changed. */
    fun sourcesChanged() = publish(scanning && onThisComputer)

    private fun scan(seconds: Int) {
        val backend = scanner ?: return
        if (scanning) return
        val mine = ++run
        failure = null
        job = scope.launch {
            publish(scanning = true)
            try {
                var passes = 0
                val result = withContext(Dispatchers.IO) {
                    backend.scan(seconds) { pass ->
                        // Passes arrive from the scanning thread. The end result is reported below, not here.
                        if (!pass.finished) scope.launch {
                            if (mine == run && scanning) {
                                passes++
                                accept(pass, scanning = true, reading = true)
                            }
                        }
                    }
                }
                run++
                // The end result repeats the last pass: it adds a reading only when no pass reported on its own.
                accept(result, scanning = false, reading = passes == 0)
            } catch (e: CancellationException) {
                backend.cancel()
                throw e
            } catch (e: Exception) {
                // A scanner answers "no adapter" and the like with a note. An exception is a fault: say so above the readout.
                run++
                failure = e.message ?: "The Wi-Fi scan failed."
                publish(scanning = false)
            }
        }
    }

    private fun accept(scan: WifiScan, scanning: Boolean, reading: Boolean) {
        last = scan
        if (reading) history.addLast(scan.connectedLink?.signalDbm)
        while (history.size > WIFI_HISTORY_CAP) history.removeFirst()
        onScan(scan)
        publish(scanning)
    }

    private fun publish(scanning: Boolean) {
        val adapterName = last?.adapters?.firstOrNull()?.let { it.hardware.product ?: it.name }
        val sources = listOf(thisComputer.copy(detail = adapterName)) + guests()
        val chosen = sources.firstOrNull { it.key == source } ?: sources.first()
        val known = last
        state.value = when {
            // A VM as source shows only why the scan runs elsewhere; that sentence is the source's own refusal.
            chosen.inGuest -> WifiPanel(sources, chosen.key)
            scanner == null -> WifiPanel(sources, chosen.key, refusal = noScanner)
            known == null -> WifiPanel(sources, chosen.key, scanning = scanning, error = failure)
            else -> wifiPanel(known, sources, chosen.key, scanning, history.toList(), fix).copy(error = failure)
        }
    }
}
