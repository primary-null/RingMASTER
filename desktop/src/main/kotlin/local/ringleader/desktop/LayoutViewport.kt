package local.ringleader.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Keeps a useful working surface in short windows, with a visible route to its lower controls. */
@Composable internal fun LayoutViewport(minHeight: Dp, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        val viewportHeight = maxHeight
        val short = viewportHeight < minHeight
        Box(Modifier.fillMaxSize().then(if (short) Modifier.padding(end = 12.dp).verticalScroll(scroll) else Modifier)) {
            Box(Modifier.fillMaxWidth().height(if (short) minHeight else viewportHeight), content = content)
        }
        if (short) VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight(), style = LocalScrollbarStyle.current.copy(unhoverColor = Tech.Muted.copy(alpha=.7f), hoverColor = Tech.Amber))
    }
}
