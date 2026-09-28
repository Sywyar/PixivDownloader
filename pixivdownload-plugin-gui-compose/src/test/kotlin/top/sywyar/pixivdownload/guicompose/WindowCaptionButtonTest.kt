package top.sywyar.pixivdownload.guicompose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class WindowCaptionButtonTest {
    @Test
    fun captionActionsSupportPointerAndKeyboardInBothThemes() {
        for (theme in listOf("light", "dark")) runSkikoComposeUiTest(size = Size(180f, 40f)) {
            val actions = mutableListOf<String>()
            setContent {
                PixivDownloaderTheme(theme) {
                    Row(Modifier.fillMaxSize().background(LocalExperiencePalette.current.surface)) {
                        for ((label, icon) in listOf("Minimize" to Icons.Default.Minimize,
                            "Maximize" to Icons.Default.CropSquare, "Close" to Icons.Default.Close)) {
                            WindowCaptionButton(
                                icon = icon,
                                label = label,
                                onClick = { actions.add(label) },
                                modifier = Modifier.width(46.dp).fillMaxHeight(),
                                close = label == "Close",
                            )
                        }
                    }
                }
            }
            onNodeWithContentDescription("Minimize").performClick()
            onNodeWithContentDescription("Maximize").performMouseInput { click() }
            onNodeWithContentDescription("Maximize").performKeyInput { pressKey(Key.Tab) }
            onNodeWithContentDescription("Close").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            assertEquals(listOf("Minimize", "Maximize", "Close"), actions)
            onNodeWithContentDescription("Close").performMouseInput { enter() }
            System.getenv("PIXIV_DIALOG_SCREENSHOTS")?.let { directory ->
                val file = File(directory, "caption-$theme.png")
                file.parentFile.mkdirs()
                ImageIO.write(captureToImage().toAwtImage(), "png", file)
            }
        }
    }
}
