package top.sywyar.pixivdownload.guicompose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.robinpcrd.cupertino.LocalContentColor

internal val LocalExperiencePalette = staticCompositionLocalOf { ExperienceTokens.light }

/** Cupertino 未提供的桌面图标、单选和进度使用 Foundation，继续共享语义色与无障碍状态。 */
@Composable
internal fun DesktopIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) = Image(imageVector, contentDescription, modifier.size(24.dp), colorFilter = ColorFilter.tint(tint))

@Composable
internal fun DesktopRadioButton(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val palette = LocalExperiencePalette.current
    val color = if (!enabled) palette.secondaryText else if (selected) palette.link else palette.controlBorder
    Canvas(modifier.size(36.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(8.dp)) {
        drawCircle(color, style = Stroke(2.dp.toPx()))
        if (selected) drawCircle(color, radius = size.minDimension * .25f)
    }
}

@Composable
internal fun DesktopCircularProgress(progress: Float, modifier: Modifier = Modifier) {
    val palette = LocalExperiencePalette.current
    Canvas(modifier.progressSemantics(progress).padding(3.dp)) {
        val stroke = Stroke(4.dp.toPx(), cap = StrokeCap.Round)
        drawArc(palette.separator, 0f, 360f, false, style = stroke)
        drawArc(palette.link, -90f, progress.coerceIn(0f, 1f) * 360f, false, style = stroke)
    }
}

@Composable
internal fun DesktopLinearProgress(progress: Float?, modifier: Modifier = Modifier) {
    val palette = LocalExperiencePalette.current
    if (progress != null) {
        Box(modifier.progressSemantics(progress).clip(CircleShape).background(palette.separator)) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(palette.link))
        }
    } else {
        val position by rememberInfiniteTransition(label = "indeterminate-progress").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "position",
        )
        Canvas(modifier.progressSemantics().clip(CircleShape).background(palette.separator)) {
            val segmentWidth = size.width * .3f
            for (copy in 0..1) {
                drawRoundRect(
                    color = palette.link,
                    topLeft = Offset((position - copy) * size.width, 0f),
                    size = Size(segmentWidth, size.height),
                    cornerRadius = CornerRadius(size.height / 2),
                )
            }
        }
    }
}
