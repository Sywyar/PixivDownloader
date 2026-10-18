package top.sywyar.pixivdownload.gui.imageclassifier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Swing 分类窗口查询与关闭")
class ImageClassifierTest {
    @TempDir Path root;
    private ImageClassifier frame;
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<Thread> workers = new CopyOnWriteArrayList<>();
    private final AtomicInteger queries = new AtomicInteger();
    private RuntimeException serverFailure;

    @BeforeEach
    void requireDesktop() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要桌面图形环境创建真实窗口");
    }

    @AfterEach
    void closeWindow() throws Exception {
        release.countDown();
        if (frame != null) SwingUtilities.invokeAndWait(frame::dispose);
        for (Thread worker : workers) {
            worker.join(2000);
            assertFalse(worker.isAlive());
        }
    }

    @Test
    @DisplayName("快速翻页合并待查询请求，只追加一次当前作品状态")
    void publishesOnlyLatestArtworkStatus() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        openWindow(started, false);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        CountDownLatch rendered = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            frame.addPropertyChangeListener("title", event -> {
                if (String.valueOf(event.getNewValue()).contains("Current artwork")) rendered.countDown();
            });
            for (int i = 0; i < 10; i++) {
                button("gui.image-classifier.button.next-group").doClick(0);
                button("gui.image-classifier.button.prev-group").doClick(0);
            }
        });
        assertEquals(1, queries.get());
        release.countDown();
        assertTrue(rendered.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(2, queries.get());
            String tag = GuiMessages.get("gui.image-classifier.status.tag.r18");
            List<String> labels = components(frame).stream().filter(JLabel.class::isInstance)
                    .map(component -> ((JLabel) component).getText()).filter(text -> text != null && text.contains(tag)).toList();
            assertEquals(1, labels.size());
            assertEquals(labels.get(0).indexOf(tag), labels.get(0).lastIndexOf(tag));
        });
    }

    @Test
    @DisplayName("关闭窗口后不交付迟到的作品信息或服务器状态")
    void closingRejectsLateQueries() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        openWindow(started, true);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        AtomicInteger updates = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            frame.addPropertyChangeListener("title", event -> updates.incrementAndGet());
            components(frame).stream().filter(JLabel.class::isInstance).forEach(component ->
                    component.addPropertyChangeListener("text", event -> updates.incrementAndGet()));
            frame.dispose();
        });
        release.countDown();
        for (Thread worker : workers) worker.join(2000);
        SwingUtilities.invokeAndWait(() -> assertEquals(0, updates.get()));
    }

    @Test
    @DisplayName("服务器检查异常时显示失败，作品查询仍可完成")
    void reportsServerFailure() throws Exception {
        serverFailure = new IllegalStateException("Controlled server failure");
        release.countDown();
        openWindow(new CountDownLatch(0), false);
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            SwingUtilities.invokeAndWait(() -> ready.set(frame.getTitle().contains("Current artwork")
                    && components(frame).stream().anyMatch(component -> component instanceof JLabel label
                    && GuiMessages.get("gui.image-classifier.server.connect-failed").equals(label.getText()))));
            if (ready.get()) break;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        assertTrue(ready.get());
    }

    private void openWindow(CountDownLatch started, boolean delayServer) throws Exception {
        Path folder = root.resolve("42");
        var english = new DesktopUiHost.UiLocale("en-US", "English", "en");
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(), new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "resolveLocale" -> new DesktopUiHost.UiLocaleResolution(english, List.of(english));
                    case "loadImageClassifierSettings" -> new DesktopUiHost.ImageClassifierSettings(root.toString(), true, "", List.of());
                    case "isImageClassifierDirectory" -> true;
                    case "listImageClassifierFolders" -> List.of(folder);
                    case "listImageClassifierImages" -> IntStream.range(0, 11).mapToObj(i -> folder.resolve(i + ".png")).toList();
                    case "resolveImageClassifierArtwork" -> {
                        queries.incrementAndGet();
                        awaitQuery(started);
                        yield Optional.of(new DesktopUiHost.ImageClassifierArtwork(42L, "Current artwork", 1));
                    }
                    case "checkImageClassifierServer" -> {
                        if (serverFailure != null) throw serverFailure;
                        if (delayServer) awaitQuery(started);
                        yield new DesktopUiHost.ImageClassifierServer(true, "http://fixture.invalid");
                    }
                    case "stripTrailingPathSeparators", "message" -> arguments[0];
                    default -> throw new AssertionError("Unexpected host call: " + method.getName());
                });
        SwingHost.install(new DesktopUiContext(false, 6999, root.toString(), root.resolve("config.yaml"), host,
                List.of(), List::of, text -> text.fallback(), () -> "system"));
        SwingUtilities.invokeAndWait(() -> frame = new ImageClassifier(false));
    }

    private void awaitQuery(CountDownLatch started) {
        workers.add(Thread.currentThread());
        started.countDown();
        while (true) {
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Query gate timed out");
                return;
            } catch (InterruptedException ignored) {
                // 模拟不能靠线程中断终止的远程查询。
            }
        }
    }

    private JButton button(String key) {
        return components(frame).stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(button -> GuiMessages.get(key).equals(button.getText())).findFirst().orElseThrow();
    }

    private static List<Component> components(Container parent) {
        List<Component> result = new ArrayList<>();
        for (Component child : parent.getComponents()) {
            result.add(child);
            if (child instanceof Container container) result.addAll(components(container));
        }
        return result;
    }
}
