package top.sywyar.pixivdownload.guicompose.image

import androidx.compose.foundation.Image
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image as SkiaImage
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@DisplayName("桌面预览图像资源生命周期")
class PreviewImageTest {
    @Test
    @DisplayName("换图与离开组合立即关闭位图，重复使用同一图片保持像素与实例")
    fun releasesPixelsWhenReplacedOrRemoved() = runComposeUiTest {
        val original = imageData(0x8040a0ff.toInt())
        val selected = mutableStateOf<DesktopUiNode.ImageData?>(original)
        var displayed: ImageBitmap? = null
        setContent {
            displayed = selected.value?.let { rememberPreviewImage(it) }
            displayed?.let { Image(it, "Preview") }
        }
        waitForIdle()
        val first = checkNotNull(displayed)
        SkiaImage.makeFromEncoded(original.bytes()).use { source ->
            val reference = source.toComposeImageBitmap()
            try {
                assertEquals(reference.width, first.width)
                assertEquals(reference.height, first.height)
                for (y in 0 until first.height) for (x in 0 until first.width) {
                    assertEquals(reference.asSkiaBitmap().getColor(x, y), first.asSkiaBitmap().getColor(x, y))
                }
            } finally {
                reference.asSkiaBitmap().close()
            }
        }
        runOnIdle { selected.value = DesktopUiNode.ImageData("image/png", original.bytes()) }
        waitForIdle()
        assertSame(first, displayed)
        repeat(12) { index ->
            val previous = checkNotNull(displayed).asSkiaBitmap()
            runOnIdle { selected.value = imageData(0xff000000.toInt() or index) }
            waitForIdle()
            assertTrue(previous.isClosed)
            assertFalse(checkNotNull(displayed).asSkiaBitmap().isClosed)
        }
        val last = checkNotNull(displayed).asSkiaBitmap()
        runOnIdle { selected.value = null }
        waitForIdle()
        assertNull(displayed)
        assertTrue(last.isClosed)
    }

    @Test
    @DisplayName("组合中止时释放已解码位图，损坏图像不产生显示资源")
    fun releasesPixelsWhenCompositionIsAbandoned() = runBlocking {
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        var decoded: ImageBitmap? = null
        try {
            assertFailsWith<IllegalStateException> {
                composition.setContent {
                    assertNull(rememberPreviewImage(DesktopUiNode.ImageData("image/png", byteArrayOf(1, 2, 3))))
                    decoded = rememberPreviewImage(imageData(0xff102030.toInt()))
                    error("Abort composition")
                }
            }
            assertTrue(checkNotNull(decoded).asSkiaBitmap().isClosed)
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
    }

    private fun imageData(color: Int): DesktopUiNode.ImageData {
        val image = BufferedImage(32, 24, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, color xor (x shl 8))
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            DesktopUiNode.ImageData("image/png", output.toByteArray())
        }
    }
}
