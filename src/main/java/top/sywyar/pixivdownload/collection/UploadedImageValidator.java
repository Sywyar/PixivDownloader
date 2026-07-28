package top.sywyar.pixivdownload.collection;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

final class UploadedImageValidator {

    private static final Set<String> SUPPORTED_FORMATS = Set.of("png", "jpg", "webp");
    private static final int MAX_WIDTH = 2048;
    private static final int MAX_HEIGHT = 2048;
    private static final long MAX_PIXELS = 4_194_304L;
    private static final int PNG_SIGNATURE_LENGTH = 8;
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
    };

    static {
        ImageIO.scanForPlugins();
    }

    private UploadedImageValidator() {
    }

    static ValidatedImage validate(byte[] data) throws IOException {
        if (data == null || data.length == 0 || isAnimatedPng(data) || isAnimatedWebp(data)) {
            return null;
        }
        try (MemoryCacheImageInputStream input =
                     new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                String format = normalizeFormat(reader.getFormatName());
                if (!SUPPORTED_FORMATS.contains(format)) {
                    return null;
                }
                if (imageCount(reader) > 1) {
                    return null;
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (!dimensionsAllowed(width, height)) {
                    return null;
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null || !dimensionsAllowed(decoded.getWidth(), decoded.getHeight())) {
                    return null;
                }
                return new ValidatedImage("png", rewriteAsPng(decoded));
            } catch (RuntimeException | IOException e) {
                return null;
            } finally {
                reader.dispose();
            }
        }
    }

    private static boolean dimensionsAllowed(int width, int height) {
        return width > 0
                && height > 0
                && width <= MAX_WIDTH
                && height <= MAX_HEIGHT
                && (long) width * height <= MAX_PIXELS;
    }

    private static int imageCount(ImageReader reader) throws IOException {
        try {
            int count = reader.getNumImages(true);
            return count < 0 ? 1 : count;
        } catch (UnsupportedOperationException ignored) {
            return 1;
        }
    }

    private static byte[] rewriteAsPng(BufferedImage decoded) throws IOException {
        BufferedImage clean = new BufferedImage(
                decoded.getWidth(),
                decoded.getHeight(),
                BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = clean.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(decoded, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(clean, "png", out)) {
            throw new IOException("PNG writer is unavailable");
        }
        return out.toByteArray();
    }

    private static String normalizeFormat(String format) {
        if (format == null) {
            return "";
        }
        String normalized = format.toLowerCase(Locale.ROOT);
        if ("jpeg".equals(normalized)) {
            return "jpg";
        }
        return normalized;
    }

    private static boolean isAnimatedPng(byte[] data) {
        if (data.length < PNG_SIGNATURE_LENGTH || !startsWith(data, PNG_SIGNATURE)) {
            return false;
        }
        int pos = PNG_SIGNATURE_LENGTH;
        while (pos + 12 <= data.length) {
            long length = readBEUInt(data, pos);
            long chunkEnd = pos + 12L + length;
            if (length < 0 || chunkEnd > data.length) {
                return false;
            }
            if (data[pos + 4] == 'a' && data[pos + 5] == 'c'
                    && data[pos + 6] == 'T' && data[pos + 7] == 'L') {
                return true;
            }
            pos = (int) chunkEnd;
        }
        return false;
    }

    private static boolean isAnimatedWebp(byte[] data) {
        if (data.length < 20 || !fourCc(data, 0, "RIFF") || !fourCc(data, 8, "WEBP")) {
            return false;
        }
        long riffSize = readLEUInt(data, 4);
        long end = riffSize + 8L;
        if (end > data.length || end < 20) {
            return false;
        }
        int pos = 12;
        while (pos + 8 <= end) {
            long chunkSize = readLEUInt(data, pos + 4);
            long body = pos + 8L;
            long next = body + chunkSize + (chunkSize & 1L);
            if (next > end) {
                return false;
            }
            if (fourCc(data, pos, "ANIM") || fourCc(data, pos, "ANMF")) {
                return true;
            }
            if (fourCc(data, pos, "VP8X") && chunkSize >= 1 && (data[(int) body] & 0x02) != 0) {
                return true;
            }
            pos = (int) next;
        }
        return false;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean fourCc(byte[] data, int offset, String value) {
        return offset + 4 <= data.length
                && data[offset] == value.charAt(0)
                && data[offset + 1] == value.charAt(1)
                && data[offset + 2] == value.charAt(2)
                && data[offset + 3] == value.charAt(3);
    }

    private static long readBEUInt(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24)
                | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8)
                | (b[off + 3] & 0xFFL);
    }

    private static long readLEUInt(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }

    record ValidatedImage(String extension, byte[] storageBytes) {
    }
}
