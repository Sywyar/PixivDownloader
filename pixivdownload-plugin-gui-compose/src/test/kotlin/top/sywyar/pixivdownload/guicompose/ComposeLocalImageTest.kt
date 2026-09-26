package top.sywyar.pixivdownload.guicompose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class ComposeLocalImageTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun rendersLocalPreviewAfterLayoutAndResizesAtHighDensity() = runComposeUiTest {
        val file = directory.resolve("preview.png")
        ImageIO.write(BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB), "png", file.toFile())
        var edge by mutableStateOf(160)
        val node = DesktopUiNode.LocalImage("preview", file, DesktopUiNode.TextToken.raw("Local preview"), 980, 700)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                MaterialTheme {
                    Box(Modifier.size(edge.dp)) {
                        ComposeDesktopUiNodeRenderer.Render(node, { it.fallback() }, {})
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Local preview").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Local preview").assertWidthIsEqualTo(160.dp).assertHeightIsEqualTo(160.dp)
        runOnIdle { edge = 240 }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Local preview").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Local preview").assertWidthIsEqualTo(240.dp).assertHeightIsEqualTo(240.dp)
    }
}
