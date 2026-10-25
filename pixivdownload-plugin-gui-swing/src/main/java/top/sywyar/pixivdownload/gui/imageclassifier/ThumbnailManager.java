package top.sywyar.pixivdownload.gui.imageclassifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.core.asset.ImageThumbnailScaler;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;

import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Serial;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;

/** 窗口拥有的缩略图缓存与加载任务；标签操作由 EDT 调用。 */
public class ThumbnailManager {
    private static final Logger log = LoggerFactory.getLogger(ThumbnailManager.class);
    private final ThreadPoolExecutor executor;
    private final Map<JLabel, FutureTask<ImageIcon>> loads = new HashMap<>();
    private final Map<String, FutureTask<ImageIcon>> prefetches = new HashMap<>();
    private final Map<String, ImageIcon> cache = new LinkedHashMap<>(200, 0.75f, true) {
        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ImageIcon> eldest) {
            return size() > 400;
        }
    };

    public ThumbnailManager() {
        int threads = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors()));
        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(threads);
    }

    public static BufferedImage getThumbnail(File image, int thumbW, int thumbH) throws IOException {
        return ImageThumbnailScaler.scale(image.toPath(), thumbW, thumbH);
    }

    public void loadThumbnail(File imageFile, JLabel targetLabel, int thumbW, int thumbH) {
        loadThumbnail(imageFile, targetLabel, thumbW, thumbH, null);
    }

    public synchronized void loadThumbnail(File imageFile, JLabel targetLabel, int thumbW, int thumbH, String badgeText) {
        if (executor.isShutdown()) return;
        cancelLoad(targetLabel);
        // WebP 动图继续读取伴随的静态缩略图。
        if (imageFile != null && imageFile.getName().toLowerCase().endsWith(".webp")) {
            String base = imageFile.getName().substring(0, imageFile.getName().lastIndexOf('.'));
            File thumb = new File(imageFile.getParent(), base + "_thumb.jpg");
            if (thumb.exists()) imageFile = thumb;
        }
        File source = imageFile;
        String key = source == null || !source.exists() ? null : source.getAbsolutePath();
        ImageIcon cached = key == null ? null : cache.get(key);
        FutureTask<ImageIcon> task = new FutureTask<>(
                () -> key == null ? null : cached != null ? cached : new ImageIcon(getThumbnail(source, thumbW, thumbH))
        ) {
            @Override
            protected void done() {
                SwingUtilities.invokeLater(() -> {
                    synchronized (ThumbnailManager.this) {
                        if (!loads.remove(targetLabel, this) || isCancelled() || executor.isShutdown()) return;
                        try {
                            ImageIcon icon = get();
                            if (icon != null) cache.put(key, icon);
                            targetLabel.setIcon(icon);
                            if (icon == null) targetLabel.setText(message("gui.image-classifier.thumbnail.empty"));
                            else applyBadge(targetLabel, badgeText);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        } catch (ExecutionException failure) {
                            if (failure.getCause() instanceof Error fatal) throw fatal;
                            log.error("Failed to load thumbnail for {}", source, failure.getCause());
                            targetLabel.setIcon(null);
                            targetLabel.setText(message("gui.image-classifier.thumbnail.load-failed"));
                        }
                    }
                });
            }
        };
        loads.put(targetLabel, task);
        if (key == null || cached != null) {
            task.run();
        } else {
            targetLabel.setIcon(null);
            targetLabel.setText(message("gui.image-classifier.thumbnail.loading"));
            executor.execute(task);
        }
    }

    synchronized void clearThumbnail(JLabel label) {
        cancelLoad(label);
        label.setIcon(null);
    }

    private void cancelLoad(JLabel label) {
        FutureTask<ImageIcon> task = loads.remove(label);
        if (task != null) cancel(task);
    }

    private void cancel(FutureTask<ImageIcon> task) {
        task.cancel(true);
        // ImageIO 可以不响应中断；已排队的旧任务仍应立即移除。
        executor.remove(task);
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

    /** 保留下一组预取；换组撤销已不需要的任务，已完成图片仍由 LRU 缓存管理。 */
    public synchronized void prefetch(List<File> files, int thumbW, int thumbH) {
        if (executor.isShutdown()) return;
        Set<String> wanted = new HashSet<>();
        if (files != null) for (File file : files) wanted.add(file.getAbsolutePath());
        for (String key : List.copyOf(prefetches.keySet())) {
            if (!wanted.contains(key)) cancel(prefetches.remove(key));
        }
        if (files == null) return;
        for (File file : files) {
            String key = file.getAbsolutePath();
            if (cache.containsKey(key) || prefetches.containsKey(key)) continue;
            FutureTask<ImageIcon> task = new FutureTask<>(() -> new ImageIcon(getThumbnail(file, thumbW, thumbH))) {
                @Override
                protected void done() {
                    synchronized (ThumbnailManager.this) {
                        if (!prefetches.remove(key, this) || isCancelled() || executor.isShutdown()) return;
                        try {
                            cache.put(key, get());
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        } catch (ExecutionException failure) {
                            if (failure.getCause() instanceof Error fatal) throw fatal;
                        }
                    }
                }
            };
            prefetches.put(key, task);
            executor.execute(task);
        }
    }

    public synchronized void clearCache() {
        for (JLabel label : List.copyOf(loads.keySet())) cancelLoad(label);
        List<FutureTask<ImageIcon>> pending = List.copyOf(prefetches.values());
        prefetches.clear();
        pending.forEach(this::cancel);
        cache.clear();
    }

    public synchronized void shutdown() {
        executor.shutdownNow();
        clearCache();
    }

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }
}
