package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.yield
import local.ringleader.core.CreateParam
import local.ringleader.core.CreateResult
import local.ringleader.core.CreateSecrets
import local.ringleader.core.CreateValue
import local.ringleader.core.NewGuestKind
import local.ringleader.core.SecretChars
import local.ringleader.core.demoCreateSession
import org.jetbrains.skia.EncodedImageFormat
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreateShowcaseTest {
    @Test
    fun sendTransfersWipeableHoldersAndClearsEditors() {
        val password = secretState(charArrayOf('s', 'a', 'm', 'p', 'l', 'e'))
        val keys = secretState(charArrayOf('s', 's', 'h', '-', 'e', 'd', '2', '5', '5', '1', '9', ' ', 'A', 'A', 'A', '\n'))
        var handed: CreateSecrets? = null

        handOffCreateSecrets(password, keys) { secrets ->
            handed = secrets
            assertTrue(secrets.password!!.matches(CharArrayView(charArrayOf('s', 'a', 'm', 'p', 'l', 'e'))))
            assertEquals(1, secrets.state.keyCount)
            secrets.close() // The one-send owner closes after writing the request body.
        }

        assertTrue(password.text.isEmpty())
        assertTrue(keys.text.isEmpty())
        assertTrue(handed!!.password!!.closed)
        assertTrue(handed!!.sshKeys!!.closed)
    }

    @Test
    fun failedHandOffClosesHoldersAndClearsEditors() {
        val password = secretState(charArrayOf('f', 'i', 'v', 'e', '!'))
        val keys = secretState(charArrayOf('s', 's', 'h', '-', 'r', 's', 'a', ' ', 'A'))
        var handed: CreateSecrets? = null
        assertFailsWith<IOException> {
            handOffCreateSecrets(password, keys) { secrets -> handed = secrets; throw IOException("synthetic send failure") }
        }
        assertTrue(password.text.isEmpty())
        assertTrue(keys.text.isEmpty())
        assertTrue(handed!!.password!!.closed)
        assertTrue(handed!!.sshKeys!!.closed)
    }

    @Test
    fun cancelAndCloseWipeEveryEditorInPlace() {
        val password = secretState(charArrayOf('a'))
        val confirmation = secretState(charArrayOf('a'))
        val keys = secretState(charArrayOf('b'))
        wipeCreateSecretInputs(password, confirmation, keys)
        assertTrue(password.text.isEmpty())
        assertTrue(confirmation.text.isEmpty())
        assertTrue(keys.text.isEmpty())
    }

    @Test
    fun keyFileReaderAndAppenderAvoidStringState() {
        val file = Files.createTempFile("ringmaster-invented-public-key", ".pub")
        val fixture = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDocumentationOnly docs@example.test\n".encodeToByteArray()
        Files.write(file, fixture)
        fixture.fill(0)
        val chars = readSshKeyChars(file)
        val state = TextFieldState()
        try {
            appendSshKeyChars(state, chars)
            assertEquals(chars.size, state.text.length)
            chars.indices.forEach { assertEquals(chars[it], state.text[it]) }
        } finally {
            chars.fill('\u0000')
            wipeSecretInput(state)
            Files.deleteIfExists(file)
        }
        assertTrue(state.text.isEmpty())
    }

    @Test
    fun keyFileLimitIsFailClosed() {
        val file = Files.createTempFile("ringmaster-large-key", ".pub")
        Files.write(file, ByteArray(65) { 'A'.code.toByte() })
        try { assertFailsWith<IOException> { readSshKeyChars(file, maxBytes = 64) } }
        finally { Files.deleteIfExists(file) }
    }

    @Test
    fun secretsCannotBecomeCreateParametersAndStringViewsAreRedacted() {
        assertFailsWith<IllegalArgumentException> { CreateParam("password", CreateValue.Text("forbidden")) }
        val value = SecretChars.copyOf(CharArrayView(charArrayOf('x')))
        val secrets = CreateSecrets(password = value)
        try {
            assertEquals("SecretChars(redacted)", value.toString())
            assertEquals("CreateSecrets(redacted)", secrets.toString())
            assertTrue(secrets.state.password)
            assertEquals(0, secrets.state.keyCount)
        } finally { secrets.close() }
    }

    @Test
    fun showcaseAndSimulatedResultRenderWithoutSecretText() = runSceneTest {
        val output = System.getenv("RINGMASTER_CT_SCENES")?.takeIf(String::isNotBlank)?.let(::Path)
        output?.let(Files::createDirectories)
        Appearance.preview(light = false, showcase = true)
        val password = secretState(charArrayOf('N', 'o', 't', 'D', 'r', 'a', 'w', 'n', '9'))
        val keys = secretState(charArrayOf('s', 's', 'h', '-', 'e', 'd', '2', '5', '5', '1', '9', ' ', 'N', 'o', 't', 'D', 'r', 'a', 'w', 'n'))
        val secretScene = ImageComposeScene(1280, 800, Density(1.25f), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                Box(Modifier.fillMaxSize().background(Tech.Canvas).padding(24.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SecretInput(password, "Root password")
                        SecretInput(keys, "SSH public keys", multiLine = true)
                    }
                }
            } }
        }
        try {
            yield()
            output?.let { Files.write(it.resolve("ct-showcase-secrets.png"), secretScene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes) }
                ?: secretScene.render()
        } finally { secretScene.close(); wipeCreateSecretInputs(password, keys) }

        Appearance.preview(light = false)
        val resultSession = demoCreateSession(NewGuestKind.Container, "docs-node", 7L, Instant.parse("2026-11-12T12:00:00Z"))
            .copy(result = CreateResult.Simulated(NewGuestKind.Container, "docs-node", 140))
        val resultScene = ImageComposeScene(1280, 800, Density(1.25f), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                CreateCtWizard(
                    resultSession, ctSceneContext, ctSceneDraft,
                    onCreate = { _, secrets -> secrets.close() }, onCancel = {},
                    validateDraft = { _, _ -> emptyMap() }, validateSecrets = { _, _, _ -> emptyMap() },
                    paramsFor = { _, _, state -> ctSceneParams(state) }, freezeDraft = { _, _ -> local.ringleader.core.FreezeResult.Ready(ctSceneRequest) },
                    nextMount = { null },
                )
            } }
        }
        try {
            repeat(8) { resultScene.render((it + 1) * 100_000_000L).close(); yield() }
            output?.let { Files.write(it.resolve("ct-demo-simulated.png"), resultScene.render(900_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes) }
                ?: resultScene.render()
        } finally { resultScene.close(); Appearance.preview(light = false) }
        assertTrue(resultSession.sample)
        assertTrue(resultSession.result is CreateResult.Simulated)
    }
}

private fun secretState(chars: CharArray): TextFieldState = TextFieldState().also { state ->
    try { state.edit { chars.forEach(::append) } } finally { chars.fill('\u0000') }
}

/** A test CharSequence whose toString is unusable, proving capture reads it character by character. */
private class CharArrayView(private val chars: CharArray) : CharSequence {
    override val length: Int get() = chars.size
    override fun get(index: Int): Char = chars[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = CharArrayView(chars.copyOfRange(startIndex, endIndex))
    override fun toString(): String = error("Secret test data must not be stringified")
}

