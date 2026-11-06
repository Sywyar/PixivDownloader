package top.sywyar.pixivdownload.guicompose.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.PixivDownloaderTheme
import top.sywyar.pixivdownload.guicompose.experiencePalette
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ToolRunningBorderTest {
    @Test
    @DisplayName("运行线段沿圆角边框移动且长度固定，结束后移除")
    fun runningSegment() = verifyBorder(false)

    @Test
    @DisplayName("减少动态效果时保留静态运行线段，结束后移除")
    fun reducedMotion() = verifyBorder(true)

    @Test
    @DisplayName("高对比度下运行线段仍可辨认并随任务结束撤回")
    fun highContrast() = verifyBorder(false, true)

    private fun verifyBorder(reduced: Boolean, highContrast: Boolean = false) = runSkikoComposeUiTest(
        effectContext = object : MotionDurationScale {
            override val scaleFactor = if (reduced) 0f else 1f
        },
    ) {
        mainClock.autoAdvance = false
        var running by mutableStateOf(true)
        var focused by mutableStateOf(true)
        val window = object : WindowInfo { override val isWindowFocused get() = focused }
        var accent = 0
        var compositions = 0
        setContent {
            CompositionLocalProvider(LocalWindowInfo provides window) {
                PixivDownloaderTheme(if (reduced) "dark" else "light") {
                    CompositionLocalProvider(LocalExperiencePalette provides experiencePalette(reduced, highContrast)) {
                        accent = LocalExperiencePalette.current.link.toArgb()
                        SideEffect { compositions++ }
                        Box(
                            Modifier.size(400.dp, 110.dp)
                                .background(LocalExperiencePalette.current.surface)
                                .toolRunningBorder(running)
                                .testTag("card"),
                        )
                    }
                }
            }
        }
        fun pixels(): Set<Int> {
            val image = onNodeWithTag("card").captureToImage().toAwtImage()
            return buildSet {
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    if (image.getRGB(x, y) == accent) add(y * image.width + x)
                }
            }
        }
        mainClock.advanceTimeBy(100)
        val first = pixels()
        val rendered = compositions
        assertTrue(first.isNotEmpty())
        mainClock.advanceTimeBy(900)
        val next = pixels()
        if (reduced) assertEquals(first, next) else assertNotEquals(first, next)
        assertTrue(kotlin.math.abs(first.size - next.size) < 12, "Moving segment must keep its length")
        assertEquals(rendered, compositions, "Animation must not recompose the card content")
        runOnIdle { focused = false }
        mainClock.advanceTimeByFrame()
        val paused = pixels()
        mainClock.advanceTimeBy(1000)
        assertEquals(paused, pixels())
        runOnIdle { running = false }
        mainClock.advanceTimeByFrame()
        assertTrue(pixels().isEmpty())
    }
}
