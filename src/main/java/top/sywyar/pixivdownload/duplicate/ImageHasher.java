package top.sywyar.pixivdownload.duplicate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;

public final class ImageHasher {

    private ImageHasher() {
    }

    public record Hashes(long dHash, Long aHash) {
    }

    public static Optional<Hashes> hash(Path imagePath) {
        if (imagePath == null) {
            return Optional.empty();
        }
        try {
            BufferedImage image = ImageIO.read(imagePath.toFile());
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return Optional.empty();
            }
            // 全尺寸铺白副本只构建一次，dHash / aHash 共用它各自缩放，避免对大图重复分配整张画布。
            // 缩放与采样步骤保持不变，因此与分别调用 dHash(image)/aHash(image) 的结果 bit 级一致。
            BufferedImage opaque = toOpaque(image);
            long dHash = dHashFromGray(scaleToGraySamples(opaque, 9, 8));
            long aHash = aHashFromGray(scaleToGraySamples(opaque, 8, 8));
            return Optional.of(new Hashes(dHash, aHash));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public static OptionalLong dHash(BufferedImage image) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(dHashFromGray(toGray(image, 9, 8)));
    }

    public static OptionalLong aHash(BufferedImage image) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(aHashFromGray(toGray(image, 8, 8)));
    }

    private static long dHashFromGray(int[][] gray) {
        long hash = 0L;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                hash <<= 1;
                if (gray[y][x] > gray[y][x + 1]) {
                    hash |= 1L;
                }
            }
        }
        return hash;
    }

    private static long aHashFromGray(int[][] gray) {
        int sum = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                sum += gray[y][x];
            }
        }
        double average = sum / 64.0;
        long hash = 0L;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                hash <<= 1;
                if (gray[y][x] >= average) {
                    hash |= 1L;
                }
            }
        }
        return hash;
    }

    public static int hamming(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    private static int[][] toGray(BufferedImage source, int width, int height) {
        return scaleToGraySamples(toOpaque(source), width, height);
    }

    /** 把可能带透明通道的原图铺到白底上，得到与原图同尺寸的不透明 RGB 副本。 */
    private static BufferedImage toOpaque(BufferedImage source) {
        BufferedImage opaque = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D baseGraphics = opaque.createGraphics();
        try {
            baseGraphics.setColor(Color.WHITE);
            baseGraphics.fillRect(0, 0, opaque.getWidth(), opaque.getHeight());
            baseGraphics.drawImage(source, 0, 0, null);
        } finally {
            baseGraphics.dispose();
        }
        return opaque;
    }

    /** 把不透明副本双线性缩放到 width×height 的灰度图，并返回逐像素灰度采样。 */
    private static int[][] scaleToGraySamples(BufferedImage opaque, int width, int height) {
        BufferedImage gray = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = gray.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(opaque, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }

        int[][] samples = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                samples[y][x] = gray.getRaster().getSample(x, y, 0);
            }
        }
        return samples;
    }
}
