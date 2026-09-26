@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.automation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import io.github.robinpcrd.cupertino.*
import top.sywyar.pixivdownload.guicompose.DesktopIcon
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import kotlin.math.roundToInt

/** 浮层位置按窗口约束计算，预览和固定详情共用同一锚点。 */
internal fun detailOffset(anchor: Rect, window: IntSize, content: IntSize, margin: Int,
                          compact: Boolean = window.width < 600): IntOffset {
    val left = (anchor.center.x - content.width / 2).roundToInt().coerceIn(0, (window.width - content.width).coerceAtLeast(0))
    val gap = (margin * .6f).roundToInt()
    val below = anchor.bottom.roundToInt() - margin + gap
    val above = anchor.top.roundToInt() - content.height + margin - gap
    val right = anchor.right.roundToInt() + gap - margin
    val before = anchor.left.roundToInt() - content.width + margin - gap
    if (!compact && below + content.height > window.height && above < 0) {
        val side = if (right + content.width <= window.width) right else if (before >= 0) before else null
        if (side != null) return IntOffset(side,
            (anchor.center.y - content.height / 2).roundToInt().coerceIn(0, (window.height - content.height).coerceAtLeast(0)))
    }
    val top = if (compact) window.height - content.height
        else if (below + content.height <= window.height) below
        else above
    return IntOffset(left, top.coerceIn(0, (window.height - content.height).coerceAtLeast(0)))
}

