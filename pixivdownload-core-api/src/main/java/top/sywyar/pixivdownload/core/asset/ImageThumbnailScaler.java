package top.sywyar.pixivdownload.core.asset;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/** Bounded image decoding and aspect-preserving high-quality thumbnail scaling. */
public final class ImageThumbnailScaler {
    private ImageThumbnailScaler() {}

    /**
     * Decodes and scales an image without changing its aspect ratio.
     *
     * @param source source image path
     * @param maximumWidth maximum output width, or a negative value for one-third source width
     * @param maximumHeight maximum output height, or a negative value for one-third source height
     * @return opaque scaled thumbnail
     * @throws IOException when the source cannot be decoded safely
     */
    public static BufferedImage scale(Path source, int maximumWidth, int maximumHeight) throws IOException {
        BufferedImage image = BoundedImageDecoder.read(source);
        if (image == null) throw new IOException("Cannot decode image: " + source);
        int width = maximumWidth < 0 ? image.getWidth() / 3 : maximumWidth;
        int height = maximumHeight < 0 ? image.getHeight() / 3 : maximumHeight;
        double ratio = Math.min((double) width / image.getWidth(), (double) height / image.getHeight());
        return scale(image, Math.max(1, (int) Math.round(image.getWidth() * ratio)),
                Math.max(1, (int) Math.round(image.getHeight() * ratio)));
    }

    private static BufferedImage scale(BufferedImage source, int targetWidth, int targetHeight) {
        int width = source.getWidth();
        int height = source.getHeight();
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage image = source;
        while (width != targetWidth || height != targetHeight) {
            int nextWidth = width > targetWidth ? Math.max(width / 2, targetWidth) : width;
            int nextHeight = height > targetHeight ? Math.max(height / 2, targetHeight) : height;
            BufferedImage next = new BufferedImage(nextWidth, nextHeight, type);
            Graphics2D graphics = next.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(image, 0, 0, nextWidth, nextHeight, 0, 0, width, height, null);
            graphics.dispose();
            image = next;
            width = nextWidth;
            height = nextHeight;
        }
        if (type != BufferedImage.TYPE_INT_ARGB) return image;
        BufferedImage opaque = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = opaque.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, targetWidth, targetHeight);
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return opaque;
    }
}
