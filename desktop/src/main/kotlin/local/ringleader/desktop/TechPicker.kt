package local.ringleader.desktop

import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import local.ringleader.core.ReadSection

/** Searchable, keyboard-operated picker. Identity values are concealed in Showcase mode. */
@Composable fun <T> TechSelect(label: String, options: List<T>, selected: T?, onSelect: (T) -> Unit,
    render: (T) -> String = { it.toString() }, enabled: Boolean = true, error: String? = null,
    hint: String? = null, loading: Boolean = false, emptyText: String = "No choices available.") {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var cursor by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    fun show() {
        query = ""
        cursor = options.indexOf(selected).coerceAtLeast(0)
        open = true
    }
    LaunchedEffect(enabled, loading) { if (!enabled || loading) open = false }
    val rows = options.filter { render(it).contains(query, ignoreCase = true) }
    LaunchedEffect(open, cursor, rows.size) {
        cursor = cursor.coerceIn(0, rows.lastIndex.coerceAtLeast(0))
        if (open && rows.isNotEmpty()) list.scrollToItem(cursor)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 10.sp)
        Box(Modifier.fillMaxWidth().border(1.dp, if (error != null) Tech.Red else Tech.Edge, Tech.Control)
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key in listOf(Key.Enter, Key.Spacebar, Key.DirectionDown)) {
                    if (enabled && !loading) show()
                    true
                } else false
            }.clickable(enabled = enabled && !loading) { show() }.padding(9.dp)) {
            Text(if (loading) "Reading choices\u2026" else selected?.let(render) ?: "Choose\u2026", Modifier.identity(),
                color = if (enabled) Tech.Ink else Tech.Muted, fontSize = 12.sp, fontFamily = Tech.Mono)
            if (open && enabled && !loading) Popup(onDismissRequest = { open = false }, properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
                RegisterModal(ModalKind.Dialog) { open = false }
                Column(Modifier.widthIn(min = 260.dp, max = 480.dp).background(ringColors.surface)
                    .border(1.dp, Tech.Amber).padding(10.dp).onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) false else when(e.key) {
                            Key.Escape -> { open = false; true }
                            Key.DirectionDown -> { cursor = (cursor + 1).coerceAtMost(rows.lastIndex.coerceAtLeast(0)); true }
                            Key.DirectionUp -> { cursor = (cursor - 1).coerceAtLeast(0); true }
                            Key.Enter -> { if (enabled && !loading) rows.getOrNull(cursor)?.takeIf { it in options }?.let(onSelect); open = false; true }
                            else -> false
                        }
                    }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val filterFocus = remember { androidx.compose.ui.focus.FocusRequester() }
                    LaunchedEffect(Unit) { filterFocus.requestFocus() }
                    TechField(query, { query = it; cursor = 0 }, "Filter", Modifier.focusRequester(filterFocus).identity())
                    LazyColumn(Modifier.heightIn(max = 260.dp), state = list) {
                        if (rows.isEmpty()) item { Text(emptyText, Modifier.identity(), color = Tech.Muted, fontSize = 12.sp) }
                        itemsIndexed(rows) { i, row ->
                            Text(render(row), Modifier.fillMaxWidth().background(if (i == cursor) Tech.Amber.copy(alpha = .12f) else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable(enabled = enabled && !loading) { if (row in options) onSelect(row); open = false }.padding(9.dp).identity(), color = Tech.Ink, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        error?.let { Text(it, Modifier.identity(), color = Tech.Red, fontSize = 11.sp) }
        hint?.let { HelpTip(it) { Text("?", color = Tech.Amber, fontSize = 12.sp) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun <T> TechChoice(options: List<T>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { choice -> TechAction(choice.toString(), active = choice == selected, onClick = { onSelect(choice) }) }
    }
}

/** Keeps decimal input intact; units never share the editable buffer. */
@Composable fun TechNumberField(value: String, onValue: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, unit: String? = null, error: String? = null, hint: String? = null,
    enabled: Boolean = true, step: Int = 1) {
    Column(Modifier.fillMaxWidth()) {
    TechField(value, { typed -> if (typed.all { it.isDigit() || it == '.' }) onValue(typed) },
        if (unit == null) label else "$label ($unit)", modifier.then(if (error != null) Modifier.identity() else Modifier).onPreviewKeyEvent { e ->
            if (enabled && e.type == KeyEventType.KeyDown && e.key in listOf(Key.DirectionUp, Key.DirectionDown)) {
                onValue(((value.toLongOrNull() ?: 0) + if (e.key == Key.DirectionUp) step else -step).coerceAtLeast(0).toString()); true
            } else false
        }, error = error, enabled = enabled)
    hint?.let { HelpTip(it) { Text("?", color = Tech.Amber) } }
    }
}

@Composable fun <T> ListStateView(state: ReadSection<List<T>>, loadingText: String, emptyText: String,
    onRetry: () -> Unit, content: @Composable (List<T>) -> Unit) {
    when {
        state.loading -> { TechProgress(); Text(loadingText, Modifier.identity(), color = Tech.Muted) }
        state.error != null -> { Text(state.error.orEmpty(), Modifier.identity(), color = Tech.Red); MiniAction("Retry", onClick = onRetry) }
        state.value.isNullOrEmpty() -> Text(emptyText, Modifier.identity(), color = Tech.Muted)
        else -> content(state.value.orEmpty())
    }
}
