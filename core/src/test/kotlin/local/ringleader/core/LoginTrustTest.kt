package local.ringleader.core

import java.nio.file.Files
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LoginTrustTest {
    private val origin = "https://pve.example.test:8006"
    private val a = "ab".repeat(32)
    private val b = "cd".repeat(32)
    private fun TestScope.fixture(store: CertificateTrustStore? = null): Triple<ProfileDesk, Workspace, java.nio.file.Path> {
        val root = Files.createTempDirectory("login-trust")
        val gateway = object : InventoryGateway {
            override suspend fun overview(connection: Connection) = Overview(0, emptyList())
            override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
        }
        val workspace = Workspace(this, gateway, pdmGateway = object : PdmReadGateway {
            override suspend fun read(connection: Connection): PdmFleetDocument = error("Offline fake PDM read")
        })
        val desk = ProfileDesk(workspace, ProfileStore(root.resolve("profiles.json")), certificateStore=store ?: CertificateTrustStore(root.resolve("certificates.json")))
        return Triple(desk, workspace, root)
    }
    @Test fun firstConsentConnectsOnceAndRepeatReusesPin() = runTest {
        val (desk, ws, root) = fixture()
        var calls = 0
        desk.router = { connection, _ -> assertEquals(a,connection!!.fingerprint); calls++ }
        try {
            desk.setManualSecret("secret")
            assertFalse(desk.connectManual(origin,"root@pam","",passwordLogin=true,observed=a))
            assertTrue(desk.hasManualSecret()); assertEquals(0,calls)
            val review = desk.manualTrust(origin,a)
            assertTrue(desk.connectManual(origin,"root@pam","",passwordLogin=true,observed=a,approval=review))
            assertEquals(1,calls); assertFalse(desk.hasManualSecret())
            assertFalse(desk.connectManual(origin,"root@pam","",passwordLogin=true,observed=a,approval=review))
            assertEquals(1,calls)
            assertEquals(a,CertificateTrustStore(root.resolve("certificates.json")).load().single().pin)
            desk.setManualSecret("next-secret")
            assertTrue(desk.connectManual(origin,"another@pam","",passwordLogin=true,observed=a))
            assertEquals(2,calls)
            assertEquals(a, desk.manualTrust(origin).known.single())
        } finally { desk.close(); ws.close() }
    }
    @Test fun mismatchBlocksOrdinaryAndStaleApprovalThenExplicitReplacementWorks() = runTest {
        val (desk,ws,root)=fixture()
        try {
            CertificateTrustStore(root.resolve("certificates.json")).save(listOf(ApprovedCertificate(origin,a)))
            desk.setManualSecret("secret")
            val old = desk.manualTrust(origin,a)
            val changed = desk.manualTrust(origin,b)
            assertTrue(changed.changed); assertTrue(changed.canApprove)
            assertFalse(desk.connectManual(origin,"root@pam","",observed=b))
            assertFalse(desk.connectManual(origin,"root@pam","",observed=b,approval=old))
            assertEquals(a,desk.manualTrust(origin).known.single())
            assertTrue(desk.connectManual(origin,"root@pam","",observed=b,approval=changed))
            assertEquals(b,desk.manualTrust(origin).known.single())
        } finally { desk.close();ws.close() }
    }
    @Test fun changedOriginCannotUseApprovalAndPortIsIsolated() = runTest {
        val (desk,ws,_)=fixture()
        try {
            desk.setManualSecret("secret")
            val review=desk.manualTrust(origin,a)
            assertFalse(desk.connectManual("https://pve.example.test:8443","root@pam","",observed=a,approval=review))
            assertTrue(desk.manualTrust("https://pve.example.test:8443").known.isEmpty())
            assertEquals("https://example.test", certificateOrigin("https://EXAMPLE.test:443/"))
            assertEquals(certificateOrigin("https://example.test"),certificateOrigin("https://EXAMPLE.test:443"))
            assertNotEquals(certificateOrigin(origin),certificateOrigin("https://pve.example.test:8443"))
        } finally { desk.close();ws.close() }
    }
    @Test fun savedPinReusedButNotOverwrittenAndConflictsBlock() = runTest {
        val (desk,ws,root)=fixture()
        try {
            fun save(pin:String) { desk.beginCreate();desk.updateDraft("Saved",origin,"root@pam",pin);assertTrue(desk.saveDraft(passwordLogin=true)) }
            save(a)
            assertEquals(a,desk.manualTrust(origin,a).known.single())
            assertFalse(desk.manualTrust(origin,b).canApprove)
            desk.setManualSecret("secret")
            assertFalse(desk.connectManual(origin,"root@pam",b))
            save(b)
            assertTrue(desk.manualTrust(origin,a).conflict)
            assertFalse(desk.connectManual(origin,"root@pam","",observed=a,approval=desk.manualTrust(origin,a)))
            assertFalse(Files.exists(root.resolve("certificates.json")))
            assertEquals(setOf(a,b),desk.state.value.profiles.map { it.sha256Pin }.toSet())
        } finally { desk.close();ws.close() }
    }
    @Test fun invalidActionNeverPersistsAndWriteFailureNeverConnects() = runTest {
        val file=Files.createTempDirectory("trust-fail").resolve("certificates.json")
        val store=CertificateTrustStore(file,replace={ _,_->error("Disk full. Free space and retry.") })
        val (desk,ws,_)=fixture(store)
        var calls=0; desk.router={_,_->calls++}
        try {
            desk.setManualSecret("secret")
            assertFalse(desk.connectManual(origin,"!bad","",observed=a,approval=desk.manualTrust(origin,a)))
            assertFalse(Files.exists(file))
            assertFalse(desk.connectManual(origin,"root@pam","",observed=a,approval=desk.manualTrust(origin,a)))
            assertEquals(0,calls);assertTrue(desk.hasManualSecret());assertNotNull(desk.state.value.formError)
            assertFalse(Files.exists(file))
        } finally { desk.close();ws.close() }
    }
    @Test fun approvalSurvivesRouterRefusalAndForgetKeepsSavedProfilePin() = runTest {
        val (desk,ws,_)=fixture()
        try {
            desk.router={_,_->error("Session limit reached")}
            desk.setManualSecret("secret")
            assertFalse(desk.connectManual(origin,"root@pam","",observed=a,approval=desk.manualTrust(origin,a)))
            assertEquals(a,desk.manualTrust(origin).known.single())
            assertTrue(desk.hasManualSecret())
            assertTrue(desk.forgetManualTrust(origin))
            assertTrue(desk.manualTrust(origin).known.isEmpty())
        } finally { desk.close();ws.close() }
    }
    @Test fun caAndPastedPinPathsWorkWithoutRememberingSecretsOrPins() = runTest {
        val (desk,ws,root)=fixture()
        try {
            desk.setManualSecret("secret")
            assertTrue(desk.connectManual(origin,"root@pam","",passwordLogin=true))
            desk.setManualSecret("token-secret")
            assertTrue(desk.connectManual("https://pdm.example.test:8443","user@pdm!token",a,kind=PDM_KIND,passwordLogin=false))
            assertFalse(Files.exists(root.resolve("certificates.json")))
            assertFalse(desk.hasManualSecret())
        } finally { desk.close();ws.close() }
    }
    @Test fun malformedFutureDuplicateOversizeAndInvalidUtf8AreReadOnly() {
        for (bytes in listOf("{}".toByteArray(),"{\"version\":2,\"certificates\":[]}".toByteArray(),
            "{\"version\":1,\"version\":1,\"certificates\":[]}".toByteArray(),ByteArray(65537){32},byteArrayOf(0xc3.toByte(),0x28))) {
            val file=Files.createTempDirectory("trust-invalid").resolve("certificates.json")
            Files.write(file,bytes)
            val store=CertificateTrustStore(file)
            assertFailsWith<IllegalStateException> {store.load()}
            assertFailsWith<IllegalStateException> {store.save(emptyList())}
            assertContentEquals(bytes,Files.readAllBytes(file))
        }
    }
    @Test fun futureProfilesCannotSilentlyRemoveSavedPinProtection() = runTest {
        val root=Files.createTempDirectory("future-profile-trust")
        Files.writeString(root.resolve("profiles.json"),"""{"version":999,"profiles":[]}""")
        val workspace=Workspace(this)
        val desk=ProfileDesk(workspace,ProfileStore(root.resolve("profiles.json")))
        try {
            assertNotNull(desk.manualTrust(origin,a).error)
            desk.setManualSecret("secret")
            assertFalse(desk.connectManual(origin,"root@pam",""))
        } finally {desk.close();workspace.close()}
    }
    @Test fun concurrentDiskChangeAndDeletionCannotBeOverwritten() {
        val file=Files.createTempDirectory("trust-concurrent").resolve("certificates.json")
        val first=CertificateTrustStore(file);first.save(listOf(ApprovedCertificate(origin,a)))
        val second=CertificateTrustStore(file);second.load()
        first.save(listOf(ApprovedCertificate(origin,b)))
        assertFailsWith<IllegalStateException> {second.save(emptyList())}
        second.load();Files.delete(file)
        assertFailsWith<IllegalStateException> {second.save(emptyList())}
    }
}
