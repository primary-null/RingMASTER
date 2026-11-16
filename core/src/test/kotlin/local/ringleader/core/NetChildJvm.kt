package local.ringleader.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs a main class of the test source set in a fresh JVM, so a test can check what the JVM-wide hooks do with the real
 * `java.net.spi` and `ProxySelector` wiring, with its own flags and environment, without touching the JVM that runs the tests.
 * The child sees the same class path as the test JVM, so the module's own META-INF/services entry is on it exactly as in the app.
 * Every line the child prints that starts with `NET|` is kept as `key=value`.
 */
internal object NetChildJvm {
    class Result(val lines: List<String>, val exit: Int) {
        val values: Map<String, String> = lines.filter { it.startsWith("NET|") }.associate {
            val body = it.removePrefix("NET|")
            body.substringBefore('=') to body.substringAfter('=', "")
        }
        operator fun get(key: String): String? = values[key]
        override fun toString() = lines.joinToString("\n") + "\n(exit $exit)"
    }

    /** [classPathFirst] are directories placed before the test class path (for a services file that must win). */
    fun run(
        mainClass: String,
        args: List<String> = emptyList(),
        jvmArgs: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        classPathFirst: List<Path> = emptyList(),
        timeoutSeconds: Long = 120,
    ): Result {
        val classPath = (classPathFirst.map { it.toString() } + System.getProperty("java.class.path")).joinToString(File.pathSeparator)
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val command = listOf(Path.of(System.getProperty("java.home"), "bin", executable).toString()) + jvmArgs + listOf("-cp", classPath, mainClass) + args
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment().remove("RINGMASTER_PLAIN_RESOLVER")
        builder.environment().putAll(env)
        val process = builder.start()
        val output = CopyOnWriteArrayList<String>()
        val pump = thread(isDaemon = true) { process.inputStream.bufferedReader().forEachLine { output += it } }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { process.destroyForcibly(); pump.join(2000); error("child timed out\n$output") }
        pump.join(5000)
        return Result(output.toList(), process.exitValue())
    }

    /** A directory holding META-INF/services/[service] with one provider class name, for [classPathFirst]. */
    fun servicesDir(service: String, provider: String): Path {
        val dir = Files.createTempDirectory("ringmaster-net-services")
        val services = dir.resolve("META-INF").resolve("services")
        Files.createDirectories(services)
        Files.writeString(services.resolve(service), provider + "\n")
        return dir
    }
}
