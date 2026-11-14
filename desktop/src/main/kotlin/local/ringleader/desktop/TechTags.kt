package local.ringleader.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared status tags. [TechTag] is the small cut-corner chip. [TechTypeTag] is the fixed-width
 * network type chip. [TechLogTag] is the task-log chip, which must ellipsize a long exit status.
 * [PveTag] is a user-defined Proxmox tag.
 */
@Composable fun TechTag(label: String, tint: Color, modifier: Modifier = Modifier, help: String? = null) {
    if (help.isNullOrBlank()) TechTagChip(label, tint, modifier) else HelpTip(help) { TechTagChip(label, tint, modifier) }
}

@Composable private fun TechTagChip(label: String, tint: Color, modifier: Modifier) {
    // Fixed line height: the default for 9sp overflowed the box and the label sat on the border.
    // The extra end padding keeps the last letter clear of the cut bottom-right corner.
    // At least 16 units, and taller with large text: a fixed height clipped labels at the chip bottom at 150 %.
    Box(modifier.heightIn(min = 16.dp).border(1.dp, tint.copy(alpha = .75f), Tech.Control).padding(start = 4.dp, end = 7.dp), contentAlignment = Alignment.Center) {
        Text(label.uppercase(), fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, color = tint, maxLines = 1, softWrap = false)
    }
}

@Composable fun HealthyTag(label: String, modifier: Modifier = Modifier, help: String? = null) = TechTag(label, Tech.Green, modifier, help)
@Composable fun AttentionTag(label: String, modifier: Modifier = Modifier, help: String? = null) = TechTag(label, Tech.Amber, modifier, help)
@Composable fun ProblemTag(label: String, modifier: Modifier = Modifier, help: String? = null) = TechTag(label, Tech.Red, modifier, help)
@Composable fun NeutralTag(label: String, modifier: Modifier = Modifier, help: String? = null) = TechTag(label, Tech.Muted, modifier, help)

/** Network interface type. Fixed 78dp so the column lines up; text starts clear of the cut corner. */
@Composable fun TechTypeTag(label: String, tint: Color, modifier: Modifier = Modifier) {
    Box(modifier.widthIn(min = 78.dp).heightIn(min = 16.dp).border(1.dp, tint.copy(alpha = .7f), Tech.Control).padding(start = 4.dp, end = 7.dp), contentAlignment = Alignment.CenterStart) {
        Text(label.uppercase(), fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, color = tint, maxLines = 1, softWrap = false)
    }
}

/** Task-log status. The label can include the server's exit text, so it ellipsizes instead of growing. */
@Composable fun TechLogTag(label: String, tint: Color, modifier: Modifier = Modifier) {
    Text(label, modifier.border(1.dp, tint.copy(alpha = .6f), Tech.Control).padding(horizontal = 8.dp, vertical = 3.dp),
        color = tint, fontFamily = Tech.Mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

internal fun compactTag(tag: String) = if (tag.length > 14) tag.take(13) + "…" else tag

/** A Proxmox guest tag. Not uppercased; a long name is shortened and the full name is the tooltip. */
@Composable fun PveTag(tag: String, modifier: Modifier = Modifier) {
    HelpTip(tag) {
        Box(modifier.height(18.dp).border(1.dp, Tech.Amber.copy(alpha = .35f), CutCornerShape(4.dp)).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
            Text(compactTag(tag), fontSize = 9.sp, lineHeight = 11.sp, color = Tech.Amber, fontFamily = Tech.Mono, maxLines = 1, softWrap = false)
        }
    }
}

/** Recorded node HA. Green is a normal HA state, amber is a transition or maintenance, red is a failure. */
internal fun haTagTint(state: String): Color = when (state.lowercase()) {
    "online", "started", "active" -> Tech.Green
    "error", "fence", "failed" -> Tech.Red
    "maintenance", "migrate", "relocate", "freeze" -> Tech.Amber
    else -> Tech.Muted
}

/** Recorded storage status. Only a status that means the pool cannot be used becomes a tag. */
internal fun storageStatusTint(status: String): Color = when (status.lowercase()) {
    "error", "failed", "offline", "unavailable" -> Tech.Red
    "disabled", "inactive" -> Tech.Amber
    else -> Tech.Muted
}

internal fun storageStatusInactive(status: String) =
    status.lowercase() in setOf("disabled", "inactive", "offline", "unavailable", "error", "failed")
