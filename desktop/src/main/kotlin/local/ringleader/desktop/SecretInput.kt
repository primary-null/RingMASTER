package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.InputTransformation
import local.ringleader.core.CREATE_PASSWORD_MAX_CHARS
import local.ringleader.core.CREATE_SSH_KEYS_MAX_CHARS
import local.ringleader.core.CREATE_SSH_KEY_LINE_MAX_CHARS
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Toolkit
import java.awt.event.KeyEvent

/**
 * A secret editor backed by [TextFieldState]. Secret characters never cross a String callback. Single-line values use
 * Compose's secure text primitive; multi-line SSH keys remain readable unless Showcase mode conceals the whole editor.
 */
@Composable
fun SecretInput(
    state: TextFieldState,
    label: String,
    multiLine: Boolean = false,
    error: String? = null,
    onSubmit: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    var limitError by remember { mutableStateOf<String?>(null) }
    val limits = remember(multiLine) { secretInputTransformation(multiLine) { limitError = it } }
    var focused by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    val showcase = Appearance.showcase
    LaunchedEffect(showcase, focused) {
        if (showcase || !focused) revealed = false
    }
    val capsLock = focused && runCatching { Toolkit.getDefaultToolkit().getLockingKeyState(KeyEvent.VK_CAPS_LOCK) }.getOrDefault(false)
    val edge = when {
        error != null || limitError != null -> Tech.Red
        focused -> Tech.Amber
        else -> Tech.Edge
    }
    val requester = focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
    val submit = Modifier.onPreviewKeyEvent {
        if (!multiLine && it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
            onSubmit?.invoke()
            onSubmit != null
        } else false
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Text(
                label.uppercase(),
                Modifier.weight(1f),
                fontFamily = Tech.Mono,
                fontSize = 10.sp,
                letterSpacing = .8.sp,
                color = Tech.Muted,
                maxLines = 1,
            )
            if (!multiLine && !showcase) {
                val interaction = remember { MutableInteractionSource() }
                androidx.compose.material3.Text(
                    if (revealed) "HIDE" else "SHOW",
                    Modifier
                        .focusProperties { canFocus = false }
                        .clickable(interactionSource = interaction, indication = null) { revealed = !revealed }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Tech.Amber,
                    fontFamily = Tech.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                )
            }
        }
        val fieldModifier = Modifier
            .fillMaxWidth()
            .then(if (multiLine) Modifier.heightIn(min = 82.dp, max = 148.dp) else Modifier.height(32.dp))
            .border(1.dp, edge, Tech.Control)
            .background(Tech.Black.copy(alpha = if (Tech.Light) .12f else .22f), Tech.Control)
            .padding(horizontal = 8.dp, vertical = if (multiLine) 7.dp else 6.dp)
            .then(requester)
            .onFocusChanged { focused = it.isFocused }
            .then(submit)
            .semantics {
                contentDescription = label
                if (!multiLine) password()
            }
        val style = TextStyle(color = Tech.Ink, fontSize = 12.sp, fontFamily = Tech.Mono)
        if (multiLine && showcase) {
            Box(fieldModifier.focusable(false), contentAlignment = Alignment.CenterStart) {
                androidx.compose.material3.Text("SSH keys hidden in Showcase mode", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 12.sp)
            }
        } else if (multiLine) {
            BasicTextField(
                state = state,
                inputTransformation = limits,
                modifier = fieldModifier,
                textStyle = style,
                cursorBrush = SolidColor(Tech.Amber),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 7),
            )
        } else {
            BasicSecureTextField(
                state = state,
                inputTransformation = limits,
                modifier = fieldModifier,
                textStyle = style,
                cursorBrush = SolidColor(Tech.Amber),
                textObfuscationMode = if (revealed && !showcase) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
            )
        }
        when {
            limitError != null -> androidx.compose.material3.Text(limitError!!, color = Tech.Red, fontSize = 11.sp)
            error != null -> androidx.compose.material3.Text(error, color = Tech.Red, fontSize = 11.sp)
            capsLock -> androidx.compose.material3.Text("Caps Lock is on", color = Tech.Amber, fontSize = 11.sp)
        }
    }
}

/** Clears editor text and undo/redo history. This does not promise physical zeroization of framework internals. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun wipeSecretInput(state: TextFieldState) {
    state.edit { replace(0, length, "") }
    state.undoState.clearHistory()
}

/** Copies the current editor into the core's wipeable holder without calling `toString()`. */
internal fun captureSecretInput(state: TextFieldState): local.ringleader.core.SecretChars? =
    state.text.takeUnless { it.isEmpty() }?.let(local.ringleader.core.SecretChars::copyOf)

internal fun wipeCreateSecretInputs(vararg states: TextFieldState) = states.forEach(::wipeSecretInput)

/**
 * Transfers the wipeable holders to [send], then clears both UI buffers. A callback that throws before taking ownership
 * cannot leak them; a successful callback owns them and closes them after its one send attempt.
 */
internal fun handOffCreateSecrets(
    password: TextFieldState,
    sshKeys: TextFieldState,
    send: (local.ringleader.core.CreateSecrets) -> Unit,
) {
    var secrets: local.ringleader.core.CreateSecrets? = null
    var capturedPassword: local.ringleader.core.SecretChars? = null
    try {
        require(secretInputLimit(password.text, false) == null) { "The password exceeds the input limit." }
        require(secretInputLimit(sshKeys.text, true) == null) { "The SSH keys exceed the input limit." }
        capturedPassword = captureSecretInput(password)
        secrets = local.ringleader.core.CreateSecrets(capturedPassword, captureSecretInput(sshKeys))
        send(secrets)
    } catch (failure: Throwable) {
        secrets?.close() ?: capturedPassword?.close()
        throw failure
    } finally {
        wipeCreateSecretInputs(password, sshKeys)
    }
}


/** Non-saveable editor lifetime shared by cancel, handoff and composition disposal. */
internal class CreateSecretEditors : AutoCloseable {
    val password = TextFieldState()
    val confirmation = TextFieldState()
    val sshKeys = TextFieldState()
    override fun close() = wipeCreateSecretInputs(password, confirmation, sshKeys)
}

@Composable internal fun rememberCreateSecretEditors(): CreateSecretEditors {
    val editors = remember { CreateSecretEditors() }
    androidx.compose.runtime.DisposableEffect(editors) { onDispose { editors.close() } }
    return editors
}

/** Checks characters in place, before a user edit is committed or a holder is copied. */
internal fun secretInputLimit(text: CharSequence, multiLine: Boolean): String? {
    if (!multiLine) return if (text.length > CREATE_PASSWORD_MAX_CHARS) "Use at most $CREATE_PASSWORD_MAX_CHARS password characters. The edit was not applied." else null
    if (text.length > CREATE_SSH_KEYS_MAX_CHARS) return "SSH keys exceed the total character limit. The edit was not applied."
    var line = 0
    for (c in text) {
        line = if (c == '\n') 0 else line + 1
        if (line > CREATE_SSH_KEY_LINE_MAX_CHARS) return "An SSH key line exceeds the character limit. The edit was not applied."
    }
    return null
}

internal fun secretInputTransformation(multiLine: Boolean, onRejected: (String?) -> Unit): InputTransformation = InputTransformation {
    val problem = secretInputLimit(asCharSequence(), multiLine)
    if (problem != null) revertAllChanges()
    onRejected(problem)
}
