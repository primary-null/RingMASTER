@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.*

// The plan before Start: every network the scan could sweep, each with where it came from, its real size, what a sweep would take and
// why it is off or cannot be swept. A tick is the person's choice for that network and is remembered. Nothing here starts a scan.

/** The short tag that names where a network came from. */
internal fun planSourceTag(source: PlanSource): String = when (source) {
    PlanSource.Typed -> "TYPED"; PlanSource.Attached -> "THIS COMPUTER"; PlanSource.Tailnet -> "TAILSCALE ROUTE"
    PlanSource.Saved -> "SAVED"; PlanSource.Session -> "OPEN"; PlanSource.Earlier -> "EARLIER RESULT"; PlanSource.Route -> "ROUTE"
}

/** "254 addresses", "1 address". */
internal fun addressCountText(count: Int): String = if (count == 1) "1 address" else "${"%,d".format(java.util.Locale.ROOT, count)} addresses"

/** The line above the plan: what Start would do. */
internal fun planSummary(plan: ScanPlanView, reading: Boolean, read: Boolean): String = when {
    reading && !read -> "Reading this computer's networks…"
    plan.typedProblem.isNotEmpty() -> "The typed network is not valid, so Start does nothing yet."
    !plan.hasWork && plan.rows.none { it.sweepable } -> NO_NETWORK_TO_SCAN
    !plan.hasWork -> "Nothing is ticked, so a scan has nothing to sweep. Tick a network below, or type one."
    else -> {
        val n = plan.swept.size
        "A scan sweeps ${addressCountText(plan.addressCount)} on $n ${if (n == 1) "network" else "networks"}, ${ScanEstimate.text(plan.estimateSeconds)}."
    }
}

/** What a row says about its size: the real network, what is swept of it and how long that takes. */
internal fun planRowSize(row: PlanNetwork): String = when {
    !row.sweepable -> ""
    row.coveredBy != null -> "Included in ${row.coveredBy}"
    else -> "${addressCountText(row.count)} · ${ScanEstimate.text(row.seconds)}"
}

@Composable internal fun ScanPlanPanel(session: ScanSession, modifier: Modifier = Modifier, editable: Boolean = true) {
    val plan = session.plan
    var showSkipped by remember { mutableStateOf(false) }
    TechPanel(modifier.fillMaxWidth()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Networks to scan")
            if (editable) {
                if (session.choices.any) HelpTip("Forget every tick and untick you made, so each network goes back to its default.") { MiniAction("Reset choices") { session.resetChoices() } }
                HelpTip("Read this computer's networks and routes again.") { MiniAction(if (session.reading) "Reading…" else "Refresh", enabled = !session.reading) { session.refresh(force = true) } }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(planSummary(plan, session.reading, session.cards != null), Modifier.semantics { }, fontSize = 12.sp, color = if (!plan.hasWork && session.cards != null) Tech.Amber else Tech.Muted)
        // The cards are known at once; what the system says about adapters and routes follows. Until then the list can still change.
        if (session.reading && session.cards != null) Text("Reading adapter details and routes… this list can still change.", fontSize = 11.sp, color = Tech.Muted, modifier = Modifier.padding(top = 4.dp))
        plan.problem?.let { Text(it, fontSize = 12.sp, color = Tech.Amber, modifier = Modifier.padding(top = 4.dp)) }
        val tickable = plan.rows.filter { it.sweepable }
        val skipped = plan.rows.filter { !it.sweepable }
        if (tickable.isNotEmpty()) Spacer(Modifier.height(8.dp))
        tickable.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(color = Tech.Edge.copy(alpha = .6f))
            PlanRow(row, editable && row.choosable) { session.setChoice(row.id, it) }
        }
        if (plan.routesLeftOut > 0) Text("${plan.routesLeftOut} more ${if (plan.routesLeftOut == 1) "route is" else "routes are"} not listed. Type a network to scan one of them.", fontSize = 11.sp, color = Tech.Muted, modifier = Modifier.padding(top = 6.dp))
        if (skipped.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            MiniAction("${if (showSkipped) "Hide" else "Show"} ${skipped.size} not scanned") { showSkipped = !showSkipped }
            if (showSkipped) skipped.forEach { row ->
                HorizontalDivider(color = Tech.Edge.copy(alpha = .6f), modifier = Modifier.padding(top = 4.dp))
                PlanRow(row, false) {}
            }
        }
    }
}

