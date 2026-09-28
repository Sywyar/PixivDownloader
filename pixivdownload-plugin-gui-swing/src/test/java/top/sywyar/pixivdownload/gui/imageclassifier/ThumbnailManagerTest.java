package top.sywyar.pixivdownload.gui.imageclassifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
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

    @Test
    @DisplayName("关闭缩略图管理器终止加载线程并拒绝缓存和新加载回写")
    void shutdownStopsWorkersAndLateUpdates() throws Exception {
        DesktopUiHost.UiLocale english = new DesktopUiHost.UiLocale("en-US", "English", "_en");
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
    void keepsSmallImageSizeForBothThumbnailEntrypoints() throws Exception {
        BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        Path file = tempDir.resolve("small.png");
        ImageIO.write(source, "png", file.toFile());

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (BufferedImage thumbnail : new BufferedImage[]{
                    ThumbnailManager.getThumbnail(source, 1600, 1600),
                    ThumbnailManager.getThumbnail(file.toFile(), 1600, 1600)}) {
                assertEquals(2, thumbnail.getWidth());
                assertEquals(1, thumbnail.getHeight());
                assertEquals(0xffffffff, thumbnail.getRGB(0, 0));
            }
        });
    }
}
