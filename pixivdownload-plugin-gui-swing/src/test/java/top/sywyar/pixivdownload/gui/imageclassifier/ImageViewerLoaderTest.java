package top.sywyar.pixivdownload.gui.imageclassifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Swing 图片查看器加载生命周期")
class ImageViewerLoaderTest {
    @Test
    @DisplayName("快速翻页只解码在途与最新图片，旧结果不得覆盖新图片")
    void replacesQueuedRequestsAndPublishesOnlyLatest() throws Exception {
        ImageViewerLoader loader = new ImageViewerLoader();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch displayed = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicInteger decoded = new AtomicInteger();
        List<Object> results = new CopyOnWriteArrayList<>();
        ImageIcon latest = new ImageIcon(new BufferedImage(32, 24, BufferedImage.TYPE_INT_ARGB));
        try {
            SwingUtilities.invokeAndWait(() -> loader.load(() -> {
                worker.set(Thread.currentThread());
                decoded.incrementAndGet();
                started.countDown();
                awaitIgnoringInterrupt(release);
                return new ImageIcon(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB));
            }, results::add, results::add));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                for (int i = 0; i < 30; i++) {
                    loader.load(() -> {
                        decoded.incrementAndGet();
                        return latest;
                    }, results::add, results::add);
                }
                loader.load(() -> {
                    decoded.incrementAndGet();
                    assertSame(worker.get(), Thread.currentThread());
                    return latest;
                }, icon -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    results.add(icon);
                    displayed.countDown();
                }, results::add);
            });
            release.countDown();
            assertTrue(displayed.await(5, TimeUnit.SECONDS));
            assertEquals(2, decoded.get());
            assertEquals(List.of(latest), results);
        } finally {
            release.countDown();
            SwingUtilities.invokeAndWait(loader::close);
            if (worker.get() != null) {
                worker.get().join(2000);
                assertFalse(worker.get().isAlive());
            }
        }
    }

    @Test
    @DisplayName("关闭时移除待解码请求并丢弃无法中断的迟到结果")
    void closingDiscardsRunningAndQueuedResults() throws Exception {
        ImageViewerLoader loader = new ImageViewerLoader();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicInteger decoded = new AtomicInteger();
        List<Object> results = new CopyOnWriteArrayList<>();
        try {
            SwingUtilities.invokeAndWait(() -> loader.load(() -> {
                worker.set(Thread.currentThread());
                decoded.incrementAndGet();
                started.countDown();
                awaitIgnoringInterrupt(release);
                return new ImageIcon(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB));
            }, results::add, results::add));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                loader.load(() -> { decoded.incrementAndGet(); return null; }, results::add, results::add);
                loader.close();
                loader.load(() -> { decoded.incrementAndGet(); return null; }, results::add, results::add);
            });
            release.countDown();
            worker.get().join(2000);
            assertFalse(worker.get().isAlive());
            SwingUtilities.invokeAndWait(() -> {});
            assertEquals(1, decoded.get());
            assertTrue(results.isEmpty());
        } finally {
            release.countDown();
            SwingUtilities.invokeAndWait(loader::close);
        }
    }

    @Test
    @DisplayName("当前图片加载失败仍在 EDT 交付原始异常")
    void reportsCurrentFailure() throws Exception {
        ImageViewerLoader loader = new ImageViewerLoader();
        CountDownLatch completed = new CountDownLatch(1);
        IOException expected = new IOException("Broken image");
        AtomicReference<Throwable> actual = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> loader.load(() -> { throw expected; }, icon -> fail(), error -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                actual.set(error);
                completed.countDown();
            }));
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertSame(expected, actual.get());
        } finally {
            SwingUtilities.invokeAndWait(loader::close);
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        while (true) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Decode gate timed out");
                return;
            } catch (InterruptedException ignored) {
                // 模拟不能靠线程中断终止的 ImageIO 读取器。
            }
        }
    }
}
