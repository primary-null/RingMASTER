@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Storage-owned bounded frame. Header/footer remain reachable even below the supported window floor. */
@Composable internal fun StorageDialog(title:String,onDismiss:()->Unit,width:Dp=600.dp,
    initialFocus:FocusRequester?=null,context:@Composable ()->Unit={},actions:@Composable ()->Unit={},content:@Composable ColumnScope.()->Unit) {
    val density=LocalDensity.current
    val window=LocalWindowInfo.current.containerSize
    val height=with(density) {window.height.toDp()}.takeIf {it>0.dp} ?: 640.dp
    val availableWidth=with(density) {window.width.toDp()}.takeIf {it>0.dp} ?: 960.dp
    TechModal(onDismiss,Dismiss.Confirm,ModalKind.Dialog,initialFocus) {
        Surface(Modifier.width(minOf(width,(availableWidth-24.dp).coerceAtLeast(200.dp))).heightIn(max=minOf(760.dp,(height-24.dp).coerceAtLeast(180.dp))),
            color=ringColors.surface,shape=Tech.Plate,border=BorderStroke(1.dp,Tech.Edge)) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(title,color=Tech.Ink,fontSize=Tech.Title)
                context()
                val scroll=rememberScrollState()
                // Full width, so the scrollbar sits at the plate edge and the wheel works across the whole body even
                // when every body line is short.
                Box(Modifier.weight(1f,fill=false).fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(end=12.dp).verticalScroll(scroll),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
                    Box(Modifier.matchParentSize()) { VerticalScrollbar(rememberScrollbarAdapter(scroll),Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        style=LocalScrollbarStyle.current.copy(unhoverColor=Tech.Muted.copy(alpha=.7f),hoverColor=Tech.Amber)) }
                }
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {actions()}
            }
        }
    }
}
