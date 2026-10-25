package top.sywyar.pixivdownload.gui.imageclassifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Swing 图片分类缩略图")
class ThumbnailManagerTest {
    @TempDir
    Path tempDir;

    @BeforeEach
    void installEnglishHost() {
        DesktopUiHost.UiLocale english = new DesktopUiHost.UiLocale("en-US", "English", "en");
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(), new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("resolveLocale")) {
                        return new DesktopUiHost.UiLocaleResolution(english, List.of(english));
                    }
                    throw new AssertionError("Unexpected host call: " + method.getName());
                });
        SwingHost.install(new DesktopUiContext(false, 6999, ".", tempDir.resolve("config.yaml"), host,
                List.of(), List::of, text -> text.fallback(), () -> "system"));
    }

    @Test
    @DisplayName("替换缩略图请求会取消旧解码并显示当前图片")
    void replacementCancelsPreviousLoad() throws Exception {
        Path file = tempDir.resolve("slow.png");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        SlowFile slow = new SlowFile(file);
        JLabel label = new JLabel();
        CountDownLatch loaded = new CountDownLatch(1);
        label.addPropertyChangeListener("icon", event -> {
            if (event.getNewValue() != null) loaded.countDown();
        });
        ThumbnailManager manager = new ThumbnailManager();
        try {
            SwingUtilities.invokeAndWait(() -> manager.loadThumbnail(slow, label, 8, 8));
            assertTrue(slow.started.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> manager.loadThumbnail(file.toFile(), label, 8, 8));
            assertTrue(slow.interrupted.await(5, TimeUnit.SECONDS));
            assertTrue(loaded.await(5, TimeUnit.SECONDS));
        } finally {
            slow.release.countDown();
            manager.shutdown();
        }
    }

    @Test
    @DisplayName("清空标签撤销等待交付的缓存命中，下一次加载仍可显示")
    void clearRejectsQueuedCacheHit() throws Exception {
        Path file = tempDir.resolve("cached.png");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        JLabel label = new JLabel();
        CountDownLatch loaded = new CountDownLatch(1);
        label.addPropertyChangeListener("icon", event -> {
            if (event.getNewValue() != null) loaded.countDown();
        });
        ThumbnailManager manager = new ThumbnailManager();
        try {
            SwingUtilities.invokeAndWait(() -> manager.loadThumbnail(file.toFile(), label, 8, 8));
            assertTrue(loaded.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                manager.loadThumbnail(file.toFile(), label, 8, 8);
                manager.clearThumbnail(label);
                label.setText("empty");
            });
            SwingUtilities.invokeAndWait(() -> {});
            assertNull(label.getIcon());
            assertEquals("empty", label.getText());
            SwingUtilities.invokeAndWait(() -> manager.loadThumbnail(file.toFile(), label, 8, 8, "animated"));
            SwingUtilities.invokeAndWait(() -> {});
            assertTrue(label.getIcon() != null);
            assertTrue(label.getText().contains("animated"));
        } finally {
            manager.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("换掉预取组或清空缓存会取消仍在读取的旧预取")
    void discardsObsoletePrefetch(boolean clearCache) throws Exception {
        Path file = tempDir.resolve("prefetch.png");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        SlowFile slow = new SlowFile(file);
        ThumbnailManager manager = new ThumbnailManager();
        try {
            manager.prefetch(List.of(slow), 8, 8);
            assertTrue(slow.started.await(5, TimeUnit.SECONDS));
            if (clearCache) manager.clearCache();
            else manager.prefetch(List.of(), 8, 8);
            assertTrue(slow.interrupted.await(5, TimeUnit.SECONDS));
        } finally {
            slow.release.countDown();
            manager.shutdown();
        }
    }

    private static final class SlowFile extends File {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);

        SlowFile(Path path) {
            super(path.toString());
        }

        @Override
        public Path toPath() {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException cancelled) {
                interrupted.countDown();
            }
            return super.toPath();
        }
    }

    @Test
    @DisplayName("解码失败显示错误，重新加载有效图片与缺失图片仍更新标签")
    void failedLoadCanRecover() throws Exception {
        Path file = tempDir.resolve("recover.png");
        Files.writeString(file, "invalid image", java.nio.charset.StandardCharsets.UTF_8);
        JLabel label = new JLabel();
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch loaded = new CountDownLatch(1);
        String failureMessage = GuiMessages.get("gui.image-classifier.thumbnail.load-failed");
        label.addPropertyChangeListener(event -> {
            if ("text".equals(event.getPropertyName()) && failureMessage.equals(event.getNewValue())) failed.countDown();
            if ("icon".equals(event.getPropertyName()) && event.getNewValue() != null) loaded.countDown();
        });
        ThumbnailManager manager = new ThumbnailManager();
        try {
            SwingUtilities.invokeAndWait(() -> manager.loadThumbnail(file.toFile(), label, 8, 8));
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            assertNull(label.getIcon());
            ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
            SwingUtilities.invokeAndWait(() -> manager.loadThumbnail(file.toFile(), label, 8, 8));
            assertTrue(loaded.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> manager.loadThumbnail(null, label, 8, 8));
            SwingUtilities.invokeAndWait(() -> {});
            assertNull(label.getIcon());
            assertEquals(GuiMessages.get("gui.image-classifier.thumbnail.empty"), label.getText());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @DisplayName("关闭缩略图管理器终止加载线程并拒绝缓存和新加载回写")
    void shutdownStopsWorkersAndLateUpdates() throws Exception {
        Path file = tempDir.resolve("loaded.png");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        JLabel label = new JLabel();
        CountDownLatch loaded = new CountDownLatch(1);
        label.addPropertyChangeListener("icon", event -> {
            if (event.getNewValue() != null) loaded.countDown();
        });
        SwingUtilities.invokeAndWait(() -> {});
        Set<Thread> previous = Thread.getAllStackTraces().keySet();
        ThumbnailManager manager = new ThumbnailManager();
        try {
            SwingUtilities.invokeAndWait(() -> manager.loadThumbnail(file.toFile(), label, 8, 8));
            assertTrue(loaded.await(5, TimeUnit.SECONDS));
            List<Thread> workers = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> !previous.contains(thread) && thread.getName().startsWith("pool-"))
                    .toList();
            assertFalse(workers.isEmpty());
            JLabel pending = new JLabel();
            SwingUtilities.invokeAndWait(() -> {
                manager.loadThumbnail(file.toFile(), pending, 8, 8);
                manager.shutdown();
            });
            for (Thread worker : workers) {
                worker.join(2000);
                assertFalse(worker.isAlive());
            }
            JLabel afterClose = new JLabel();
            SwingUtilities.invokeAndWait(() -> {
                manager.loadThumbnail(file.toFile(), afterClose, 8, 8);
                manager.prefetch(List.of(file.toFile()), 8, 8);
            });
            SwingUtilities.invokeAndWait(() -> {});
            assertNull(pending.getIcon());
            assertNull(afterClose.getIcon());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @DisplayName("小图在较大预览区域中保持原尺寸并正常结束缩放")
    void keepsSmallImageSize() throws Exception {
        BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        Path file = tempDir.resolve("small.png");
        ImageIO.write(source, "png", file.toFile());

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            BufferedImage thumbnail = ThumbnailManager.getThumbnail(file.toFile(), 1600, 1600);
            assertEquals(2, thumbnail.getWidth());
            assertEquals(1, thumbnail.getHeight());
            assertEquals(0xffffffff, thumbnail.getRGB(0, 0));
        });
    }
}
