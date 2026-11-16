package local.ringleader.core.audit

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertTrue
import local.ringleader.core.Connection
import local.ringleader.core.DIRECT_PVE_KIND
import local.ringleader.core.ProfileDraft
import local.ringleader.core.PveWebUi
import local.ringleader.core.Redact
import local.ringleader.core.certificateOrigin
import local.ringleader.core.completeServerAddress
import local.ringleader.core.ipLiteral
import local.ringleader.core.profileFromDraft
import local.ringleader.core.routeTarget
import local.ringleader.core.serverOrigin
import local.ringleader.core.sessionIdentity

/**
 * IPv4/IPv6 audit, evidence 0: what each typed server address does end to end.
 *
 * This test asserts nothing about correctness. It prints one `MATRIX|` line per input so the audit report's per-input
 * table is real output. Documentation addresses only (RFC 5737 192.0.2.0/24, RFC 3849 2001:db8::/32, example.test).
 * Run: `./gradlew.bat :core:test --tests "local.ringleader.core.audit.Ipv46MatrixAuditTest" --console=plain -i`
 */
class Ipv46MatrixAuditTest {
    private fun <T> shown(block: () -> T): String = try {
        block().toString()
    } catch (e: Throwable) {
        "REFUSED(${e.javaClass.simpleName}: ${e.message?.take(70)})"
    }

    private val inputs = listOf(
        "192.0.2.10", "192.0.2.10:8006", "https://192.0.2.10", "pve.example.test", "PVE.EXAMPLE.TEST:8006",
        "https://pve.example.test:8006/", "https://pve.example.test:8006/some/path",
        "[2001:db8::10]", "2001:db8::10", "[2001:db8::10]:8006", "https://[2001:db8::10]:8006",
        "fe80::1%eth0", "fe80::1%12", "[fe80::1%25eth0]:8006", "::1", "[::ffff:192.0.2.10]:8006",
        "b\u00fccher.example.test", "pve.example.test.", "HTTPS://pve.example.test:8006", "http://pve.example.test:8006",
        "https://root@pve.example.test:8006", "pve.example.test:0", "pve.example.test:65536", "pve.example.test:abc",
        "  pve.example.test:8006  ", "pve.example.ts.net", "100.64.0.7",
        "pve_1.example.test", "192.0.2.10:", "https://pve.example.test:443", "https://pve.example.test", "192.0.002.010",
        "https://[2001:DB8::10]:8006", "https://[2001:db8:0:0:0:0:0:10]:8006",
    )

    @Test fun printMatrix() {
        println("MATRIX|input|connectForm completeServerAddress|connectForm serverOrigin|profileEditor origin (no completion)|certificateOrigin (trust key)|sessionIdentity|routeTarget host/port -> hostPort|InetAddress for a literal host|PveWebUi.datacenter|Redact.url(origin/..?ticket=t)|Redact.text(\"connect to <hostPort> failed\")")
        for (typed in inputs) {
            val completed = completeServerAddress(typed)
            val origin = shown { serverOrigin(completed) }
            val profile = shown { profileFromDraft(ProfileDraft(null, "n", typed, "root@pam!t", "")).origin }
            val trust = shown { certificateOrigin(completed) }
            val session = shown { sessionIdentity(DIRECT_PVE_KIND, Connection(completed, "root@pam!t", "x", "").address, "root@pam!t") }
            val target = routeTarget(completed)
            val route = target?.let { "${it.host} / ${it.port} -> ${it.hostPort}" } ?: "null"
            val literal = target?.let { t ->
                if (ipLiteral(t.host) != null || ':' in t.host) shown { InetAddress.getByName(t.host).let { "${it.javaClass.simpleName} ${it.hostAddress}" } } else "(name, not resolved)"
            } ?: "-"
            val web = target?.let { shown { PveWebUi.datacenter(it.origin) } } ?: "-"
            val redactedUrl = target?.let { shown { Redact.url(it.origin + "/api2/json/x?ticket=t") } } ?: "-"
            val redactedText = target?.let { shown { Redact.text("connect to ${it.hostPort} failed") } } ?: "-"
            println("MATRIX|${typed.replace("|", "/")}|$completed|$origin|$profile|$trust|$session|$route|$literal|$web|$redactedUrl|$redactedText")
        }
        assertTrue(true)
    }
}
