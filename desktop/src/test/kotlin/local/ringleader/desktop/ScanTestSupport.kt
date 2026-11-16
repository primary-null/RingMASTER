package local.ringleader.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import local.ringleader.core.*

/**
 * A computer for the Scan page to read in a test: the cards and facts it is given, a scanner that finds nothing (or the one it is
 * given), and no process, no adapter and no network touched. Counts how often the page asked.
 */
internal class FakeScanEnvironment(
    private val cards: List<ScanInterface> = emptyList(),
    private val facts: NetworkFactsRead = NetworkFactsRead(null, null),
    private val make: () -> NetworkScanner = { NetworkScanner { _, _ -> emptyList() } },
    /** When set, [facts] waits for it: the read is still running until the test completes it. */
    private val hold: CompletableDeferred<Unit>? = null,
) : ScanEnvironment {
    @Volatile var cardReads = 0; private set
    @Volatile var factReads = 0; private set
    @Volatile var scansStarted = 0; private set
    override fun cards(): List<ScanInterface> { cardReads++; return cards }
    override suspend fun facts(): NetworkFactsRead { factReads++; hold?.await(); return facts }
    override fun scanner(): NetworkScanner { scansStarted++; return make() }
}

/** A session that touches nothing outside the test: invented cards, no stored choices on disk. */
internal fun quietSession(scope: CoroutineScope, env: ScanEnvironment = FakeScanEnvironment(), choices: ScanChoices = ScanChoices.inMemory()): ScanSession = ScanSession(scope, env, choices)
