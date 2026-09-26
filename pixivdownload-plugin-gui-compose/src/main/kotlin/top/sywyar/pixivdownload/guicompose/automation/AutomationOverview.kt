@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.automation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.sywyar.pixivdownload.guicompose.DesktopIcon
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class PlanSelection(val key: String, val ids: List<String>, val planId: String? = null, val at: Long? = null)

@Composable
internal fun AutomationOverview(
    node: DesktopUiNode.AutomationOverview,
    text: (DesktopUiNode.TextToken) -> String,
    emit: (DesktopUiNode.Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalExperiencePalette.current
    val scope = rememberCoroutineScope()
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    var zoom by rememberSaveable { mutableStateOf(false) }
    var otherExpanded by rememberSaveable { mutableStateOf(true) }
    var selection by remember { mutableStateOf<PlanSelection?>(null) }
    var pinned by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var pointerInDetail by remember { mutableStateOf(false) }
    var hoveredKey by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Job?>(null) }
    val anchors = remember { mutableStateMapOf<String, Pair<Rect, FocusRequester>>() }
    var detailBounds by remember { mutableStateOf(Rect.Zero) }
    var pageOrigin by remember { mutableStateOf(Offset.Zero) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    val start = if (node.observedAt() > 0) node.observedAt() else now
    val points = remember(node.plans(), start) { planPoints(node.plans(), start, start + 86_400_000L) }
    val scheduled = points.flatMap { it.plans }.map { it.id() }.toSet()
    val others = node.plans().filter { it.id() !in scheduled }
    val currentPlans = selection?.ids?.mapNotNull { id -> node.plans().find { it.id() == id } }.orEmpty()
    fun label(key: String, args: List<String> = emptyList()) =
        text(DesktopUiNode.TextToken("gui-compose", "gui.compose.automation.$key", "", args))
    fun activate(button: DesktopUiNode.Button) = emit(
        DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, button.id(), DesktopUiNode.Value.empty()))
    fun dismiss(restore: Boolean = false) {
        pending?.cancel()
        if (restore) selection?.key?.let { anchors[it]?.second?.requestFocus() }
        visible = false
        pinned = false
        pointerInDetail = false
        hoveredKey = null
    }
    fun open(key: String, plans: List<DesktopUiNode.AutomationPlan>, pin: Boolean) {
        pending?.cancel()
        selection = PlanSelection(key, plans.map { it.id() }, at = points.find { it.key == key }?.at)
        pinned = pin
        visible = true
    }
    fun hover(key: String, plans: List<DesktopUiNode.AutomationPlan>, entered: Boolean) {
        if (pinned) return
        if (entered) hoveredKey = key
        else if (hoveredKey == key) hoveredKey = null
        else return
        pending?.cancel()
        pending = scope.launch {
            delay(if (entered) 300 else 230)
            if (entered) open(key, plans, false) else if (!pointerInDetail) dismiss()
        }
    }
    LaunchedEffect(currentPlans, node.sources()) {
        selection?.let { current ->
            if (currentPlans.isEmpty() || (current.planId != null && currentPlans.none { it.id() == current.planId })) {
                dismiss()
                selection = null
            }
        }
    }
    // 来源撤回后同步回收锚点，避免保留旧计划的焦点对象。
    LaunchedEffect(points, others) {
        val keys = points.map { it.key }.toSet() + others.map { it.id() }
        anchors.keys.toList().filter { it !in keys }.forEach(anchors::remove)
        selection?.let { current ->
            if (current.key !in keys) {
                val point = points.firstOrNull { p -> p.plans.any { it.id() in current.ids } }
                val other = others.firstOrNull { it.id() in current.ids }
                if (point != null || other != null) selection = current.copy(key = point?.key ?: other!!.id())
            }
        }
    }
    Box(modifier.fillMaxSize().testTag("automation.overview")
        .onGloballyPositioned { pageOrigin = it.positionInWindow() }
        .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
            val point = event.changes.firstOrNull()?.position?.plus(pageOrigin)
            if (point != null && visible && !detailBounds.contains(point) &&
                selection?.key?.let { anchors[it]?.first?.contains(point) } != true) dismiss()
        }
        .onPreviewKeyEvent {
        if (it.key == Key.Escape && it.type == KeyEventType.KeyDown && selection != null) {
            dismiss(true); true
        } else false
    }) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(end = 12.dp)) {
            val narrow = maxWidth < 620.dp
            Column(
                Modifier.fillMaxSize()
                    .padding(horizontal = if (narrow) 24.dp else 44.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                FlowRow(Modifier.fillMaxWidth().testTag("automation.header"),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).widthIn(min = 240.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CupertinoText(text(DesktopUiNode.TextToken.key("desktop.ui.automation.title")),
                            Modifier.semantics { heading() }, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        CupertinoText(label("welcome"), fontSize = 14.sp, color = palette.secondaryText)
                    }
                    FlowRow(Modifier.testTag("automation.toolbar"),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.clip(RoundedCornerShape(9.dp)).background(palette.secondarySurface).padding(3.dp)) {
                            listOf(false, true).forEach { enlarged ->
                                CupertinoText(label(if (enlarged) "zoom" else "fit"),
                                    Modifier.testTag(if (enlarged) "automation.zoom" else "automation.fit")
                                        .clip(RoundedCornerShape(7.dp)).background(if (zoom == enlarged) palette.surface else palette.secondarySurface)
                                        .clickable(role = Role.Button) { zoom = enlarged }
                                        .semantics { selected = zoom == enlarged }.padding(horizontal = 11.dp, vertical = 7.dp),
                                    color = if (zoom == enlarged) palette.text else palette.secondaryText, fontSize = 12.sp)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ArrowButton(label("earlier"), Icons.Default.ChevronLeft, horizontal.canScrollBackward) {
                                scope.launch { horizontal.animateScrollTo((horizontal.value - 550).coerceAtLeast(0)) }
                            }
                            ArrowButton(label("later"), Icons.Default.ChevronRight, horizontal.canScrollForward) {
                                scope.launch { horizontal.animateScrollTo((horizontal.value + 550).coerceAtMost(horizontal.maxValue)) }
                            }
                        }
                        node.management().firstOrNull()?.let { button ->
                            TimelineButton(label("manage"), "automation.manage", { activate(button) }, iconOnly = narrow)
                        }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).testTag("automation.canvas")) {
                    val timelineHeight = (maxHeight - if (others.isEmpty()) 0.dp else 48.dp).coerceAtLeast(220.dp)
                    Column(Modifier.fillMaxWidth().verticalScroll(vertical), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        if (points.isEmpty()) {
                            val state = when {
                                !node.known() || node.sources().any { it.availability() == "UNAVAILABLE" } -> "unavailable"
                                node.sources().isEmpty() -> "no-source"
                                node.sources().any { it.availability() == "STALE" } -> "stale"
                                node.plans().isEmpty() -> "no-tasks"
                                else -> "no-runs"
                            }
                            Column(Modifier.testTag("automation.empty").fillMaxWidth().heightIn(min = timelineHeight).padding(24.dp),
                                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                DesktopIcon(Icons.Default.Schedule, null, Modifier.size(30.dp), palette.secondaryText)
                                Spacer(Modifier.height(15.dp))
                                CupertinoText(label(state), Modifier.widthIn(max = 400.dp), fontSize = 13.sp, color = palette.secondaryText)
                            }
                        } else BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                            val chartWidth = maxOf(maxWidth, (if (zoom) 1800 else 800).dp * scale)
                            Box(Modifier.fillMaxWidth().horizontalScroll(horizontal)) {
                                AutomationTimeline(points, start, now, chartWidth, if (visible) selection?.key else null,
                                    text, ::label,
                                    onHover = { point, entered -> hover(point.key, point.plans, entered) },
                                    onClick = { point ->
                                        if (selection?.key == point.key && pinned) dismiss() else open(point.key, point.plans, true)
                                    },
                                    onAnchor = { key, rect, focus -> anchors[key] = rect to focus },
                                    minHeight = timelineHeight,
                                )
                            }
                        }
                        if (others.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(Modifier.testTag("automation.other-toggle").clickable(role = Role.Button) {
                                otherExpanded = !otherExpanded
                                if (!otherExpanded && selection?.key in others.map { it.id() }) dismiss()
                            }.semantics { stateDescription = label(if (otherExpanded) "expanded" else "collapsed") }
                                .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                val rotation by animateFloatAsState(if (otherExpanded) 90f else 0f, spring(1f, 600f))
                                DesktopIcon(Icons.Default.ChevronRight, null, Modifier.size(16.dp).graphicsLayer { rotationZ = rotation }, palette.secondaryText)
                                Spacer(Modifier.width(7.dp))
                                CupertinoText(label("other"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                CupertinoText(others.size.toString(), Modifier.padding(start = 9.dp)
                                    .clip(RoundedCornerShape(5.dp)).background(palette.secondarySurface).padding(5.dp), fontSize = 11.sp)
                            }
                            AnimatedVisibility(otherExpanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    others.chunked(if (narrow) 1 else 3).forEach { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            row.forEach { plan ->
                                                key(plan.id()) {
                                                    PlanCard(plan.id(), visible && selection?.key == plan.id(),
                                                        modifier = Modifier.weight(1f),
                                                        onHover = { hover(plan.id(), listOf(plan), it) },
                                                        onClick = { if (selection?.key == plan.id() && pinned) dismiss() else open(plan.id(), listOf(plan), true) },
                                                        onAnchor = { key, rect, focus -> anchors[key] = rect to focus },
                                                    ) {
                                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                            DesktopIcon(if (plan.status() == "SUSPENDED" || plan.status() == "DISABLED") Icons.Default.Pause else Icons.Default.Schedule,
                                                                null, Modifier.size(20.dp), planColor(plan))
                                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                                CupertinoText(text(plan.title()), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                                CupertinoText(planStatus(plan, text, ::label), fontSize = 11.sp, color = planColor(plan))
                                                            }
                                                            DesktopIcon(Icons.Default.ChevronRight, null, Modifier.size(14.dp), palette.secondaryText)
                                                        }
                                                    }
                                                }
                                            }
                                            if (!narrow) repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(vertical), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    node.sources().forEach { source ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            DesktopIcon(if (source.availability() == "AVAILABLE") Icons.Default.CheckCircle else Icons.Default.Warning,
                                null, Modifier.size(12.dp), if (source.availability() == "AVAILABLE") palette.success else palette.warning)
                            CupertinoText(source.owner(), fontSize = 11.sp, color = palette.secondaryText)
                            CupertinoText(text(DesktopUiNode.TextToken.key("desktop.ui.automation.availability." + source.availability().lowercase())),
                                fontSize = 11.sp, color = if (source.availability() == "AVAILABLE") palette.secondaryText else palette.warning)
                            CupertinoText(label("observed", listOf(formatPlanTimestamp(source.observedAt()))),
                                fontSize = 11.sp, color = palette.secondaryText)
                        }
                    }
                }
            }
        }
        selection?.let { current ->
            if (currentPlans.isNotEmpty()) AutomationDetails(
                selection = current,
                plans = currentPlans,
                anchor = anchors[current.key]?.first ?: Rect.Zero,
                pinned = pinned,
                visible = visible,
                text = text,
                label = ::label,
                onPin = { pinned = true },
                onSelect = { id -> selection = current.copy(planId = id); pinned = true },
                onDismiss = { dismiss(it) },
                onHidden = { if (!visible) selection = null },
                onBounds = { detailBounds = it },
                onHover = { entered ->
                    pointerInDetail = entered
                    pending?.cancel()
                    if (!entered && !pinned) pending = scope.launch { delay(230); dismiss() }
                },
                onManage = { plan ->
                    node.management().find { it.actionId() == plan?.actionId() }?.let(::activate)
                },
            )
        }
    }
}

internal fun formatPlanTimestamp(at: Long?): String = at?.let {
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
} ?: "—"

@Composable
internal fun TimelineButton(title: String, tag: String, onClick: () -> Unit, primary: Boolean = false, iconOnly: Boolean = false,
                            modifier: Modifier = Modifier) {
    val palette = LocalExperiencePalette.current
    Row(modifier.testTag(tag).clip(RoundedCornerShape(10.dp))
        .background(if (primary) palette.accent else palette.secondarySurface)
        .clickable(role = Role.Button, onClick = onClick).semantics { if (iconOnly) contentDescription = title }
        .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        if (!iconOnly) CupertinoText(title, fontSize = 12.sp, color = if (primary) palette.onAccent else palette.text)
        DesktopIcon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(14.dp), if (primary) palette.onAccent else palette.secondaryText)
    }
}

@Composable
internal fun ArrowButton(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalExperiencePalette.current
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = title }, contentAlignment = Alignment.Center) {
        DesktopIcon(icon, null, Modifier.size(15.dp), palette.secondaryText.copy(alpha = if (enabled) 1f else .3f))
    }
}
