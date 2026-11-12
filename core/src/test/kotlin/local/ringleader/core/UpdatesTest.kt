package local.ringleader.core

import java.nio.file.*
import java.security.*
import java.time.*
import java.util.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesTest {
    private fun pair() = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private fun publicKey(k: KeyPair) = Base64.getEncoder().encodeToString(k.public.encoded)
    private fun signature(bytes: ByteArray, k: KeyPair) = Base64.getEncoder().encode(Signature.getInstance("Ed25519").run { initSign(k.private); update(bytes); sign() })
    private val json = """{"schema":1,"channel":"stable","version":"0.22.0","published":"2026-11-10T00:00:00Z","notes":"Plain notes <b>remain text</b>","assets":[{"platform":"windows-x64-msi","url":"https://example.com/a.msi","sha256":"${"a".repeat(64)}","size":1048576}]}"""
    private fun prefs() = UpdatePreferences(Files.createTempDirectory("updates").resolve("updates.properties"))
    @Test fun signaturesGoodBadMissingWrongAndRotated() {
        val key=pair(); val other=pair(); val bytes=json.toByteArray(); val sig=signature(bytes,key)
        assertTrue(verifyUpdateSignature(bytes,sig,listOf(publicKey(key))))
        assertFalse(verifyUpdateSignature(bytes+32,sig,listOf(publicKey(key))))
        assertFalse(verifyUpdateSignature(bytes,byteArrayOf(),listOf(publicKey(key))))
        assertFalse(verifyUpdateSignature(bytes,sig,listOf(publicKey(other))))
        assertTrue(verifyUpdateSignature(bytes,sig,listOf(publicKey(other),publicKey(key))))
        assertFalse(verifyUpdateSignature(bytes,sig,emptyList()))
    }
    @Test fun releaseNotesKeepTheirLineBreaks() {
        val notes = parseUpdateManifest(json.replace("Plain notes", "First line\\r\\nSecond line\\nThird").toByteArray()).notes
        assertTrue(notes.startsWith("First line\nSecond line\nThird"), notes)
    }

    @Test fun manifestRejectRules() {
        assertEquals("0.22.0",parseUpdateManifest(json.toByteArray()).version)
        val bad = listOf(
            json.replace("\"schema\":1","\"schema\":2"), json.replace("\"schema\":1","\"schema\":\"1\""),
            json.replace("stable","beta"), json.replace("https://example.com", "http://example.com"),
            json.replace("https://example.com", "https://user:pass@example.com"), json.replace("https://example.com", "https:///nohost"),
            json.replace("a".repeat(64),"a".repeat(63)), json.replace("a".repeat(64),"z".repeat(64)),
            json.replace("1048576","1048575"), json.replace("1048576","1073741825"),
            json.replace("windows-x64-msi","Bad Id!"), json.replace("windows-x64-msi","a..b"), json.replace("windows-x64-msi","a-."),
            json.replace("Plain notes", "bad\\tnotes"), json.replace("Plain notes", "bad\\u0007notes"), json.replace("stable", "stable\\n"),
            json.replace("Plain notes", "x".repeat(8001)), json.replace("0.22.0","v0.22.0"),
            json.replace("2026-11-10T00:00:00Z","yesterday"),
            json.replace("\"assets\"", "\"notesUrl\":\"http://example.com\",\"assets\""),
            json.replace("\"assets\"", "\"notesUrl\":\"https://u@example.com\",\"assets\""),
            json.replace("\"assets\"", "\"minVersion\":\"invalid\",\"assets\""),
            json.replace("\"assets\"", "\"extra\\u0000\":\"value\",\"assets\""),
            json.replace("\"assets\"", "\"extra\":\"value\\u007f\",\"assets\""))
        bad.forEachIndexed { i,s -> assertFails("case $i") { parseUpdateManifest(s.toByteArray()) } }
        assertFails { parseUpdateManifest(ByteArray(MANIFEST_LIMIT+1) { 32 }) }
        fun withAsset(platform: String, sha: String = "b".repeat(64), url: String = "https://example.com/b.bin", size: Long = 1_048_576L) =
            json.replace("]}", """,{"platform":"$platform","url":"$url","sha256":"$sha","size":$size}]}""")
        val skipped = parseUpdateManifest(withAsset("future-99-x86_64-rpm").toByteArray())
        assertEquals(listOf("windows-x64-msi"), skipped.assets.map { it.platform })
        assertEquals(listOf("windows-x64-msi"), parseUpdateManifest(withAsset("x86_64-flatpak").toByteArray()).assets.map { it.platform })
        assertFails { parseUpdateManifest(withAsset("a".repeat(65)).toByteArray()) }
        assertEquals(2, parseUpdateManifest(withAsset("fedora-45-x86_64-rpm").toByteArray()).assets.size)
        assertFails { parseUpdateManifest(withAsset("future-99-x86_64-rpm", "b".repeat(63)).toByteArray()) }
        assertFails { parseUpdateManifest(withAsset("windows-x64-msi").toByteArray()) }
        val unknown = withAsset("future-99-x86_64-rpm")
        assertFails { parseUpdateManifest(unknown.replace("]}", """,{"platform":"future-99-x86_64-rpm","url":"https://example.com/c.bin","sha256":"${"c".repeat(64)}","size":1048576}]}""").toByteArray()) }
        assertFails { parseUpdateManifest(withAsset("future-99-x86_64-rpm", url = "http://example.com/b.bin").toByteArray()) }
        assertFails { parseUpdateManifest(withAsset("future-99-x86_64-rpm", size = 1_048_575L).toByteArray()) }
        assertFails { parseUpdateManifest(json.replace("]}", """,{"platform":"future-99-x86_64-rpm","url":"https://example.com/b.bin","sha256":"${"b".repeat(64)}"}]}""").toByteArray()) }
        assertTrue(parseUpdateManifest(json.replace("windows-x64-msi","future-99-x86_64-rpm").toByteArray()).assets.isEmpty())
    }
    @Test fun versionsAndPlatforms() {
        assertEquals(0,compareUpdateVersions("1.2.3.99","1.2.3.0"))
        assertTrue(compareUpdateVersions("0.22.0","0.21.99") > 0)
        assertTrue(compareUpdateVersions("999999999999.0","2") > 0)
        assertEquals("windows-x64-msi",updatePlatform("Windows 11","", "amd64"))
        assertEquals("debian-13-amd64-deb",updatePlatform("Linux","ID=debian\nVERSION_ID=\"13\"", "amd64"))
        assertEquals("ubuntu-26.04-amd64-deb",updatePlatform("Linux","ID=ubuntu\nVERSION_ID=26.04", "x86_64"))
        assertEquals("fedora-45-x86_64-rpm",updatePlatform("Linux","ID=fedora\nVERSION_ID=45", "amd64"))
        assertEquals("arch-x86_64-pkg",updatePlatform("Linux","ID=arch\nBUILD_ID=rolling", "x86_64"))
        assertNull(updatePlatform("Linux","ID=ubuntu\nVERSION_ID=24.04", "amd64"))
        assertNull(updatePlatform("Linux","ID=fedora\nVERSION_ID=44", "amd64"))
        assertNull(updatePlatform("Linux","ID=manjaro\nID_LIKE=arch", "amd64"))
        assertNull(updatePlatform("Linux","ID=debian\nVERSION_ID=13", "aarch64"))
        assertNull(updatePlatform("Windows 11","", "aarch64"))
        assertNull(updatePlatform("Mac OS X","", "amd64"))
    }
    @Test fun consentDisabledAndEmptyKeysNeverRequest() = runTest {
        val k=publicKey(pair()); var requests=0
        val t=UpdateTransport { _,_,_ -> requests++; error("No request expected") }
        val p=prefs(); val c=UpdateChecker("0.21.6",p,listOf(k),t)
        assertIs<UpdateState.NeedsConsent>(c.state.value); c.check(); c.consent(false); c.check()
        p.put("consent","yes")
        UpdateChecker("0.21.6",p,emptyList(),t).check()
        val d=UpdateChecker("0.21.6",p,listOf(k),t,disabled=true); d.check(); assertIs<UpdateState.Disabled>(d.state.value)
        assertEquals(0,requests)
    }
    private suspend fun checked(payload: String = json, running: String="0.21.6", platform: String?="windows-x64-msi", skip: String?=null): UpdateState {
        val key=pair(); val bytes=payload.toByteArray(); val p=prefs(); p.put("consent","yes"); skip?.let { p.put("skippedVersion",it) }
        val c=UpdateChecker(running,p,listOf(publicKey(key)),UpdateTransport { u,_,agent ->
            assertEquals("RingMASTER/$running",agent); UpdateResponse(200, if(u.endsWith(".sig")) signature(bytes,key) else bytes)
        },platform={platform})
        c.check(); return c.state.value
    }
    @Test fun offersOnlyNewerAndHonoursMinVersionSkipAndPlatform() = runTest {
        assertIs<UpdateState.Available>(checked())
        assertIs<UpdateState.UpToDate>(checked(running="0.22.0"))
        val stale = assertIs<UpdateState.Failed>(checked(running="0.23.0"))
        assertEquals("The update feed is older than this version.", stale.reason)
        assertEquals(RELEASE_PAGE, stale.releasePage)
        val stale2 = assertIs<UpdateState.Failed>(checked(running="1.0.0"))
        assertEquals("The update feed is older than this version.", stale2.reason)
        assertEquals(RELEASE_PAGE, stale2.releasePage)
        assertIs<UpdateState.Skipped>(checked(skip="0.22.0"))
        assertEquals(RELEASE_PAGE,assertIs<UpdateState.Failed>(checked(platform=null)).releasePage)
        val noBuild = assertIs<UpdateState.Failed>(checked(json.replace("windows-x64-msi","future-99-x86_64-rpm")))
        assertEquals("No build for this system.", noBuild.reason)
        assertEquals(RELEASE_PAGE, noBuild.releasePage)
        val min=json.replace("\"assets\"", "\"minVersion\":\"0.21.9\",\"assets\"")
        assertTrue(assertIs<UpdateState.Failed>(checked(min)).reason.contains("in-between"))
    }
    @Test fun freshnessRejectsOlderManifestAndPreservesDate() = runTest {
        val key=pair()
        val clockTime=Instant.parse("2026-11-11T00:00:00Z")
        val clock=object: Clock() { override fun getZone()=ZoneOffset.UTC; override fun withZone(zone: ZoneId)=this; override fun instant()=clockTime }
        val newer=json.replace("0.22.0","0.23.0").replace("2026-11-10T00:00:00Z","2026-11-10T12:00:00Z")
        val older=json.replace("2026-11-10T00:00:00Z","2026-11-09T00:00:00Z")
        val p=prefs(); p.put("consent","yes")
        var payload=newer.toByteArray()
        val t=UpdateTransport { u,_,_ -> UpdateResponse(200, if(u.endsWith(".sig")) signature(payload,key) else payload) }
        val c=UpdateChecker("0.21.6",p,listOf(publicKey(key)),t,clock,platform={"windows-x64-msi"})
        c.check()
        assertIs<UpdateState.Available>(c.state.value)
        val saved=p.feedPublished; assertNotNull(saved)
        payload=older.toByteArray()
        c.check()
        assertIs<UpdateState.Failed>(c.state.value)
        assertEquals(saved,p.feedPublished)
        payload=newer.toByteArray()
        c.check()
        assertIs<UpdateState.Available>(c.state.value)
    }
    @Test fun futureDatedManifestSavesClockTime() = runTest {
        val key=pair()
        val clockTime=Instant.parse("2026-11-10T00:00:00Z")
        val clock=object: Clock() { override fun getZone()=ZoneOffset.UTC; override fun withZone(zone: ZoneId)=this; override fun instant()=clockTime }
        val future=json.replace("0.22.0","0.23.0").replace("2026-11-10T00:00:00Z","2027-01-01T00:00:00Z")
        val bytes=future.toByteArray()
        val p=prefs(); p.put("consent","yes")
        val c=UpdateChecker("0.21.6",p,listOf(publicKey(key)),UpdateTransport { u,_,_ ->
            UpdateResponse(200, if(u.endsWith(".sig")) signature(bytes,key) else bytes)
        },clock,platform={"windows-x64-msi"})
        c.check()
        assertIs<UpdateState.Available>(c.state.value)
        assertEquals(clockTime,p.feedPublished)
    }
    @Test fun notPublishedOversizeRedirectAndUnverified() = runTest {
        val key=pair(); val p=prefs(); p.put("consent","yes")
        suspend fun run(t: UpdateTransport): UpdateState { val c=UpdateChecker("0.21.6",p,listOf(publicKey(key)),t); c.check(); return c.state.value }
        val missing=assertIs<UpdateState.Failed>(run(UpdateTransport { _,_,_ -> UpdateResponse(404) }))
        assertEquals("The update feed was not found. Its address may have changed.",missing.reason); assertNull(missing.releasePage)
        assertIs<UpdateState.Failed>(run(UpdateTransport { _,limit,_ -> UpdateResponse(200,ByteArray(limit+1)) }))
        assertIs<UpdateState.Failed>(run(UpdateTransport { u,limit,_ -> UpdateResponse(200, if(u.endsWith(".sig")) ByteArray(limit+1) else json.toByteArray()) }))
        var redirects=0
        val movedAway=assertIs<UpdateState.Failed>(run(UpdateTransport { u,_,_ -> redirects++; if (u == UPDATE_FEED) UpdateResponse(302,location="https://example.com/feed") else UpdateResponse(404) }))
        assertEquals("The update feed was not found. Its address may have changed.",movedAway.reason); assertNull(movedAway.releasePage)
        assertEquals(2,redirects)
        var count=0
        assertIs<UpdateState.Failed>(run(UpdateTransport { _,_,_ -> count++; UpdateResponse(302,location="http://example.com") })); assertEquals(1,count)
        assertIs<UpdateState.Failed>(run(UpdateTransport { u,_,_ -> if(u.endsWith(".sig")) UpdateResponse(404) else UpdateResponse(200,json.toByteArray()) }))
        assertIs<UpdateState.Failed>(run(UpdateTransport { u,_,_ -> UpdateResponse(200,if(u.endsWith(".sig")) signature(json.toByteArray(),pair()) else json.toByteArray()) }))
    }
    @Test fun newerPreferencesRemainUntouchedForSession() {
        val p=Files.createTempDirectory("updates-new").resolve("updates.properties"); val text="schema=2\nconsent=yes\n"
        Files.writeString(p,text); var messages=0
        val prefs=UpdatePreferences(p) { messages++ }; assertNull(prefs.consent); prefs.put("consent","yes")
        assertEquals(text,Files.readString(p)); assertEquals(1,messages)
        Files.delete(p); prefs.put("automatic","false"); assertFalse(Files.exists(p))
    }
    @Test fun scheduleUsesThirtySecondsAndTwentyFourHours() = runTest {
        val start=Instant.parse("2026-11-10T00:00:00Z")
        val clock=object: Clock() { override fun getZone()=ZoneOffset.UTC; override fun withZone(zone: ZoneId)=this; override fun instant()=start.plusMillis(testScheduler.currentTime) }
        var calls=0; val p=prefs(); p.put("consent","yes")
        val c=UpdateChecker("0.21.6",p,listOf(publicKey(pair())),UpdateTransport { _,_,_ -> calls++; UpdateResponse(404) },clock,ioDispatcher=StandardTestDispatcher(testScheduler))
        val job=c.schedule(this)
        advanceTimeBy(29999); runCurrent(); assertEquals(0,calls)
        advanceTimeBy(1); runCurrent()
        // Both clock and dispatcher follow the virtual scheduler.
        runCurrent()
        assertEquals(1,calls)
        advanceTimeBy(24*60*60*1000L-1); runCurrent(); assertEquals(1,calls)
        advanceTimeBy(60001); runCurrent()
        runCurrent()
        assertEquals(2,calls); job.cancel()
    }
    @Test fun releaseToolRoundTripAndOverwriteRefusal() {
        val root=Files.createTempDirectory("release-tool")
        val key=root.resolve("private.pem"); val manifest=root.resolve("ringmaster-update.json")
        Files.writeString(manifest,json)
        val original=System.out; val capture=java.io.ByteArrayOutputStream()
        try { System.setOut(java.io.PrintStream(capture)); local.ringleader.core.tools.main(arrayOf("generate",key.toString())) }
        finally { System.setOut(original) }
        val public=capture.toString().trim()
        assertFalse(public.contains("PRIVATE")); assertEquals(1,public.lines().size)
        assertFailsWith<FileAlreadyExistsException> { local.ringleader.core.tools.main(arrayOf("generate",key.toString())) }
        val signOut=java.io.ByteArrayOutputStream()
        try { System.setOut(java.io.PrintStream(signOut)); local.ringleader.core.tools.main(arrayOf("sign",key.toString(),manifest.toString())) }
        finally { System.setOut(original) }
        assertFalse(signOut.toString().contains("PRIVATE"))
        assertTrue(signOut.toString().isBlank())
        val verifyOut=java.io.ByteArrayOutputStream()
        try { System.setOut(java.io.PrintStream(verifyOut)); local.ringleader.core.tools.main(arrayOf("verify",public,manifest.toString())) }
        finally { System.setOut(original) }
        val printed=verifyOut.toString()
        assertTrue(printed.contains("version 0.22.0"), printed)
        assertTrue(printed.contains("published 2026-11-10T00:00:00Z"), printed)
        assertTrue(printed.contains("platforms windows-x64-msi"), printed)
        assertFalse(printed.contains("PRIVATE"))
        val bad=root.resolve("bad.json"); Files.writeString(bad,"""{"schema":2}""")
        local.ringleader.core.tools.main(arrayOf("sign",key.toString(),bad.toString()))
        assertFails { local.ringleader.core.tools.main(arrayOf("verify",public,bad.toString())) }
        Files.writeString(manifest,json+" ")
        assertFails { local.ringleader.core.tools.main(arrayOf("verify",public,manifest.toString())) }
    }
    @Test fun networkFailureRecordsLastCheckedAndWaitsADay() = runTest {
        val start=Instant.parse("2026-11-10T00:00:00Z")
        val clock=object: Clock() { override fun getZone()=ZoneOffset.UTC; override fun withZone(zone: ZoneId)=this; override fun instant()=start.plusMillis(testScheduler.currentTime) }
        var calls=0; val p=prefs(); p.put("consent","yes")
        val c=UpdateChecker("0.21.6",p,listOf(publicKey(pair())),UpdateTransport { _,_,_ -> calls++; throw java.io.IOException("down") },clock,ioDispatcher=StandardTestDispatcher(testScheduler))
        val job=c.schedule(this)
        advanceTimeBy(30_000); runCurrent(); runCurrent()
        assertEquals(1,calls); assertNotNull(p.lastChecked)
        advanceTimeBy(24*60*60*1000L-1); runCurrent(); assertEquals(1,calls)
        advanceTimeBy(60_001); runCurrent(); runCurrent(); assertEquals(2,calls); job.cancel()
    }
    @Test fun checkGivesUpAfterNinetySeconds() = runTest {
        val start=Instant.parse("2026-11-10T00:00:00Z")
        val clock=Clock.fixed(start, ZoneOffset.UTC)
        val p=prefs(); p.put("consent","yes")
        // The fetch outlives the deadline, so the 404 body must not be reported. 90_000 is the production default.
        val c=UpdateChecker("0.21.6",p,listOf(publicKey(pair())),UpdateTransport { _,_,_ ->
            Thread.sleep(1_000); UpdateResponse(404)
        },clock,attemptTimeout=200,ioDispatcher=Dispatchers.IO)
        c.check()
        val failed=assertIs<UpdateState.Failed>(c.state.value)
        assertEquals("The update feed could not be reached. Check the network and try again.", failed.reason)
        assertEquals(start, p.lastChecked)
    }
    @Test fun httpStatusAndInsecureRedirectAreDistinct() = runTest {
        val key=pair(); val p=prefs(); p.put("consent","yes")
        suspend fun run(t: UpdateTransport): UpdateState { val c=UpdateChecker("0.21.6",p,listOf(publicKey(key)),t); c.check(); return c.state.value }
        val http=assertIs<UpdateState.Failed>(run(UpdateTransport { _,_,_ -> UpdateResponse(503) }))
        assertEquals("The update feed answered with HTTP 503.", http.reason)
        assertNotNull(p.lastChecked)
        val portal=assertIs<UpdateState.Failed>(run(UpdateTransport { _,_,_ -> UpdateResponse(302, location="http://example.com") }))
        assertEquals("The update feed redirected to an address that is not HTTPS. This can happen on a captive portal.", portal.reason)
        val bad=assertIs<UpdateState.Failed>(run(UpdateTransport { u,_,_ -> UpdateResponse(200, if (u.endsWith(".sig")) signature(json.toByteArray(), pair()) else json.toByteArray()) }))
        assertEquals("The update feed could not be verified.", bad.reason)
    }
    @Test fun corruptPreferencesAreLeftUntouched() {
        val dir=Files.createTempDirectory("updates-corrupt")
        for (text in listOf("", "# only a comment\n", "consent=\\uXXXX\n")) {
            val file=dir.resolve("case-${text.length}.properties")
            Files.writeString(file, text)
            val before=Files.readAllBytes(file)
            var messages=0
            val stored=UpdatePreferences(file) { messages++ }
            assertNull(stored.consent); assertNull(stored.feedPublished)
            stored.put("consent","yes"); stored.put("feedPublished","2026-11-11T00:00:00Z")
            assertTrue(Files.readAllBytes(file).contentEquals(before), text)
            assertTrue(messages >= 1)
        }
    }
    @Test fun preferencesRoundTripAfterForcedWrite() {
        val file=Files.createTempDirectory("updates-force").resolve("updates.properties")
        val stored=UpdatePreferences(file)
        stored.put("consent","yes"); stored.put("feedPublished","2026-11-11T00:00:00Z")
        val again=UpdatePreferences(file)
        assertEquals("yes", again.consent)
        assertEquals(Instant.parse("2026-11-11T00:00:00Z"), again.feedPublished)
    }
    @Test fun preferencesFileIsOwnerOnlyWhenPosix() {
        val file=Files.createTempDirectory("updates-mode").resolve("updates.properties")
        if (!file.fileSystem.supportedFileAttributeViews().contains("posix")) return
        UpdatePreferences(file).put("consent","yes")
        assertEquals(setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file))
    }

    @Test fun profileNewerVersionLoadsDefaultsAndBlocksSave() {
        val file=Files.createTempDirectory("profile-new").resolve("profiles.json")
        val text="""{"version":2,"profiles":[],"future":true}"""; Files.writeString(file,text)
        var count=0; val store=ProfileStore(file).apply { onNewerSchema={ count++ } }
        assertTrue(store.load().isEmpty()); assertTrue(store.load().isEmpty()); assertEquals(1,count)
        assertFailsWith<ProfileStoreException> { store.save(emptyList()) }; assertEquals(text,Files.readString(file))
    }
}
