package local.ringleader.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NetworkDefaults.install() has to run before anything resolves a name or connects, in every process the app starts: the window, the
 * resident process that owns the schedules, and the browser-hosted mode all start in desktop/Main.kt. This keeps that true.
 */
class NetworkDefaultsEntryTest {
    @Test fun mainInstallsTheNetworkDefaultsBeforeAnythingElse() {
        val text = File("src/main/kotlin/local/ringleader/desktop/Main.kt").readText(Charsets.UTF_8)
        val body = text.substringAfter("fun main(args: Array<String>) {")
        val first = body.lines().map { it.trim() }.first { it.isNotEmpty() && !it.startsWith("//") }
        assertEquals("NetworkDefaults.install()", first, "the first statement of main")
    }

    @Test fun noOtherProgramEntryPointStartsWithoutIt() {
        val entries = listOf(File("src/main/kotlin"), File("../core/src/main/kotlin")).flatMap { root ->
            assertTrue(root.isDirectory, "Missing source folder ${root.path}")
            root.walkTopDown().filter { it.isFile && it.extension == "kt" && Regex("""^\s*(@JvmStatic\s+)?fun main\(""", RegexOption.MULTILINE).containsMatchIn(it.readText(Charsets.UTF_8)) }.map { it.name }.toList()
        }.sorted()
        // ReleaseTool.kt signs update manifests offline and never opens a connection; NetworkSelfCheck.kt installs the defaults itself.
        assertEquals(listOf("Main.kt", "NetworkSelfCheck.kt", "ReleaseTool.kt"), entries, "a new entry point must call NetworkDefaults.install() first, then be added here")
    }
}
