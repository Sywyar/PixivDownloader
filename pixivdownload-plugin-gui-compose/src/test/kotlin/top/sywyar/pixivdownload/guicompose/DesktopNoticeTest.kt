package top.sywyar.pixivdownload.guicompose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextStyle
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopNoticeTest {
    @Test
    @DisplayName("提示在浅深色与高对比度主题中保留语义颜色和可访问错误")
    fun semanticFeedbackAcrossThemes() {
        for (dark in listOf(false, true)) for (highContrast in listOf(false, true)) {
            runComposeUiTest {
                val palette = experiencePalette(dark, highContrast)
                val colors = mapOf(TextStyle.BODY to palette.link, TextStyle.SUCCESS to palette.success,
                    TextStyle.WARNING to palette.warning, TextStyle.ERROR to palette.error)
                setContent {
                    PixivDownloaderTheme(if (dark) "dark" else "light") {
                        CompositionLocalProvider(LocalExperiencePalette provides palette) {
                            Column(Modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                colors.keys.forEach { style ->
                                    DesktopNotice(
                                        "Fixture feedback: ${style.name}. Details remain readable on another line.",
                                        style,
                                        Modifier.testTag(style.name),
                                    )
                                }
                            }
                        }
                    }
                }
                colors.forEach { (style, color) ->
                    val notice = onNodeWithTag(style.name)
                    notice.assertIsDisplayed()
                    if (style == TextStyle.ERROR) notice.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
                    else notice.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
                    val pixels = notice.captureToImage().toPixelMap()
                    assertTrue((0 until pixels.width).any { x ->
                        (0 until pixels.height).any { y -> pixels[x, y] == color }
                    }, "Missing semantic color for $style, dark=$dark, highContrast=$highContrast")
                }
            }
        }
    }
}
