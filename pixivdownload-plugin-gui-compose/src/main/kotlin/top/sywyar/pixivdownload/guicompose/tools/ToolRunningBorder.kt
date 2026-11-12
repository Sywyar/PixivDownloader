package top.sywyar.pixivdownload.guicompose.tools

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette

/** 固定长度的线段表示任务仍在运行，不表示完成比例。 */
@Composable
internal fun Modifier.toolRunningBorder(running: Boolean, cornerRadius: Dp = 13.dp): Modifier {
    if (!running) return this
    val palette = LocalExperiencePalette.current
    val reducedMotion = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor == 0f
    val phase = if (!reducedMotion && LocalWindowInfo.current.isWindowFocused) {
        rememberInfiniteTransition(label = "tool-running").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing)),
            label = "tool-border-position",
        )
    } else remember { mutableFloatStateOf(0f) }
    return drawWithCache {
        val inset = 1.dp.toPx()
        val outline = Path().apply {
            addRoundRect(
                RoundRect(
                    inset,
                    inset,
                    size.width - inset,
                    size.height - inset,
                    CornerRadius(cornerRadius.toPx()),
                ),
            )
        }
        val measure = PathMeasure().apply { setPath(outline, true) }
        val segment = Path()
        val length = 64.dp.toPx().coerceAtMost(measure.length)
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        val borderStroke = Stroke(width = inset)
        onDrawWithContent {
            drawContent()
            drawPath(outline, palette.controlBorder, style = borderStroke)
            segment.reset()
            val start = phase.value * measure.length
            val end = start + length
            measure.getSegment(start, end.coerceAtMost(measure.length), segment)
            if (end > measure.length) measure.getSegment(0f, end - measure.length, segment)
            drawPath(segment, palette.link, style = stroke)
        }
    }
}
