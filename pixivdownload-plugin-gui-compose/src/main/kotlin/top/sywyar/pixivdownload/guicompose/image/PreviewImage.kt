package top.sywyar.pixivdownload.guicompose.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode

@Composable
internal fun rememberPreviewImage(image: DesktopUiNode.ImageData) =
    remember(image) { runCatching { PreviewImage(image.bytes()) }.getOrNull() }?.image

private class PreviewImage(encoded: ByteArray) : RememberObserver {
    private val pixels = Image.makeFromEncoded(encoded).use { source ->
        val bitmap = Bitmap()
        try {
            check(bitmap.allocPixels(ImageInfo.makeN32(source.width, source.height, ColorAlphaType.PREMUL)))
            // 保持 Compose 的像素转换；临时画布也持有像素，必须在转换结束时关闭。
            Canvas(bitmap).use { it.drawImage(source, 0f, 0f) }
            bitmap.setImmutable()
        } catch (failure: Throwable) {
            bitmap.close()
            throw failure
        }
    }
    val image = try {
        pixels.asComposeImageBitmap()
    } catch (failure: Throwable) {
        pixels.close()
        throw failure
    }

    override fun onRemembered() = Unit
    override fun onForgotten() = pixels.close()
    override fun onAbandoned() = pixels.close()
}
