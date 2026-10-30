@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package top.sywyar.pixivdownload.guicompose.tools

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.*
import top.sywyar.pixivdownload.guicompose.DesktopIcon
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.BackfillState
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.BackfillTask
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil

private const val NOTICE_TIMEOUT_MILLIS = 10_000L

/** 边框只表示提示的剩余展示时间，任务进度仍由工具页展示。 */
@Composable
internal fun AutomaticBackfillNotice(
    task: BackfillTask?,
    text: (TextToken) -> String,
    onView: (UUID) -> Unit,
    onDismiss: (UUID) -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    var retainedTask by remember { mutableStateOf(task) }
    if (task != null) retainedTask = task
    val appearance = remember { Animatable(0f) }
    val reducedMotion = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor == 0f
    val shown = task != null && visible
    LaunchedEffect(shown, task == null, reducedMotion) {
        val target = if (shown) 1f else 0f
        if (reducedMotion) appearance.snapTo(target)
        else appearance.animateTo(target, if (shown) spring(stiffness = 500f) else tween(160))
        if (task == null) retainedTask = null
    }
    val rendered by remember(shown) { derivedStateOf { shown || appearance.value > 0f } }
    val settled by remember { derivedStateOf { !appearance.isRunning && appearance.value == 1f } }
    retainedTask?.let { current ->
        key(current.executionId()) {
            BackfillNoticeContent(
                task = current,
                text = text,
                onView = { if (shown) onView(current.executionId()) },
                onDismiss = { if (shown) onDismiss(current.executionId()) },
                modifier = modifier.graphicsLayer {
                    val amount = appearance.value
                    alpha = amount
                    scaleX = if (reducedMotion) 1f else .98f + .02f * amount
                    scaleY = scaleX
                    translationY = if (reducedMotion) 0f else 12.dp.toPx() * (1 - amount)
                    transformOrigin = TransformOrigin(1f, 1f)
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
                rendered = rendered,
                interactive = shown,
                readable = shown && settled,
                reducedMotion = reducedMotion,
            )
        }
    }
}

@Composable
private fun BackfillNoticeContent(
    task: BackfillTask,
    text: (TextToken) -> String,
    onView: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
    rendered: Boolean,
    interactive: Boolean,
    readable: Boolean,
    reducedMotion: Boolean,
) {
    val palette = LocalExperiencePalette.current
    val accessibility = LocalAccessibilityManager.current
    val timeout = remember(accessibility) {
        accessibility?.calculateRecommendedTimeoutMillis(NOTICE_TIMEOUT_MILLIS, true, true, true)
            ?: NOTICE_TIMEOUT_MILLIS
    }
    val remaining = remember { mutableDoubleStateOf(timeout.toDouble()) }
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val automaticDismiss = task.state() != BackfillState.FAILED && timeout < Long.MAX_VALUE
    val paused = !readable || hovered || focused || !windowFocused
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(paused, automaticDismiss) {
        if (!paused && automaticDismiss) {
            var previous = withFrameNanos { it }
            while (remaining.doubleValue > 0) {
                val now = withFrameNanos { it }
                remaining.doubleValue = (remaining.doubleValue - (now - previous) / 1_000_000.0).coerceAtLeast(0.0)
                previous = now
            }
            dismiss()
        }
    }
    if (!rendered) return
    val seconds by remember { derivedStateOf { ceil(remaining.doubleValue / 1000).toLong() } }
    fun label(key: String) = text(TextToken.key("gui.tools.backfill.notice.$key"))
    val shape = RoundedCornerShape(14.dp)
    val elevatedSurface = if (palette.surface.luminance() < .5f) palette.secondarySurface else palette.surface
    Column(
        modifier.widthIn(max = 340.dp).fillMaxWidth().heightIn(max = 320.dp)
            .shadow(
                elevation = 20.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = .5f),
                spotColor = Color.Black.copy(alpha = .6f),
            )
            .background(elevatedSurface, shape)
            .drawWithCache {
                val inset = 1.dp.toPx()
                val outline = Path().apply {
                    addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset,
                        CornerRadius(13.dp.toPx())))
                }
                val measure = PathMeasure().apply { setPath(outline, true) }
                val segment = Path()
                val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                onDrawWithContent {
                    drawContent()
                    drawPath(outline, palette.separator, style = Stroke(1.dp.toPx()))
                    if (automaticDismiss) {
                        // 减少动态效果时按整秒更新边框，不改变实际可阅读时间。
                        val millis = if (reducedMotion) ceil(remaining.doubleValue / 1000) * 1000 else remaining.doubleValue
                        segment.reset()
                        measure.getSegment(0f, measure.length * (millis / timeout).toFloat().coerceIn(0f, 1f), segment)
                        drawPath(segment, palette.link, style = stroke)
                    }
                }
            }
            .testTag("backfill.notice")
            .then(if (interactive) Modifier else Modifier.clearAndSetSemantics { })
            .hoverable(interactions, enabled = interactive)
            .onFocusChanged { focused = it.hasFocus }
            .focusGroup()
            .pointerInput(Unit) { detectTapGestures { } }
            .onPreviewKeyEvent {
                if (interactive && it.key == Key.Escape && it.type == KeyEventType.KeyUp) { onDismiss(); true } else false
            }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DesktopIcon(Icons.Default.Info, null, Modifier.size(18.dp), tint = palette.link)
            CupertinoText(
                label("title"),
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            CupertinoIconButton(
                onClick = onDismiss,
                enabled = interactive,
                modifier = Modifier.size(32.dp).testTag("backfill.notice.dismiss"),
            ) {
                DesktopIcon(Icons.Default.Close, label("dismiss"), Modifier.size(16.dp), tint = palette.secondaryText)
            }
        }
        CupertinoText(
            text(TextToken.key("gui.tools.backfill.state." + task.state().name.lowercase(Locale.ROOT))),
            color = palette.secondaryText,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .testTag("backfill.notice.state").semantics { liveRegion = LiveRegionMode.Polite },
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (automaticDismiss) CupertinoText(
                if (paused) label("paused") else text(TextToken(null, "gui.tools.backfill.notice.countdown", "", listOf(seconds.toString()))),
                fontSize = 11.sp,
                color = palette.secondaryText,
                modifier = Modifier.testTag("backfill.notice.countdown"),
            )
            CupertinoButton(
                onClick = onView,
                enabled = interactive,
                modifier = Modifier.heightIn(min = 32.dp).testTag("backfill.notice.view"),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                colors = CupertinoButtonDefaults.filledButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = palette.link,
                ),
            ) {
                CupertinoText(label(if (task.active()) "view" else "results"), fontSize = 12.sp)
            }
        }
    }
}
