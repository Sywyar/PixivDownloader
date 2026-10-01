package top.sywyar.pixivdownload.guicompose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.event.IIOReadProgressListener
import javax.imageio.spi.IIORegistry
import javax.imageio.spi.ImageReaderSpi
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ComposeLocalImageTest {
    @TempDir
    lateinit var directory: Path

    @Test
    @DisplayName("高缩放图片按实际尺寸解码，未变事实复用，尺寸与路径变化及重开才重新读取")
    fun rendersLocalPreviewAfterLayoutAndResizesAtHighDensity() = runComposeUiTest {
        val file = directory.resolve("preview.png")
        ImageIO.write(BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB), "png", file.toFile())
        val second = directory.resolve("second.png")
        ImageIO.write(BufferedImage(800, 1200, BufferedImage.TYPE_INT_RGB), "png", second.toFile())
        var edge by mutableStateOf(160)
        var path by mutableStateOf(file)
        var shown by mutableStateOf(true)
        var revision by mutableStateOf(1L)
        CountingPngReads().use { reads ->
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(2f)) {
                    PixivDownloaderTheme("light") {
                        Box(Modifier.size(edge.dp)) {
                            if (shown) ComposeDesktopUiNodeRenderer.Render(
                                DesktopUiNode.LocalImage("preview", path, DesktopUiNode.TextToken.raw("Local preview"), 980, 700),
                                { it.fallback() },
                                {},
                                documentRevision = revision,
                            )
                        }
                    }
                }
            }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Local preview").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription("Local preview").assertWidthIsEqualTo(160.dp).assertHeightIsEqualTo(160.dp)
            assertEquals(1, reads.count.get())
            runOnIdle { revision++ }
            waitForIdle()
            assertEquals(1, reads.count.get())
            runOnIdle { edge = 240 }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Local preview").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription("Local preview").assertWidthIsEqualTo(240.dp).assertHeightIsEqualTo(240.dp)
            assertEquals(2, reads.count.get())
            runOnIdle { path = second }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Local preview").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(3, reads.count.get())
            runOnIdle { shown = false }
            waitForIdle()
            assertEquals(0, onAllNodesWithContentDescription("Local preview").fetchSemanticsNodes().size)
            assertEquals(3, reads.count.get())
            runOnIdle { shown = true }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Local preview").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(4, reads.count.get())
        }
    }
}

/** 保留真实 PNG 解码，仅通过 ImageIO 标准监听器计数。 */
internal class CountingPngReads : AutoCloseable {
    val count = AtomicInteger()
    private val registry = IIORegistry.getDefaultInstance()
    private val delegate = ImageIO.getImageReadersByFormatName("png").next().let {
        it.originatingProvider.also { _ -> it.dispose() }
    }
    private val provider = object : ImageReaderSpi(
        "test", "1", arrayOf("png"), arrayOf("png"), arrayOf("image/png"),
        delegate.pluginClassName, delegate.inputTypes, null,
        false, null, null, null, null, false, null, null, null, null,
    ) {
        override fun canDecodeInput(source: Any) = delegate.canDecodeInput(source)
        override fun getDescription(locale: Locale?) = "Counted PNG reader"
        override fun createReaderInstance(extension: Any?): ImageReader = delegate.createReaderInstance(extension).apply {
            addIIOReadProgressListener(object : IIOReadProgressListener {
                override fun imageStarted(source: ImageReader, imageIndex: Int) { count.incrementAndGet() }
                override fun imageProgress(source: ImageReader, percentageDone: Float) {}
                override fun imageComplete(source: ImageReader) {}
                override fun sequenceStarted(source: ImageReader, minIndex: Int) {}
                override fun sequenceComplete(source: ImageReader) {}
                override fun thumbnailStarted(source: ImageReader, imageIndex: Int, thumbnailIndex: Int) {}
                override fun thumbnailProgress(source: ImageReader, percentageDone: Float) {}
                override fun thumbnailComplete(source: ImageReader) {}
                override fun readAborted(source: ImageReader) {}
            })
        }
    }

    init {
        registry.registerServiceProvider(provider)
        registry.setOrdering(ImageReaderSpi::class.java, provider, delegate)
    }

    override fun close() { registry.deregisterServiceProvider(provider) }
}
