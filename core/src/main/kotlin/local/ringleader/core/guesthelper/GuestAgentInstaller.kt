package local.ringleader.core.guesthelper

import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.ringleader.core.AgentExecStatus
import local.ringleader.core.AmbiguousPowerResultException
import local.ringleader.core.Connection
import local.ringleader.core.Guest
import local.ringleader.core.GuestAgentFailure
import local.ringleader.core.GuestPermissionException
import local.ringleader.core.GuestRefusal
import local.ringleader.core.PveClient
import local.ringleader.core.ProxmoxTransport
import local.ringleader.core.boundGuestPath

/*
 * Installing and removing the Engine through the QEMU guest agent: the bundle goes in as parts (`file-write` takes at most
 * 60 KiB of base64 per call and replaces the file each time), then one `exec` joins them, checks the bundle's hash and
 * runs the installer, whose last lines name the certificate and the token. Each request is sent once and never retried;
 * the user confirmed the whole install in one click before the first one.
 */

/** The two guest-agent calls an install needs. The live one talks to Proxmox; tests record. */
interface GuestAgentChannel {
    /** One `agent/file-write` of ready base64 ([base64] decodes to the file's bytes). */
    fun writeFile(connection: Connection, guest: Guest, path: String, base64: String)

    fun run(connection: Connection, guest: Guest, command: List<String>, firstPollSeconds: Int, budgetSeconds: Int): AgentExecStatus
}

class LiveGuestAgentChannel(private val client: PveClient = PveClient()) : GuestAgentChannel {
    private val transport = ProxmoxTransport()

    override fun writeFile(connection: Connection, guest: Guest, path: String, base64: String) {
        val base = boundGuestPath(connection, guest).removeSuffix("/status/current")
        val body = buildJsonObject {
            put("file", path)
            put("content", base64)
            put("encode", 0)
        }.toString().toByteArray(Charsets.UTF_8)
        try {
            transport.direct(connection, "$base/agent/file-write", body)
        } catch (_: GuestPermissionException) {
            throw GuestAgentFailure(GuestRefusal.NoPermission)
        }
    }

    override fun run(connection: Connection, guest: Guest, command: List<String>, firstPollSeconds: Int, budgetSeconds: Int) =
        client.guestAgentRun(connection, guest, command, firstPollSeconds, budgetSeconds)
}

/** Base64 characters per `file-write`: the API's 60 KiB, a multiple of four so every part decodes on its own. */
const val GUEST_AGENT_PART_CHARS = 60 * 1024

const val GUEST_HELPER_DELIVERY_LOST =
    "The guest agent did not confirm one part of the upload, so the install was stopped. Parts may be left in /var/tmp; " +
        "they are removed by the next install."

/** Install progress: parts written of all parts, then the installer running. */
data class GuestHelperProgress(val partsSent: Int, val parts: Int, val installing: Boolean = false)

class GuestAgentInstaller(private val channel: GuestAgentChannel = LiveGuestAgentChannel(), private val random: SecureRandom = SecureRandom()) {

    /**
     * Sends [bundle] and runs its installer. Returns what the installer printed; throws [GuestAgentFailure] when the agent
     * refuses (no permission, not answering) and [IllegalStateException] with a sentence for anything else.
     */
    fun install(connection: Connection, guest: Guest, bundle: GuestHelperBundle, progress: (GuestHelperProgress) -> Unit = {}): GuestHelperInstalled {
        val base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(bundle.file))
        val parts = base64.chunked(GUEST_AGENT_PART_CHARS)
        val run = ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val prefix = "/var/tmp/ringmaster-engine-$run-"
        progress(GuestHelperProgress(0, parts.size))
        parts.forEachIndexed { index, part ->
            try {
                channel.writeFile(connection, guest, prefix + "%04d".format(index), part)
            } catch (_: AmbiguousPowerResultException) {
                throw IllegalStateException(GUEST_HELPER_DELIVERY_LOST)
            }
            progress(GuestHelperProgress(index + 1, parts.size))
        }
        progress(GuestHelperProgress(parts.size, parts.size, installing = true))
        val bundlePath = "/var/tmp/ringmaster-engine-$run.tar.gz"
        // Older runs' parts are cleared first, so a stopped install never leaves files behind for long.
        val script = listOf(
            "find /var/tmp -maxdepth 1 -name 'ringmaster-engine-*-[0-9][0-9][0-9][0-9]' ! -name 'ringmaster-engine-$run-*' -delete",
            "cat $prefix[0-9][0-9][0-9][0-9] > $bundlePath",
            "rm -f $prefix[0-9][0-9][0-9][0-9]",
            installScript(bundlePath, bundle.sha256, assumeYes = true),
        ).joinToString("\n")
        val status = channel.run(connection, guest, listOf("sh", "-c", script), 2, 120)
        val output = status.out + "\n" + status.err
        val installed = parseInstallerOutput(output)
        if (status.exitCode != 0 || installed == null) {
            throw IllegalStateException("The installer did not finish. Its last line: " + lastLine(output))
        }
        return installed
    }

    /** Runs the uninstaller in the guest. Throws with the uninstaller's last line when it did not remove everything. */
    fun remove(connection: Connection, guest: Guest) {
        val status = channel.run(connection, guest, listOf("sh", "-c", "$GUEST_HELPER_UNINSTALL --yes"), 1, 60)
        val output = status.out + "\n" + status.err
        if (status.exitCode != 0 || GUEST_HELPER_REMOVED !in output) {
            throw IllegalStateException("The Engine was not removed. The uninstaller's last line: " + lastLine(output))
        }
    }

    private fun lastLine(output: String) = output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.take(200) ?: "(none)"
}
