@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.automation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.CupertinoText
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class PlanPoint(val at: Long, val plans: List<DesktopUiNode.AutomationPlan>) {
    val key get() = "time:$at"
}
internal data class PlanPlacement(val point: PlanPoint, val left: Float, val lane: Int)

internal fun planPoints(plans: List<DesktopUiNode.AutomationPlan>, start: Long, end: Long): List<PlanPoint> =
    plans.flatMap { plan -> plan.nextRuns().distinct().filter { it in start..end }.map { it to plan } }
        .groupBy({ it.first }, { it.second }).toSortedMap()
        .map { (time, plans) -> PlanPoint(time, plans) }

internal fun placePlans(points: List<PlanPoint>, start: Long, end: Long, width: Float, cardWidth: Float): List<PlanPlacement> {
    val lanes = mutableListOf<Float>()
    return points.map { point ->
        val left = ((point.at - start).toDouble() / (end - start).coerceAtLeast(1)).toFloat() *
            (width - cardWidth - 12).coerceAtLeast(0f) + 4
        val lane = lanes.indexOfFirst { left >= it + 13 }.let { if (it < 0) lanes.size else it }
        if (lane == lanes.size) lanes.add(left + cardWidth) else lanes[lane] = left + cardWidth
        PlanPlacement(point, left, lane)
    }
}

internal fun timeLabel(at: Long): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))

