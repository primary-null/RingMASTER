package local.ringleader.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The code budget: what stops the source from getting denser, file by file.
 *
 * Three costs are counted for every Kotlin file under `core/src` and `desktop/src`: lines longer than [MAX_LINE]
 * characters, the file's length once it is over [MAX_FILE] lines, and errors caught and dropped without a name
 * (`catch (_: Exception)`). A new file must have none of the three. An older file is listed in `code-budget.txt` with
 * the costs it had when the budget was introduced, and may not exceed them: a long file cannot grow, so new behaviour
 * goes into a new file.
 *
 * The ledger is kept exact. When a file improves, the test fails until its numbers are lowered, so a gain cannot be
 * spent again later: run the test with `RINGMASTER_CODE_BUDGET=update` and commit the ledger. That mode only ever
 * lowers numbers. To raise one on purpose, edit the ledger by hand and say why in the commit.
 */
class CodeBudgetTest {
    private data class Cost(val longLines: Int, val ceiling: Int, val dropped: Int) {
        val free get() = longLines == 0 && ceiling == 0 && dropped == 0
    }

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val ledger = File(root, "core/src/test/resources/code-budget.txt")
    private val droppedError = Regex("""catch \(_: (Exception|Throwable)\)""")

    private fun sources(): Map<String, Cost> {
        val files = listOf("core/src", "desktop/src").flatMap { top ->
            File(root, top).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        return files.associate { file -> file.relativeTo(root).invariantSeparatorsPath to costOf(file) }.toSortedMap()
    }

    private fun costOf(file: File): Cost {
        val lines = file.readLines()
        val long = lines.count { it.length > MAX_LINE }
        val ceiling = if (lines.size > MAX_FILE) lines.size else 0
        return Cost(long, ceiling, lines.sumOf { droppedError.findAll(it).count() })
    }

    private fun readLedger(): Map<String, Cost> = ledger.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
        val (long, ceiling, dropped, path) = line.split(' ', limit = 4)
        path to Cost(long.toInt(), ceiling.toInt(), dropped.toInt())
    }

    private fun writeLedger(costs: Map<String, Cost>) {
        val head = listOf(
            "# The code budget (CodeBudgetTest). One file per line: lines over $MAX_LINE characters, the length it may not",
            "# exceed (0 while it is under $MAX_FILE lines), errors caught and dropped without a name, and its path.",
            "# A file not listed here must have none of the three.",
        )
        val rows = costs.filterValues { !it.free }.map { (path, c) -> "${c.longLines} ${c.ceiling} ${c.dropped} $path" }
        ledger.writeText((head + rows).joinToString("\n") + "\n")
    }

    @Test fun noFileCostsMoreThanItsBudget() {
        val now = sources()
        val before = if (ledger.isFile) readLedger() else emptyMap()
        val allowed = { path: String -> before[path] ?: Cost(0, 0, 0) }
        val over = now.mapNotNull { (path, cost) ->
            val budget = allowed(path)
            val faults = listOfNotNull(
                "${cost.longLines} lines over $MAX_LINE characters (budget ${budget.longLines})".takeIf { cost.longLines > budget.longLines },
                "${cost.ceiling} lines long (budget ${if (budget.ceiling == 0) MAX_FILE else budget.ceiling})".takeIf { cost.ceiling > budget.ceiling },
                "${cost.dropped} errors caught and dropped (budget ${budget.dropped})".takeIf { cost.dropped > budget.dropped },
            )
            if (faults.isEmpty()) null else "$path: " + faults.joinToString("; ")
        }
        val mode = System.getenv("RINGMASTER_CODE_BUDGET")
        if (mode == "update" && over.isEmpty()) writeLedger(now)
        assertTrue(over.isEmpty(), "Over budget. Shorten the lines, split the file, or name the error you catch:\n" + over.joinToString("\n"))
        if (mode == "update") return
        val slack = before.mapNotNull { (path, budget) ->
            val cost = now[path]
            when {
                cost == null -> "$path is gone"
                cost != budget -> "$path improved to ${cost.longLines} ${cost.ceiling} ${cost.dropped}"
                else -> null
            }
        }
        assertTrue(slack.isEmpty(), "The ledger is out of date. Run this test with RINGMASTER_CODE_BUDGET=update and commit it:\n" + slack.joinToString("\n"))
    }

    private companion object {
        const val MAX_LINE = 160
        const val MAX_FILE = 800
    }
}
