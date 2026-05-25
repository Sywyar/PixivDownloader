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
            if (image == null) {
                return Optional.empty();
            }
            OptionalLong dHash = dHash(image);
            if (dHash.isEmpty()) {
                return Optional.empty();
            }
            OptionalLong aHash = aHash(image);
            return Optional.of(new Hashes(dHash.getAsLong(), aHash.isPresent() ? aHash.getAsLong() : null));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public static OptionalLong dHash(BufferedImage image) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return OptionalLong.empty();
        }
        int[][] gray = toGray(image, 9, 8);
        long hash = 0L;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                hash <<= 1;
                if (gray[y][x] > gray[y][x + 1]) {
                    hash |= 1L;
                }
            }
        }
        return OptionalLong.of(hash);
    }

    public static OptionalLong aHash(BufferedImage image) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return OptionalLong.empty();
        }
        int[][] gray = toGray(image, 8, 8);
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
        return OptionalLong.of(hash);
    }

    public static int hamming(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    private static int[][] toGray(BufferedImage source, int width, int height) {
        BufferedImage opaque = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D baseGraphics = opaque.createGraphics();
        try {
            baseGraphics.setColor(Color.WHITE);
            baseGraphics.fillRect(0, 0, opaque.getWidth(), opaque.getHeight());
            baseGraphics.drawImage(source, 0, 0, null);
        } finally {
            baseGraphics.dispose();
        }

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
