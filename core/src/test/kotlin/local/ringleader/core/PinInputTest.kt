package local.ringleader.core

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Network audit F8, F4 and M3: common fingerprint pastes, forgetting an approval, and the same certificate under another name. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PinInputTest {
    private val pin = "0123456789abcdef".repeat(4)
    private val colons = pin.uppercase().chunked(2).joinToString(":")

    @Test fun theCommonPastesAreOneCanonicalPin() {
        for (paste in listOf(
            pin, pin.uppercase(), colons, " $colons ", "sha256 Fingerprint=$colons", "SHA256 Fingerprint=$colons", "SHA256 FINGERPRINT = $colons",
            "SHA256:$colons", "sha256:$pin", "SHA-256: $colons", pin.chunked(2).joinToString(" "), pin.chunked(32).joinToString("\n"),
            "sha256 Fingerprint=$colons\n",
        )) assertEquals(pin, canonicalOptionalPin(paste), paste)
        assertNull(canonicalOptionalPin("")); assertNull(canonicalOptionalPin("  \n"))
        // Still refused: a short value, other hashes, a label alone, letters that are not hex, a label in the middle.
        for (bad in listOf("abcd", "sha1 Fingerprint=" + "AB:".repeat(19) + "AB", "sha256 Fingerprint=", pin.dropLast(1) + "g", "x sha256:$pin", "md5:$pin"))
            assertFailsWith<IllegalArgumentException>(bad) { canonicalOptionalPin(bad) }
        // The same spelling reaches the connection, the saved profile and the trust file.
        assertEquals(pin, Connection("https://pve.example.test:8006", "root@pam!read", "s", "SHA256 Fingerprint=$colons").fingerprint)
        assertEquals(pin, profileFromDraft(ProfileDraft(null, "Lab", "https://pve.example.test:8006", "root@pam", "sha256:$colons")).sha256Pin)
    }

    private fun kotlinx.coroutines.test.TestScope.desk(): Triple<ProfileDesk, Workspace, java.nio.file.Path> {
        val root = Files.createTempDirectory("pin-input")
        val workspace = Workspace(this)
        return Triple(ProfileDesk(workspace, ProfileStore(root.resolve("profiles.json"))), workspace, root)
    }

    @Test fun aPastedOpensslLineConnectsWithThatPin() = runTest {
        val (desk, workspace, root) = desk()
        val routed = mutableListOf<Connection>()
        desk.router = { c, _ -> routed += c!!; error("offline") }
        try {
            desk.setManualSecret("secret")
            desk.connectManual("https://pve.example.test:8006", "root@pam!read", "sha256 Fingerprint=$colons")
            assertEquals(pin, routed.single().fingerprint)
        } finally { desk.close(); workspace.close(); root.toFile().deleteRecursively() }
    }

    @Test fun forgettingAnApprovalSaysWhatHappenedAndKeepsProfilePins() = runTest {
        val (desk, workspace, root) = desk()
        try {
            val origin = "https://pve.example.test:8006"
            CertificateTrustStore(root.resolve("certificates.json")).save(listOf(ApprovedCertificate(origin, pin), ApprovedCertificate("https://pve-b.example.test:8006", pin)))
            assertTrue(forgetCertificateQuestion(origin).startsWith("Forget the approved certificate for $origin? The next connection will ask you to check and approve it again."))
            assertTrue(desk.forgetManualTrust(origin))
            assertEquals(FORGOT_CERTIFICATE_NOTICE, desk.state.value.notice)
            assertEquals(listOf("https://pve-b.example.test:8006"), CertificateTrustStore(root.resolve("certificates.json")).load().map { it.origin }, "only this address")
        } finally { desk.close(); workspace.close(); root.toFile().deleteRecursively() }
    }

    @Test fun theSameCertificateUnderAnotherAddressIsSaidButStillNeedsTheExplicitApproval() = runTest {
        val (desk, workspace, root) = desk()
        val routed = mutableListOf<Connection>()
        desk.router = { c, _ -> routed += c!! }
        try {
            val byName = "https://pve.example.test:8006"
            CertificateTrustStore(root.resolve("certificates.json")).save(listOf(ApprovedCertificate(byName, pin)))
            desk.beginCreate(); desk.updateDraft("Tailnet", "https://pve.tail.example.test:8006", "root@pam", pin); assertTrue(desk.saveDraft(passwordLogin = true))
            val byAddress = "https://192.0.2.10:8006"
            val trust = desk.manualTrust(byAddress, pin)
            assertEquals(listOf(byName, "https://pve.tail.example.test:8006"), trust.sameAs)
            assertTrue(trust.needsApproval, "no automatic carry-over")
            desk.setManualSecret("secret")
            assertFalse(desk.connectManual(byAddress, "root@pam", "", passwordLogin = true, observed = pin))
            assertTrue(routed.isEmpty())
            assertTrue(desk.connectManual(byAddress, "root@pam", "", passwordLogin = true, observed = pin, approval = trust))
            assertTrue(desk.manualTrust(byName, pin).sameAs.contains(byAddress), "and the other way round once approved")
            assertTrue(desk.manualTrust(byName, "fe".repeat(32)).sameAs.isEmpty())
        } finally { desk.close(); workspace.close(); root.toFile().deleteRecursively() }
    }
}
