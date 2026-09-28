package top.sywyar.pixivdownload.core.hash;

import top.sywyar.pixivdownload.core.asset.BoundedImageDecoder;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;

public final class ImageHasher {

    private static final int OPAQUE_TILE_PIXELS = 256 * 1024;

    private ImageHasher() {
    }

    public record Hashes(long dHash, Long aHash) {
    }

    public static Optional<Hashes> hash(Path imagePath) {
        if (imagePath == null) {
            return Optional.empty();
        }
        try {
            return hashDecodedImage(BoundedImageDecoder.read(imagePath));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 消费本次解码独占的图像；允许原地铺白，调用方不得再复用其透明像素。 */
    static Optional<Hashes> hashDecodedImage(BufferedImage image) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return Optional.empty();
        }
        BufferedImage opaque;
        switch (image.getType()) {
            case BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE,
                    BufferedImage.TYPE_4BYTE_ABGR, BufferedImage.TYPE_4BYTE_ABGR_PRE -> {
                // 在已有像素后铺白，保留先合成、后双线性采样的顺序和八位舍入。
                Graphics2D graphics = image.createGraphics();
                try {
                    graphics.setComposite(AlphaComposite.DstOver);
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                } finally {
                    graphics.dispose();
                }
                opaque = image;
            }
            default -> opaque = toOpaque(image);
        }
        long dHash = dHashFromGray(scaleToGraySamples(opaque, 9, 8));
        long aHash = aHashFromGray(scaleToGraySamples(opaque, 8, 8));
        return Optional.of(new Hashes(dHash, aHash));
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

    /** 复用可直接采样的不透明图像，其它格式保持白底 RGB 转换，且不修改调用方的像素。 */
    private static BufferedImage toOpaque(BufferedImage source) {
        switch (source.getType()) {
            case BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_BGR,
                    BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_BYTE_GRAY -> {
                return source;
            }
            default -> { }
        }
        BufferedImage opaque = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D baseGraphics = opaque.createGraphics();
        try {
            baseGraphics.setColor(Color.WHITE);
            baseGraphics.fillRect(0, 0, opaque.getWidth(), opaque.getHeight());
            // Java2D 可能为透明源创建额外的 ARGB 转换缓冲；分条带合成把它限制在约 1 MiB。
            int rows = Math.max(1, OPAQUE_TILE_PIXELS / source.getWidth());
            for (int y = 0; y < source.getHeight(); y += rows) {
                int bottom = Math.min(source.getHeight(), y + rows);
                baseGraphics.drawImage(source, 0, y, source.getWidth(), bottom,
                        0, y, source.getWidth(), bottom, null);
            }
        } finally {
            baseGraphics.dispose();
        }
        return opaque;
    }

    /** 把不透明图像双线性缩放到 width×height 的灰度图，并返回逐像素灰度采样。 */
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