@Composable
internal fun AutomationDetails(
    selection: PlanSelection,
    plans: List<DesktopUiNode.AutomationPlan>,
    anchor: Rect,
    pinned: Boolean,
    visible: Boolean,
    text: (DesktopUiNode.TextToken) -> String,
    label: (String, List<String>) -> String,
    onPin: () -> Unit,
    onSelect: (String?) -> Unit,
    onDismiss: (Boolean) -> Unit,
    onHidden: () -> Unit,
    onBounds: (Rect) -> Unit,
    onHover: (Boolean) -> Unit,
    onManage: (DesktopUiNode.AutomationPlan?) -> Unit,
) {
    val palette = LocalExperiencePalette.current
    val density = LocalDensity.current
    val margin = with(density) { 20.dp.roundToPx() }
    var popupOffset by remember { mutableStateOf(IntOffset.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val localAnchor = anchor.translate(-origin)
    var entered by remember { mutableStateOf(false) }
    val amount by animateFloatAsState(if (entered && visible) 1f else 0f, tween(160), label = "plan-popover")
    LaunchedEffect(visible, amount) { if (!visible && amount == 0f) onHidden() }
    val titleFocus = remember { FocusRequester() }
    var technical by remember(selection.key, selection.planId) { mutableStateOf(false) }
    val group = plans.size > 1 && selection.planId == null
    val plan = plans.find { it.id() == selection.planId } ?: plans.first()
    LaunchedEffect(Unit) { entered = true }
    LaunchedEffect(pinned, selection.key, selection.planId) { if (pinned) titleFocus.requestFocus() }
    Layout(
        modifier = Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }.onPreviewKeyEvent {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onDismiss(true); true } else false
        },
        content = {
        BoxWithConstraints {
            val narrow = maxWidth < 600.dp
            val availableHeight = (maxHeight - 40.dp).coerceAtLeast(80.dp)
            val above = popupOffset.y < localAnchor.top - margin
            val shape = RoundedCornerShape(17.dp)
            Box(
                Modifier.widthIn(max = 384.dp).padding(20.dp)
                    .graphicsLayer {
                        alpha = amount
                        scaleX = .982f + .018f * amount
                        scaleY = scaleX
                        transformOrigin = TransformOrigin(.5f, if (above) 1f else 0f)
                        translationY = (if (above) 5 else -5) * density.density * (1 - amount)
                    }
                    .onPointerEvent(PointerEventType.Enter) { onHover(true) }
                    .onPointerEvent(PointerEventType.Exit) { onHover(false) }
                    .shadow(24.dp, shape, clip = false, ambientColor = palette.text.copy(alpha = .10f), spotColor = palette.text.copy(alpha = .12f)),
            ) {
                val side = popupOffset.x + margin >= localAnchor.right || popupOffset.x + with(density) { 364.dp.toPx() } <= localAnchor.left
                if (!narrow && !side) Canvas(Modifier.fillMaxWidth().height(7.dp)
                    .align(if (above) Alignment.BottomCenter else Alignment.TopCenter)
                    .offset(y = if (above) 6.dp else (-6).dp)) {
                    val center = (localAnchor.center.x - popupOffset.x - margin).coerceIn(20.dp.toPx(), size.width - 20.dp.toPx())
                    val path = Path().apply {
                        moveTo(center - 6.dp.toPx(), if (above) 0f else size.height)
                        lineTo(center, if (above) size.height else 0f)
                        lineTo(center + 6.dp.toPx(), if (above) 0f else size.height)
                        close()
                    }
                    drawPath(path, palette.surface)
                }
                Column(
                    Modifier.testTag("automation.detail").width(344.dp).onGloballyPositioned { onBounds(it.boundsInWindow()) }
                        .heightIn(max = availableHeight)
                        .clip(shape).background(palette.surface)
                        .animateContentSize(tween(240)).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (plans.size > 1 && !group) Row(Modifier.weight(1f).testTag("automation.detail.back")
                            .clickable(role = Role.Button) { onSelect(null) }, verticalAlignment = Alignment.CenterVertically) {
                            DesktopIcon(Icons.Default.ChevronLeft, null, Modifier.size(14.dp), palette.link)
                            CupertinoText(label("group-back", emptyList()), fontSize = 11.sp, color = palette.link)
                        } else Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            DesktopIcon(if (pinned) Icons.Default.PushPin else Icons.Default.Schedule, null,
                                Modifier.size(13.dp), palette.secondaryText)
                            CupertinoText(label(if (pinned) "details" else "preview", emptyList()),
                                fontSize = 11.sp, color = palette.secondaryText)
                        }
                        Box(Modifier.testTag("automation.detail.close").size(28.dp).clip(RoundedCornerShape(7.dp))
                            .clickable(role = Role.Button) { onDismiss(true) }
                            .semantics { contentDescription = label("close", emptyList()) }, contentAlignment = Alignment.Center) {
                            DesktopIcon(Icons.Default.Close, null, Modifier.size(16.dp), palette.secondaryText)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        CupertinoText(
                            if (group) (selection.at?.let { timeLabel(it) + " · " } ?: "") + label("group", listOf(plans.size.toString()))
                                else text(plan.title()),
                            Modifier.testTag("automation.detail.title").focusRequester(titleFocus).focusable()
                                .semantics { heading() },
                            fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                        )
                        CupertinoText(
                            if (group) label("same-time", emptyList()) else text(plan.trigger()),
                            fontSize = 12.sp, color = palette.secondaryText,
                        )
                    }
                    if (group) Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        plans.forEach { item ->
                            Row(Modifier.fillMaxWidth().testTag("automation.detail.plan.${item.id()}")
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.secondarySurface)
                                .clickable(role = Role.Button) { onSelect(item.id()) }.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    CupertinoText(text(item.title()), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    CupertinoText(planStatus(item, text, label), fontSize = 11.sp, color = planColor(item))
                                }
                                DesktopIcon(Icons.Default.ChevronRight, null, Modifier.size(14.dp), palette.secondaryText)
                            }
                        }
                    } else {
                        val warning = plan.availability() != "AVAILABLE" || plan.lastResult() == "ERROR" || plan.status() == "SUSPENDED"
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (warning) palette.warningSurface else palette.selection.copy(alpha = .6f)).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            DesktopIcon(if (warning) Icons.Default.Warning else Icons.Default.Schedule, null,
                                Modifier.size(14.dp), planColor(plan))
                            CupertinoText(planStatus(plan, text, label), fontSize = 11.sp, color = planColor(plan))
                        }
                        Fact(label("next", emptyList()), formatPlanTimestamp(plan.nextRuns().firstOrNull()))
                        Fact(label("result", emptyList()), text(DesktopUiNode.TextToken.key(
                            "desktop.ui.automation.last-result." + plan.lastResult().lowercase())))
                        CupertinoHorizontalDivider(color = palette.separator.copy(alpha = .6f))
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.testTag("automation.detail.technical").clickable(role = Role.Button) {
                                technical = !technical; onPin()
                            }.semantics { stateDescription = label(if (technical) "expanded" else "collapsed", emptyList()) },
                                verticalAlignment = Alignment.CenterVertically) {
                                val rotation by animateFloatAsState(if (technical) 90f else 0f, spring(1f, 650f))
                                DesktopIcon(Icons.Default.ChevronRight, null,
                                    Modifier.size(12.dp).graphicsLayer { rotationZ = rotation }, palette.secondaryText)
                                CupertinoText(label("technical", emptyList()), fontSize = 11.sp, color = palette.secondaryText)
                            }
                            AnimatedVisibility(technical, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Fact(label("source", emptyList()), plan.owner())
                                    Fact(label("trigger", emptyList()), text(plan.trigger()))
                                    Fact(label("observed-label", emptyList()), formatPlanTimestamp(plan.observedAt()))
                                    Fact(label("task-id", emptyList()), plan.taskId())
                                }
                            }
                        }
                    }
                    if (!group && plan.actionId() != null) {
                        TimelineButton(
                            label("manage", emptyList()), "automation.detail.manage", { onPin(); onManage(plan) },
                            primary = pinned, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        },
    ) { measurables, constraints ->
        val content = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val offset = detailOffset(localAnchor, IntSize(constraints.maxWidth, constraints.maxHeight),
            IntSize(content.width, content.height), margin, constraints.maxWidth < with(density) { 600.dp.toPx() })
        popupOffset = offset
        layout(constraints.maxWidth, constraints.maxHeight) { content.place(offset.x, offset.y) }
    }
}

@Composable
private fun Fact(title: String, value: String) {
    val palette = LocalExperiencePalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        CupertinoText(title, Modifier.weight(.4f), fontSize = 12.sp, color = palette.secondaryText)
        CupertinoText(value, Modifier.weight(.6f), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
