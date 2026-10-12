@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.CupertinoText
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextStyle

@Composable
internal fun DesktopNotice(
    message: String,
    style: TextStyle = TextStyle.BODY,
    modifier: Modifier = Modifier,
) {
    val palette = LocalExperiencePalette.current
    val (color, surface, icon) = when (style) {
        TextStyle.SUCCESS -> Triple(palette.success, palette.successSurface, Icons.Default.CheckCircle)
        TextStyle.WARNING -> Triple(palette.warning, palette.warningSurface, Icons.Default.Warning)
        TextStyle.ERROR -> Triple(palette.error, palette.errorSurface, Icons.Default.Error)
        else -> Triple(palette.link, palette.selection, Icons.Default.Info)
    }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier.fillMaxWidth()
            .background(surface, shape)
            .border(1.dp, color.copy(alpha = .35f), shape)
            .padding(12.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                if (style == TextStyle.ERROR) error(message)
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DesktopIcon(icon, contentDescription = null, modifier = Modifier.size(17.dp), tint = color)
        SelectionContainer(Modifier.weight(1f)) {
            CupertinoText(message, fontSize = 12.sp, color = color)
        }
    }
}
