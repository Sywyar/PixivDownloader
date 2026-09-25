package top.sywyar.pixivdownload.core.asset;

import top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/** 在分配像素缓冲区前校验源文件，并对大图降采样。 */
public final class BoundedImageDecoder {

    static final int MAX_WIDTH = 25_000;
    static final int MAX_HEIGHT = 25_000;
    static final long MAX_PIXELS = 25_000_000L;
    static final long MAX_SOURCE_BYTES = PixivImageTransferObserver.MAX_IMAGE_BYTES;

    private BoundedImageDecoder() {
    }

    /**
     * 在源字节数、边长和解码像素预算内读取图片；大尺寸 PNG / JPEG 返回降采样结果。
     *
     * @param path 图片文件路径
     * @return 解码后的图片；路径不存在、文件为空或格式不受支持时返回 {@code null}
     * @throws IOException 源文件超限或无法安全解码时抛出
     */
    public static BufferedImage read(Path path) throws IOException {
        Decoded decoded = read(path, MAX_WIDTH, MAX_HEIGHT);
        return decoded == null ? null : decoded.image();
    }

    static Decoded read(Path path, int maximumWidth, int maximumHeight) throws IOException {
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
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);
                String format = reader.getFormatName();
                // GIF 等读取器即使设置降采样，也可能先分配完整源帧。
                if (!"png".equalsIgnoreCase(format) && !"jpeg".equalsIgnoreCase(format)) {
                    validatePixels(width, height);
                }
                int boundWidth = maximumWidth < 0 ? Math.max(1, width / 3) : Math.max(1, maximumWidth);
                int boundHeight = maximumHeight < 0 ? Math.max(1, height / 3) : Math.max(1, maximumHeight);
                double ratio = Math.min(1d, Math.min((double) boundWidth / width, (double) boundHeight / height));
                int targetWidth = Math.max(1, (int) Math.round(width * ratio));
                int targetHeight = Math.max(1, (int) Math.round(height * ratio));
                int sampling = Math.max(1, Math.min(width / targetWidth, height / targetHeight));
                while ((long) ((width + sampling - 1) / sampling)
                        * ((height + sampling - 1) / sampling) > MAX_PIXELS) {
                    sampling++;
                }
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(sampling, sampling, 0, 0);
                BufferedImage decoded = reader.read(0, param);
                if (decoded != null) {
                    validateDimensions(decoded.getWidth(), decoded.getHeight());
                    validatePixels(decoded.getWidth(), decoded.getHeight());
                    return new Decoded(decoded, Math.min(targetWidth, decoded.getWidth()),
                            Math.min(targetHeight, decoded.getHeight()));
                }
                return null;
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
    }

    private static void validatePixels(int width, int height) throws IOException {
        long pixels = (long) width * height;
        if (pixels > MAX_PIXELS) {
            throw new IOException("Image pixel count limit exceeded: " + pixels);
        }
    }

    record Decoded(BufferedImage image, int targetWidth, int targetHeight) {}
}
