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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
            frame.animateTo(1f, tween(((1 - frame.value) * 3600).roundToInt(), easing = LinearEasing))
            playing = false
        }
    }
    val caption = text(hubToken(when (topic) {
        DesktopUiNode.OnboardingTopic.NETWORK -> "demo.network"
        DesktopUiNode.OnboardingTopic.DOWNLOAD -> "demo.download"
        DesktopUiNode.OnboardingTopic.GUIDE -> "demo.guide"
    }))
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
        Canvas(Modifier.fillMaxWidth().height(112.dp).semantics { contentDescription = caption }) {
            val progress = frame.value
            when (topic) {
                DesktopUiNode.OnboardingTopic.NETWORK -> {
                    val y = size.height / 2
                    val start = size.width * .16f
                    val end = size.width * .84f
                    drawLine(palette.separator, Offset(start, y), Offset(end, y), 2.dp.toPx())
                    drawLine(
                        palette.accent.copy(alpha = .5f),
                        Offset(start, y),
                        Offset(start + (end - start) * progress, y),
                        2.dp.toPx()
                    )
                    listOf(start, size.width / 2, end).forEachIndexed { index, x ->
                        val reached = progress >= index * .34f
                        drawRoundRect(
                            palette.surface,
                            Offset(x - 23.dp.toPx(), y - 23.dp.toPx()),
                            Size(46.dp.toPx(), 46.dp.toPx()),
                            CornerRadius(12.dp.toPx())
                        )
                        drawCircle(
                            if (reached) palette.accent else palette.secondaryText.copy(alpha = .35f),
                            8.dp.toPx(),
                            Offset(x, y),
                            style = Stroke(2.dp.toPx())
                        )
                        if (index == 1) drawLine(
                            palette.accent,
                            Offset(x - 7.dp.toPx(), y),
                            Offset(x + 7.dp.toPx(), y),
                            2.dp.toPx()
                        )
                    }
                    if (progress < 1) drawCircle(
                        palette.accent,
                        3.dp.toPx(),
                        Offset(start + (end - start) * progress, y)
                    )
                }
                DesktopUiNode.OnboardingTopic.DOWNLOAD -> {
                    val w = size.width * .8f
                    val x = (size.width - w) / 2
                    val y = 8.dp.toPx()
                    drawRoundRect(palette.surface, Offset(x, y), Size(w, 94.dp.toPx()), CornerRadius(9.dp.toPx()))
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
                    val saved = ((progress - .35f) / .5f).coerceIn(0f, 1f)
                    drawRoundRect(
                        palette.secondarySurface,
                        Offset(x + 14.dp.toPx(), y + 52.dp.toPx()),
                        Size(w - 28.dp.toPx(), 27.dp.toPx()),
                        CornerRadius(5.dp.toPx())
                    )
                    if (saved > 0) drawRoundRect(
                        palette.accent.copy(alpha = .12f),
                        Offset(x + 14.dp.toPx(), y + 52.dp.toPx()),
                        Size((w - 28.dp.toPx()) * saved, 27.dp.toPx()),
                        CornerRadius(5.dp.toPx())
                    )
                    if (saved >= 1) checkMark(Offset(x + w - 29.dp.toPx(), y + 65.dp.toPx()), palette.accent)
                }
                DesktopUiNode.OnboardingTopic.GUIDE -> {
                    val gap = 10.dp.toPx()
                    val w = minOf(90.dp.toPx(), (size.width - 4 * gap) / 3)
                    val start = (size.width - w * 3 - gap * 2) / 2
                    repeat(3) { index ->
                        val appear = ((progress - index * .15f) * 3).coerceIn(0f, 1f)
                        val x = start + index * (w + gap)
                        val y = 9.dp.toPx() + (1 - appear) * 16.dp.toPx()
                        drawRoundRect(
                            palette.surface.copy(alpha = appear),
                            Offset(x, y),
                            Size(w, 88.dp.toPx()),
                            CornerRadius(8.dp.toPx())
                        )
                        drawRoundRect(
                            palette.accent.copy(alpha = .08f * appear),
                            Offset(x + 7.dp.toPx(), y + 7.dp.toPx()),
                            Size(w - 14.dp.toPx(), 54.dp.toPx()),
                            CornerRadius(5.dp.toPx())
                        )
                        val mountain = Path().apply {
                            moveTo(x + 9.dp.toPx(), y + 53.dp.toPx())
                            lineTo(x + w * .4f, y + 25.dp.toPx())
                            lineTo(x + w * .57f, y + 41.dp.toPx())
                            lineTo(x + w * .73f, y + 32.dp.toPx())
                            lineTo(x + w - 9.dp.toPx(), y + 53.dp.toPx())
                            close()
                        }
                        drawPath(mountain, palette.accent.copy(alpha = (.25f + index * .15f) * appear))
                        drawLine(
                            palette.secondaryText.copy(alpha = .3f * appear),
                            Offset(x + 10.dp.toPx(), y + 74.dp.toPx()),
                            Offset(x + w * .7f, y + 74.dp.toPx()),
                            3.dp.toPx(),
                            StrokeCap.Round
                        )
                    }
                }
            }
        }
        CupertinoText(caption, color = palette.secondaryText, fontSize = 12.sp, lineHeight = 18.sp)
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
