@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult

/**
 * What a layout audit can say about one text or one control in a drawn scene.
 *
 * [Clipped]: an ancestor cuts the element (a box that is too small, a fixed-size parent, the window edge) and no scroll
 * container above it lets the person reach the rest. [Ellipsis]: the text itself was cut short, with an ellipsis or a clip.
 * [Collapsed]: text that exists but has no room at all. These are facts about the picture; whether an ellipsis is acceptable
 * (the full text is in a tip, the row is not for that text) is for a person looking at it to decide.
 */
internal enum class AuditKind { Clipped, Ellipsis, Collapsed }

internal data class AuditHit(val kind: AuditKind, val label: String, val bounds: Rect, val visible: Rect, val control: Boolean) {
    override fun toString() = "$kind${if (control) " (control)" else ""} \"${label.replace('\n', ' ').take(60)}\" at ${bounds.left.toInt()},${bounds.top.toInt()} ${bounds.width.toInt()}x${bounds.height.toInt()}" +
        if (kind == AuditKind.Clipped) ", shows ${visible.width.toInt()}x${visible.height.toInt()}" else ""
}

/** Every text and every clickable control of every layer of [scene] (the page and each open dialog or menu). */
internal fun auditScene(scene: ImageComposeScene): List<AuditHit> {
    val hits = ArrayList<AuditHit>()
    fun visit(node: SemanticsNode, scrollable: Boolean) {
        val config = node.config
        val scrolls = scrollable || config.contains(SemanticsProperties.VerticalScrollAxisRange) || config.contains(SemanticsProperties.HorizontalScrollAxisRange)
        val text = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        val clickable = config.contains(SemanticsActions.OnClick)
        val full = node.boundsInRoot
        if (!text.isNullOrBlank() && config.contains(SemanticsActions.GetTextLayoutResult)) {
            val results = ArrayList<TextLayoutResult>()
            config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val layout = results.firstOrNull()
            // A node that was never placed (below the fold of a lazy list, or not on screen) reports empty bounds: nothing to see.
            if (full == Rect.Zero) { node.children.forEach { visit(it, scrolls) }; return }
            if (full.width < 6f || full.height < 6f) { if (!scrolls) hits += AuditHit(AuditKind.Collapsed, text, full, node.touchBoundsInRoot, false) }
            else {
                if (layout != null && (0 until layout.lineCount).any { layout.isLineEllipsized(it) }) hits += AuditHit(AuditKind.Ellipsis, text, full, node.touchBoundsInRoot, false)
                // The text's own box cuts it: a line wider than the box (no wrapping), or more lines than the box has height for.
                if (layout != null && ((0 until layout.lineCount).any { layout.getLineRight(it) > layout.size.width + 1f } ||
                        (layout.didOverflowHeight && layout.lineCount > 1 && !(0 until layout.lineCount).any { layout.isLineEllipsized(it) }))) hits += AuditHit(AuditKind.Clipped, text, full, node.touchBoundsInRoot, false)
                else if (!scrolls) clippedHit(node, text, full, false)?.let(hits::add)
            }
        } else if (clickable && !scrolls && full.width >= 6f && full.height >= 6f) {
            clippedHit(node, config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") ?: text.orEmpty().ifBlank { "control" }, full, true)?.let(hits::add)
        }
        node.children.forEach { visit(it, scrolls) }
    }
    scene.semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode, false) }
    return hits
}

private fun clippedHit(node: SemanticsNode, label: String, full: Rect, control: Boolean): AuditHit? {
    val seen = node.touchBoundsInRoot
    // A pixel or two of difference is rounding; more is an element that an ancestor cuts.
    val lostWidth = full.width - seen.width
    val lostHeight = full.height - seen.height
    return if (lostWidth > 2f || lostHeight > 2f || seen.width <= 0f || seen.height <= 0f) AuditHit(AuditKind.Clipped, label, full, seen, control) else null
}

/** The audit as one line per hit, for a probe report or an assertion message. */
internal fun List<AuditHit>.describe(): String = if (isEmpty()) "clean" else joinToString("\n") { "  $it" }
