@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.*
import top.sywyar.pixivdownload.guicompose.DesktopIcon
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import kotlin.math.roundToInt

/** 示意动画只解释操作，不投影下载进度或网络连接结果。 */
@Composable
internal fun OnboardingDemo(
    topic: DesktopUiNode.OnboardingTopic,
    text: (DesktopUiNode.TextToken) -> String,
    reducedMotion: Boolean,
    modifier: Modifier,
) {
    val palette = LocalExperiencePalette.current
    val frame = remember(topic) { Animatable(if (reducedMotion) 1f else 0f) }
    var playing by remember(topic) { mutableStateOf(!reducedMotion) }
    var replay by remember(topic) { mutableIntStateOf(0) }
    val active = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(topic, playing, replay, reducedMotion, active) {
        if (reducedMotion) {
            frame.snapTo(1f)
            playing = false
        } else if (playing && active) {
            if (frame.value >= 1f) frame.snapTo(0f)
            frame.animateTo(1f, tween(((1 - frame.value) * 6600).roundToInt(), easing = LinearEasing))
            playing = false
        }
    }
    val caption = text(hubToken(when (topic) {
        DesktopUiNode.OnboardingTopic.NETWORK -> "demo.network"
        DesktopUiNode.OnboardingTopic.DOWNLOAD -> "demo.download"
        DesktopUiNode.OnboardingTopic.GUIDE -> "demo.guide"
        DesktopUiNode.OnboardingTopic.ANIMATION -> "demo.animation"
    }))
    val steps = (1..3).map { text(hubToken("demo.${topic.name.lowercase()}.$it")) }
    val stage = (frame.value * 3).toInt().coerceAtMost(2)
    val textMeasurer = rememberTextMeasurer()
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(palette.secondarySurface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CupertinoText(text(hubToken("demo")), color = palette.secondaryText, fontSize = 11.sp)
            if (!reducedMotion) {
                val label = text(hubToken(if (playing) "pause" else if (frame.value >= 1f) "replay" else "play"))
                CupertinoButton(
                    onClick = {
                        if (frame.value >= 1f) replay++
                        playing = !playing
                    },
                    colors = CupertinoButtonDefaults.plainButtonColors(contentColor = palette.secondaryText),
                    contentPadding = PaddingValues(5.dp),
                    modifier = Modifier.testTag("hub.demo.playback")
                        .semantics { contentDescription = label },
                ) {
                    DesktopIcon(
                        if (playing) Icons.Default.Pause else if (frame.value >= 1f) Icons.Default.Replay else Icons.Default.PlayArrow,
                        null,
                        Modifier.size(17.dp),
                        palette.secondaryText,
                    )
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(144.dp).testTag("hub.demo.scene").semantics {
            contentDescription = caption
            stateDescription = steps[stage]
            progressBarRangeInfo = ProgressBarRangeInfo(frame.value, 0f..1f)
        }) {
            val progress = frame.value
            when (topic) {
                DesktopUiNode.OnboardingTopic.NETWORK -> {
                    val w = minOf(300.dp.toPx(), size.width - 16.dp.toPx())
                    val x = (size.width - w) / 2
                    val y = 8.dp.toPx()
                    drawRoundRect(palette.surface, Offset(x, y), Size(w, 128.dp.toPx()), CornerRadius(10.dp.toPx()))
                    val enabled = ((progress - .08f) * 8).coerceIn(0f, 1f)
                    drawLine(palette.separator, Offset(x + 16.dp.toPx(), y + 24.dp.toPx()),
                        Offset(x + w * .48f, y + 24.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
                    val switchX = x + w - 52.dp.toPx()
                    drawRoundRect(lerp(palette.separator, palette.accent, enabled),
                        Offset(switchX, y + 14.dp.toPx()), Size(36.dp.toPx(), 20.dp.toPx()), CornerRadius(10.dp.toPx()))
                    drawCircle(palette.surface, 7.dp.toPx(), Offset(switchX + (10 + 16 * enabled).dp.toPx(), y + 24.dp.toPx()))
                    val typed = ((progress - .34f) * 4).coerceIn(0f, 1f)
                    listOf("127.0.0.1", "7890").forEachIndexed { index, value ->
                        val fieldX = x + 14.dp.toPx() + index * (w - 28.dp.toPx()) * .64f
                        val fieldWidth = (w - 28.dp.toPx()) * (if (index == 0) .6f else .36f)
                        drawRoundRect(palette.secondarySurface, Offset(fieldX, y + 48.dp.toPx()),
                            Size(fieldWidth, 28.dp.toPx()), CornerRadius(5.dp.toPx()))
                        drawText(textMeasurer, value.take((value.length * typed).toInt()),
                            Offset(fieldX + 8.dp.toPx(), y + 53.dp.toPx()),
                            style = TextStyle(color = palette.text, fontSize = 11.sp))
                    }
                    val saved = ((progress - .72f) * 5).coerceIn(0f, 1f)
                    drawRoundRect(palette.accent.copy(alpha = .1f + saved * .12f),
                        Offset(x + w - 70.dp.toPx(), y + 90.dp.toPx()), Size(56.dp.toPx(), 24.dp.toPx()), CornerRadius(6.dp.toPx()))
                    if (saved < 1f) {
                        drawCircle(palette.accent.copy(alpha = (1 - saved) * .6f), (5 + saved * 12).dp.toPx(),
                            Offset(x + w - 42.dp.toPx(), y + 102.dp.toPx()), style = Stroke(2.dp.toPx()))
                    } else checkMark(Offset(x + w - 42.dp.toPx(), y + 102.dp.toPx()), palette.accent)
                }
                DesktopUiNode.OnboardingTopic.DOWNLOAD -> {
                    val w = size.width * .8f
                    val x = (size.width - w) / 2
                    val y = 20.dp.toPx()
                    drawRoundRect(palette.surface, Offset(x, y), Size(w, 104.dp.toPx()), CornerRadius(9.dp.toPx()))
                    drawRoundRect(
                        palette.secondarySurface,
                        Offset(x + 14.dp.toPx(), y + 14.dp.toPx()),
                        Size(w - 28.dp.toPx(), 23.dp.toPx()),
                        CornerRadius(5.dp.toPx())
                    )
                    val typed = (progress * 3).coerceIn(0f, 1f)
                    drawLine(
                        palette.secondaryText.copy(alpha = .5f),
                        Offset(x + 23.dp.toPx(), y + 25.dp.toPx()),
                        Offset(x + 23.dp.toPx() + (w - 90.dp.toPx()) * typed, y + 25.dp.toPx()),
                        3.dp.toPx(),
                        StrokeCap.Round
                    )
                    drawRoundRect(
                        palette.accent,
                        Offset(x + w - 40.dp.toPx(), y + 18.dp.toPx()),
                        Size(18.dp.toPx(), 15.dp.toPx()),
                        CornerRadius(4.dp.toPx())
                    )
                    val selected = ((progress - .34f) * 5).coerceIn(0f, 1f)
                    val saved = ((progress - .68f) / .25f).coerceIn(0f, 1f)
                    drawRoundRect(
                        palette.secondarySurface,
                        Offset(x + 14.dp.toPx(), y + 52.dp.toPx()),
                        Size(w - 28.dp.toPx(), 27.dp.toPx()),
                        CornerRadius(5.dp.toPx())
                    )
                    drawCircle(palette.accent.copy(alpha = selected), 6.dp.toPx(),
                        Offset(x + 27.dp.toPx(), y + 65.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                    if (selected >= 1) checkMark(Offset(x + 27.dp.toPx(), y + 65.dp.toPx()), palette.accent)
                    drawLine(palette.separator, Offset(x + 42.dp.toPx(), y + 65.dp.toPx()),
                        Offset(x + w - 50.dp.toPx(), y + 65.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
                    if (saved > 0) drawRoundRect(
                        palette.accent.copy(alpha = .12f),
                        Offset(x + 14.dp.toPx(), y + 52.dp.toPx()),
                        Size((w - 28.dp.toPx()) * saved, 27.dp.toPx()),
                        CornerRadius(5.dp.toPx())
                    )
                    if (saved >= 1) checkMark(Offset(x + w - 29.dp.toPx(), y + 65.dp.toPx()), palette.accent)
                    drawLine(palette.separator, Offset(x + 14.dp.toPx(), y + 90.dp.toPx()),
                        Offset(x + w - 14.dp.toPx(), y + 90.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
                    drawLine(palette.accent, Offset(x + 14.dp.toPx(), y + 90.dp.toPx()),
                        Offset(x + 14.dp.toPx() + (w - 28.dp.toPx()) * saved, y + 90.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
                }
                DesktopUiNode.OnboardingTopic.ANIMATION -> {
                    val tile = minOf(76.dp.toPx(), size.width * .22f)
                    val y = (size.height - tile) / 2
                    val start = size.width * .2f - tile / 2
                    val end = size.width * .8f - tile / 2
                    val merge = ((progress - .34f) / .3f).coerceIn(0f, 1f)
                    repeat(3) { index ->
                        val appear = ((progress - index * .05f) * 6).coerceIn(0f, 1f)
                        val offset = (2 - index) * 7.dp.toPx() * (1 - merge)
                        drawRoundRect(
                            palette.surface.copy(alpha = appear),
                            Offset(start + offset, y - offset + (1 - appear) * 12.dp.toPx()),
                            Size(tile, tile),
                            CornerRadius(8.dp.toPx())
                        )
                        drawRoundRect(
                            palette.accent.copy(alpha = (.2f + index * .2f) * appear),
                            Offset(start + offset + tile * .2f, y - offset + tile * .25f + (1 - appear) * 12.dp.toPx()),
                            Size(tile * .6f, tile * .5f),
                            CornerRadius(5.dp.toPx())
                        )
                    }
                    val center = Offset(size.width * .5f, size.height / 2)
                    drawLine(palette.accent, center - Offset(12.dp.toPx(), 0f), center + Offset(12.dp.toPx(), 0f), 2.dp.toPx())
                    drawLine(palette.accent, center + Offset(5.dp.toPx(), -7.dp.toPx()), center + Offset(12.dp.toPx(), 0f), 2.dp.toPx())
                    drawLine(palette.accent, center + Offset(5.dp.toPx(), 7.dp.toPx()), center + Offset(12.dp.toPx(), 0f), 2.dp.toPx())
                    drawRoundRect(
                        palette.surface,
                        Offset(end, y),
                        Size(tile, tile),
                        CornerRadius(8.dp.toPx())
                    )
                    val reveal = ((progress - .64f) / .08f).coerceIn(0f, 1f)
                    drawRoundRect(
                        palette.accent.copy(alpha = .12f * reveal),
                        Offset(end + 6.dp.toPx(), y + 6.dp.toPx()),
                        Size(tile - 12.dp.toPx(), tile - 12.dp.toPx()),
                        CornerRadius(5.dp.toPx())
                    )
                    val motion = kotlin.math.sin(((progress - .68f).coerceAtLeast(0f) / .32f) * Math.PI * 4).toFloat()
                    drawCircle(
                        palette.accent.copy(alpha = reveal),
                        tile * .12f,
                        Offset(end + tile * (.5f + motion * .22f), y + tile / 2)
                    )
                }
                DesktopUiNode.OnboardingTopic.GUIDE -> {
                    val gap = 10.dp.toPx()
                    val w = minOf(90.dp.toPx(), (size.width - 4 * gap) / 3)
                    val start = (size.width - w * 3 - gap * 2) / 2
                    val preview = ((progress - .68f) / .2f).coerceIn(0f, 1f)
                    repeat(3) { index ->
                        val appear = ((progress - index * .05f) * 6).coerceIn(0f, 1f) * (if (index == 1) 1f else 1 - preview)
                        val width = w + (if (index == 1) 48.dp.toPx() * preview else 0f)
                        val x = start + index * (w + gap) - (width - w) / 2
                        val y = 24.dp.toPx() + (1 - appear) * 16.dp.toPx() - preview * 12.dp.toPx()
                        drawRoundRect(
                            palette.surface.copy(alpha = appear),
                            Offset(x, y),
                            Size(width, 88.dp.toPx() + preview * 24.dp.toPx()),
                            CornerRadius(8.dp.toPx())
                        )
                        drawRoundRect(
                            palette.accent.copy(alpha = .08f * appear),
                            Offset(x + 7.dp.toPx(), y + 7.dp.toPx()),
                            Size(width - 14.dp.toPx(), 54.dp.toPx() + preview * 24.dp.toPx()),
                            CornerRadius(5.dp.toPx())
                        )
                        val mountain = Path().apply {
                            moveTo(x + 9.dp.toPx(), y + 53.dp.toPx())
                            lineTo(x + width * .4f, y + 25.dp.toPx())
                            lineTo(x + width * .57f, y + 41.dp.toPx())
                            lineTo(x + width * .73f, y + 32.dp.toPx())
                            lineTo(x + width - 9.dp.toPx(), y + 53.dp.toPx())
                            close()
                        }
                        drawPath(mountain, palette.accent.copy(alpha = (.25f + index * .15f) * appear))
                        drawLine(
                            palette.secondaryText.copy(alpha = .3f * appear),
                            Offset(x + 10.dp.toPx(), y + 74.dp.toPx() + preview * 24.dp.toPx()),
                            Offset(x + width * .7f, y + 74.dp.toPx() + preview * 24.dp.toPx()),
                            3.dp.toPx(),
                            StrokeCap.Round
                        )
                        if (index == 1) drawRoundRect(
                            palette.accent.copy(alpha = ((progress - .34f) * 6).coerceIn(0f, 1f)),
                            Offset(x, y), Size(width, 88.dp.toPx() + preview * 24.dp.toPx()),
                            CornerRadius(8.dp.toPx()), style = Stroke(2.dp.toPx()),
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.forEachIndexed { index, label ->
                val fill = (frame.value * 3 - index).coerceIn(0f, 1f)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(palette.separator)) {
                        Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(palette.accent))
                    }
                    CupertinoText(label, fontSize = 11.sp, lineHeight = 16.sp,
                        color = if (index == stage || reducedMotion) palette.text else palette.secondaryText)
                }
            }
        }
    }
}

private fun DrawScope.checkMark(center: Offset, color: Color) {
    val path = Path().apply {
        moveTo(center.x - 5.dp.toPx(), center.y)
        lineTo(center.x - 1.dp.toPx(), center.y + 4.dp.toPx())
        lineTo(center.x + 6.dp.toPx(), center.y - 4.dp.toPx())
    }
    drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
}