/** One network: a tick, what it is called and where it came from, its real size, what a sweep takes, and why when it is off. */
@Composable private fun PlanRow(row: PlanNetwork, editable: Boolean, onTick: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val muted = !row.sweepable
    val toggle = if (row.choosable) Modifier.toggleable(row.on, interactionSource = interaction, indication = null, enabled = editable, role = Role.Checkbox, onValueChange = onTick)
        .hoverable(interaction).then(if (editable) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier) else Modifier
    Row(Modifier.fillMaxWidth().then(toggle).padding(vertical = 6.dp, horizontal = 2.dp).semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (row.choosable) TickBox(row.on, editable, focused, hovered) else Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(if (row.wider) "Whole network" else row.label, Modifier.identity(), fontSize = 13.sp, color = if (muted) Tech.Muted else Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                PlanTag(planSourceTag(row.source))
                if (row.wider) PlanTag("OFF BY DEFAULT")
            }
            Text(if (row.sweep != null && row.sweep != row.real && !row.wider) "${row.sweep} · of ${row.real}" else row.real, Modifier.identity(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Tech.Muted)
            row.note?.let { Text(it.replaceFirstChar(Char::uppercase), Modifier.identity(), fontSize = 11.sp, color = Tech.Muted) }
            if (row.alsoFrom.isNotEmpty()) Text("Also: ${row.alsoFrom.joinToString(", ")}", Modifier.identity(), fontSize = 11.sp, color = Tech.Muted)
            row.reason?.let { Text(it, fontSize = 11.sp, color = if (row.sweepable && !row.on || row.limited) Tech.Amber else Tech.Muted) }
        }
        if (row.sweepable) Column(horizontalAlignment = Alignment.End) {
            if (row.coveredBy != null) Text(planRowSize(row), fontSize = 12.sp, color = Tech.Muted, maxLines = 1)
            else {
                Text(addressCountText(row.count), fontSize = 12.sp, color = if (row.on) Tech.Ink else Tech.Muted, maxLines = 1)
                Text(ScanEstimate.text(row.seconds), fontSize = 11.sp, color = Tech.Muted, maxLines = 1)
            }
        }
    }
}

@Composable private fun PlanTag(text: String) {
    // The tag's edge is in the text colour in high contrast, like the other chips on the page.
    val edge = if (Appearance.palette.highContrast) Tech.Ink else Tech.Edge
    Text(text, Modifier.border(1.dp, edge, Tech.Control).padding(horizontal = 6.dp, vertical = 1.dp), fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = .6.sp, fontWeight = FontWeight.Bold, color = Tech.Muted, maxLines = 1)
}

/** The tick box of the plan: the same cut-corner square as [TechCheck], without a label, so the whole row can be the label. */
@Composable private fun TickBox(checked: Boolean, enabled: Boolean, focused: Boolean, hovered: Boolean) {
    val motion = LocalMotion.current
    val fill by animateColorAsState(if (checked && enabled) Tech.Amber else Color.Transparent, motion.fade(MotionDur.Quick))
    val edge = when { !enabled -> Tech.Edge.copy(alpha = .5f); checked || hovered -> Tech.Amber; else -> if (Appearance.palette.highContrast) Tech.Ink else Tech.Edge }
    val shape = CutCornerShape(bottomEnd = 4.dp)
    Box(Modifier.padding(top = 1.dp).size(16.dp).focusRing(focused, shape, gap = 3.dp).clip(shape).background(fill).border(1.dp, edge, shape)) {
        if (checked) Canvas(Modifier.fillMaxSize().padding(3.dp)) {
            val tick = Path().apply { moveTo(size.width * .08f, size.height * .52f); lineTo(size.width * .4f, size.height * .82f); lineTo(size.width * .94f, size.height * .18f) }
            drawPath(tick, if (enabled) Tech.OnAccent else Tech.Muted, style = Stroke(2.dp.toPx(), cap = StrokeCap.Square))
        }
    }
}
