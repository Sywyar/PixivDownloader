package top.sywyar.pixivdownload.imageclassifier;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Helper class to asynchronously load, scale and cache thumbnails for Swing JLabels.
 * How to integrate (summary):
 * - Create a single ThumbnailManager instance in your main window/class.
 * - Replace direct ImageIcon creation in updateThumbnails() with calls to
 * thumbnailManager.loadThumbnail(imageFile, thumbnailLabels[i], thumbW, thumbH);
 * - Optionally call thumbnailManager.prefetch(nextGroupFiles, thumbW, thumbH) to warm the cache.
 * - Call thumbnailManager.shutdown() when your application/window is closing.
 */
public class ThumbnailManager {

    private final ExecutorService executor;

    // LRU cache for thumbnails. Adjust max size to fit memory budget.
    private final Map<String, ImageIcon> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(200, 0.75f, true) {
                @Serial
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ImageIcon> eldest) {
                    return size() > 400; // keep up to 400 thumbnails in memory (tweak as needed)
                }
            }
    );

    public ThumbnailManager() {
        // Use a small pool so we don't saturate the disk/CPU with too many parallel reads.
        int threads = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors()));
        this.executor = Executors.newFixedThreadPool(threads);
    }

    private static BufferedImage highQualityScale(BufferedImage src, int targetW, int targetH) {
        int w = src.getWidth();
        int h = src.getHeight();

        // 根据原图是否带透明决定目标类型
        int imageType = src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage img = src;

        // 多步缩放保持质量
        while (w != targetW || h != targetH) {
            int nextW = w;
            int nextH = h;

            if (w > targetW) {
                nextW = Math.max(w / 2, targetW);
            }
            if (h > targetH) {
                nextH = Math.max(h / 2, targetH);
            }

            // 注意这里防止宽高被错误拉伸
            BufferedImage tmp = new BufferedImage(nextW, nextH, imageType);
            Graphics2D g2 = tmp.createGraphics();

            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // 关键：用 drawImage 的四参数版本，防止拉伸时空白
            g2.drawImage(img, 0, 0, nextW, nextH, 0, 0, w, h, null);
            g2.dispose();

            img = tmp;
            w = nextW;
            h = nextH;
        }

        // 转成与保存格式兼容的类型（JPG不支持透明）
        BufferedImage finalImg;
        if (imageType == BufferedImage.TYPE_INT_ARGB) {
            finalImg = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = finalImg.createGraphics();
            g.setColor(Color.WHITE); // 背景填充白色
            g.fillRect(0, 0, targetW, targetH);
            g.drawImage(img, 0, 0, null);
            g.dispose();
        } else {
            finalImg = img;
        }

        return finalImg;
    }


    public static BufferedImage getThumbnail(File image, int thumbW, int thumbH) throws IOException {
        BufferedImage src = ImageIO.read(image);

        if (thumbW == -1) {
            thumbW = src.getWidth() / 3;
        }

        if (thumbH == -1) {
            thumbH = src.getHeight() / 3;
        }

        if (src == null) throw new IOException("无法解码图片: " + image);

        int[] sized = fitTo(src.getWidth(), src.getHeight(), thumbW, thumbH);
        int w = sized[0];
        int h = sized[1];

        // High-quality scaling using Graphics2D (faster and better control than getScaledInstance)
        return highQualityScale(src, w, h);
    }

    /**
     * Asynchronously load a thumbnail and set it on the given JLabel when ready.
     * If a cached thumbnail exists it is used immediately.
     */
    public void loadThumbnail(File imageFile, JLabel targetLabel, int thumbW, int thumbH) {
        loadThumbnail(imageFile, targetLabel, thumbW, thumbH, null);
    }

    public void loadThumbnail(File imageFile, JLabel targetLabel, int thumbW, int thumbH, String badgeText) {
        // WebP 动图：使用伴随的 _thumb.jpg 文件供 ImageIO 解码
        if (imageFile != null && imageFile.getName().toLowerCase().endsWith(".webp")) {
            String base = imageFile.getName().substring(0, imageFile.getName().lastIndexOf('.'));
            File thumb = new File(imageFile.getParent(), base + "_thumb.jpg");
            if (thumb.exists()) imageFile = thumb;
        }

        if (imageFile == null || !imageFile.exists()) {
            SwingUtilities.invokeLater(() -> {
                targetLabel.setIcon(null);
                targetLabel.setText("无图片");
            });
            return;
        }

        final String key = imageFile.getAbsolutePath();
        ImageIcon cached = cache.get(key);
        if (cached != null) {
            SwingUtilities.invokeLater(() -> {
                targetLabel.setIcon(cached);
                applyBadge(targetLabel, badgeText);
            });
            return;
        }

        // set a lightweight placeholder immediately (so UI stays responsive)
        SwingUtilities.invokeLater(() -> {
            targetLabel.setIcon(null);
            targetLabel.setText("加载中...");
        });

        // Submit a background task to read+scale the image and update the label
        File finalImageFile = imageFile;
        executor.submit(() -> {
            try {
                BufferedImage dst = getThumbnail(finalImageFile, thumbW, thumbH);
                ImageIcon icon = new ImageIcon(dst);
                cache.put(key, icon);

                // Update label on EDT
                SwingUtilities.invokeLater(() -> {
                    targetLabel.setIcon(icon);
                    applyBadge(targetLabel, badgeText);
                });
            } catch (Exception e) {
                // on error show a small message instead of freezing the UI
                SwingUtilities.invokeLater(() -> {
                    targetLabel.setIcon(null);
                    targetLabel.setText("加载失败");
                });
            }
        });
    }

    private static void applyBadge(JLabel label, String badgeText) {
        if (badgeText != null && !badgeText.isEmpty()) {
            label.setText("<html><center><font color='#FF6600'><b>" + badgeText + "</b></font></center></html>");
            label.setVerticalTextPosition(SwingConstants.BOTTOM);
            label.setHorizontalTextPosition(SwingConstants.CENTER);
        } else {
            label.setText(null);
        }
    }

    /**
     * Prefetch a list of files into the cache. This doesn't touch the UI; useful for warming the
     * cache for the "next" or "previous" group so navigation feels instant.
     */
    public void prefetch(List<File> files, int thumbW, int thumbH) {
        if (files == null) return;
        for (File f : files) {
            final String key = f.getAbsolutePath();
            if (cache.containsKey(key)) continue;
            executor.submit(() -> {
                try {
                    BufferedImage src = ImageIO.read(f);
                    if (src == null) return;
                    int[] sized = fitTo(src.getWidth(), src.getHeight(), thumbW, thumbH);
                    BufferedImage dst = new BufferedImage(sized[0], sized[1], BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = dst.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    g.drawImage(src, 0, 0, sized[0], sized[1], null);
                    g.dispose();
                    cache.put(key, new ImageIcon(dst));
                } catch (IOException ignored) {
                }
            });
        }
    }

    /**
     * Clear cache (optional).
     */
    public void clearCache() {
        cache.clear();
    }

    /**
     * Shutdown the background loader. Call this when your window closes.
     */
    public void shutdown() {
        executor.shutdownNow();
    }

    private static int[] fitTo(int srcW, int srcH, int maxW, int maxH) {
        double wr = (double) maxW / srcW;
        double hr = (double) maxH / srcH;
        double r = Math.min(wr, hr);
        int w = (int) Math.max(1, Math.round(srcW * r));
        int h = (int) Math.max(1, Math.round(srcH * r));
        return new int[]{w, h};
    }
}