@Composable
internal fun AutomationTimeline(
    points: List<PlanPoint>,
    start: Long,
    now: Long,
    width: androidx.compose.ui.unit.Dp,
    selected: String?,
    text: (DesktopUiNode.TextToken) -> String,
    label: (String, List<String>) -> String,
    onHover: (PlanPoint, Boolean) -> Unit,
    onClick: (PlanPoint) -> Unit,
    onAnchor: (String, Rect, FocusRequester) -> Unit,
    minHeight: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val palette = LocalExperiencePalette.current
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val cardWidth = 170f * scale
    val placements = remember(points, start, width, scale) {
        placePlans(points, start, start + 86_400_000L, width.value, cardWidth)
    }
    val lanes = (placements.maxOfOrNull { it.lane } ?: 0) + 1
    val laneHeight = 100f * scale
    val height = maxOf(94f + lanes * laneHeight + 12f, minHeight.value)
    val axisWidth = (width.value - cardWidth - 12).coerceAtLeast(0f)
    fun x(at: Long) = 13f + ((at - start).toDouble() / 86_400_000).toFloat() * axisWidth
    val zone = ZoneId.systemDefault()
    val first = Instant.ofEpochMilli(start).atZone(zone)
    val midnight = first.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    Box(Modifier.width(width).height(height.dp).testTag("automation.axis")) {
        if (midnight < start + 86_400_000) {
            Box(Modifier.offset(x = x(midnight).dp).width((width.value - x(midnight)).dp)
                .height((height - 23).dp).clip(RoundedCornerShape(10.dp))
                .background(palette.secondarySurface.copy(alpha = .4f)))
        }
        CupertinoText(first.toLocalDate().toString(), Modifier.offset(x = 4.dp), fontSize = 11.sp, color = palette.secondaryText)
        if (x(midnight) < width.value - 90) CupertinoText(
            first.toLocalDate().plusDays(1).toString(),
            Modifier.offset(x = (x(midnight) + 8).dp), fontSize = 11.sp, color = palette.secondaryText,
        )
        var tick = first.withMinute(0).withSecond(0).withNano(0).plusHours(1)
        while (tick.toInstant().toEpochMilli() < start + 86_400_000) {
            val at = tick.toInstant().toEpochMilli()
            val left = x(at)
            if (tick.hour % 2 == 0 && left > 80f * scale) {
                CupertinoText(timeLabel(at), Modifier.offset(x = (left - 18).dp, y = 39.dp),
                    color = palette.secondaryText, fontSize = 11.sp)
                Box(Modifier.offset(x = left.dp, y = 65.dp).width(1.dp).height((height - 88).dp)
                    .background(palette.separator.copy(alpha = .22f)))
            }
            tick = tick.plusHours(1)
        }
        Box(Modifier.offset(x = 13.dp, y = 68.dp).width(axisWidth.dp).height(1.dp).background(palette.separator.copy(alpha = .7f)))
        val currentX = x(now.coerceIn(start, start + 86_400_000))
        CupertinoText(label("now", listOf(timeLabel(now))), Modifier.offset(x = (currentX - 9).dp, y = 36.dp)
            .background(palette.surface).padding(end = 4.dp), fontSize = 11.sp, color = palette.link)
        Box(Modifier.offset(x = currentX.dp, y = 68.dp).width(1.dp).height((height - 91).dp)
            .background(palette.link.copy(alpha = .4f)))
        placements.forEach { placement ->
            val point = placement.point
            val active = selected == point.key
            val top = 94 + laneHeight * placement.lane
            val color = if (active || point.plans.any { it.status() == "RUNNING" }) palette.link else palette.separator
            Box(Modifier.offset(x = (placement.left + 9).dp, y = 68.dp).width(1.dp)
                .height((top - 68).dp).background(color))
            Box(Modifier.offset(x = (placement.left + 6).dp, y = 65.dp).size(7.dp)
                .clip(CircleShape).background(palette.surface).border(1.dp, color, CircleShape))
        }
        placements.forEach { placement ->
            val point = placement.point
            val active = selected == point.key
            val top = 94 + laneHeight * placement.lane
            key(point.key) {
                PlanCard(
                    id = point.key,
                    active = active,
                    running = point.plans.any { it.status() == "RUNNING" },
                    modifier = Modifier.offset(x = placement.left.dp, y = top.dp).width(cardWidth.dp).height((84 * scale).dp),
                    onHover = { onHover(point, it) },
                    onClick = { onClick(point) },
                    onAnchor = onAnchor,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CupertinoText(timeLabel(point.at), Modifier.weight(1f), fontSize = 11.sp, color = palette.secondaryText)
                        if (point.plans.size > 1) CupertinoText("▢ ${point.plans.size}", fontSize = 11.sp, color = palette.link)
                        else {
                            val plan = point.plans.single()
                            if (plan.availability() != "AVAILABLE" || plan.status() != "IDLE" || plan.lastResult() == "ERROR")
                                CupertinoText(planStatus(plan, text, label), fontSize = 10.sp, maxLines = 1,
                                    modifier = Modifier.widthIn(max = (100 * scale).dp),
                                    overflow = TextOverflow.Ellipsis, color = planColor(plan))
                        }
                    }
                    CupertinoText(
                        if (point.plans.size == 1) text(point.plans.single().title()) else label("group", listOf(point.plans.size.toString())),
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    CupertinoText(
                        if (point.plans.size == 1) text(point.plans.single().trigger()) else point.plans.joinToString(" · ") { text(it.title()) },
                        color = palette.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlanCard(
    id: String,
    active: Boolean,
    running: Boolean = false,
    modifier: Modifier,
    onHover: (Boolean) -> Unit,
    onClick: () -> Unit,
    onAnchor: (String, Rect, FocusRequester) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalExperiencePalette.current
    val interaction = remember { MutableInteractionSource() }
    var hovered by remember { mutableStateOf(false) }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val scale by animateFloatAsState(if (pressed) .975f else 1f, spring(1f, 700f))
    val fill by animateColorAsState(if (hovered || active || running) lerp(palette.surface, palette.selection, .6f) else palette.secondarySurface)
    DisposableEffect(id) { onDispose { onHover(false) } }
    Column(
        modifier.testTag("automation.card.$id").onGloballyPositioned { onAnchor(id, it.boundsInWindow(), focus) }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp)).background(fill)
            .then(if (focused) Modifier.border(1.5.dp, palette.link, RoundedCornerShape(12.dp)) else Modifier)
            .pointerHoverIcon(PointerIcon.Hand).focusRequester(focus)
            .onPointerEvent(PointerEventType.Enter) { hovered = true; onHover(true) }
            .onPointerEvent(PointerEventType.Exit) { hovered = false; onHover(false) }
            .onPointerEvent(PointerEventType.Release, PointerEventPass.Final) {
                if (focused) focusManager.clearFocus()
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onClick() }
            .semantics { selected = active }
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        content = content,
    )
}

internal fun planStatus(plan: DesktopUiNode.AutomationPlan, text: (DesktopUiNode.TextToken) -> String,
                        label: (String, List<String>) -> String): String =
    when {
        plan.availability() != "AVAILABLE" -> text(DesktopUiNode.TextToken.key("desktop.ui.automation.availability." + plan.availability().lowercase()))
        plan.status() == "IDLE" && plan.lastResult() == "ERROR" -> label("last-error", emptyList())
        else -> text(DesktopUiNode.TextToken.key("desktop.ui.automation.status." + plan.status().lowercase()))
    }

@Composable
internal fun planColor(plan: DesktopUiNode.AutomationPlan) = LocalExperiencePalette.current.let {
    when {
        plan.availability() != "AVAILABLE" || plan.lastResult() == "ERROR" || plan.status() == "SUSPENDED" -> it.warning
        plan.status() == "RUNNING" -> it.link
        else -> it.secondaryText
    }
}
