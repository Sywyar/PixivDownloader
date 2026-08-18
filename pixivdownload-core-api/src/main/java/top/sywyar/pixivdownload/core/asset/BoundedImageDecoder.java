package top.sywyar.pixivdownload.core.asset;

import top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/** 在分配像素缓冲区前校验本地图片的文件大小与解码尺寸。 */
public final class BoundedImageDecoder {

    static final int MAX_WIDTH = 25_000;
    static final int MAX_HEIGHT = 25_000;
    static final long MAX_PIXELS = 25_000_000L;
    static final long MAX_SOURCE_BYTES = PixivImageTransferObserver.MAX_IMAGE_BYTES;

    private BoundedImageDecoder() {
    }

    /**
     * Decodes a regular image file after enforcing source-byte and pixel-count limits.
     *
     * @param path image file path
     * @return decoded image, or {@code null} when the path is absent, empty, or unsupported
     * @throws IOException when the source exceeds a limit or cannot be decoded safely
     */
    public static BufferedImage read(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        long sourceBytes = Files.size(path);
        if (sourceBytes == 0) {
            return null;
        }
        if (sourceBytes > MAX_SOURCE_BYTES) {
            throw new IOException("Image source byte limit exceeded: " + sourceBytes);
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                validateDimensions(reader.getWidth(0), reader.getHeight(0));
                BufferedImage decoded = reader.read(0);
                if (decoded != null) {
                    validateDimensions(decoded.getWidth(), decoded.getHeight());
                }
                return decoded;
            } catch (RuntimeException e) {
                throw new IOException("Cannot decode image: " + path, e);
            } finally {
                reader.dispose();
            }
        }
    }

    private static void validateDimensions(int width, int height) throws IOException {
        if (width <= 0 || width > MAX_WIDTH) {
            throw new IOException("Image width limit exceeded: " + width);
        }
        if (height <= 0 || height > MAX_HEIGHT) {
            throw new IOException("Image height limit exceeded: " + height);
        }
        long pixels = (long) width * height;
        if (pixels > MAX_PIXELS) {
            throw new IOException("Image pixel count limit exceeded: " + pixels);
        }
    }
}
