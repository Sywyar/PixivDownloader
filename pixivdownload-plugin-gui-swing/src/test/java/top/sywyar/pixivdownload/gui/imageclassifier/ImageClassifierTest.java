package top.sywyar.pixivdownload.gui.imageclassifier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
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
    private List<Path> listedFolders;
    private IOException folderFailure;
    private IOException saveFailure;
    private boolean autoOpen = true;
    private DesktopUiHost.ImageClassifierSettings savedSettings;

    @BeforeEach
    void requireDesktop() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要桌面图形环境创建真实窗口");
    }

    @AfterEach
    void closeWindow() throws Exception {
        release.countDown();
        if (frame != null) SwingUtilities.invokeAndWait(() -> {
            for (Window window : frame.getOwnedWindows()) window.dispose();
            frame.dispose();
        });
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

    @Test
    @DisplayName("未打开目录时禁用分类和导航，打开后恢复")
    void startsWithoutCurrentFolder() throws Exception {
        autoOpen = false;
        release.countDown();
        openWindow(new CountDownLatch(0), false);
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(button("gui.image-classifier.button.skip-folder").isEnabled());
            assertFalse(button("gui.image-classifier.button.classify-folder").isEnabled());
            assertFalse(button("gui.image-classifier.button.next-group").isEnabled());
            folderField().setText(root.toString());
            folderField().postActionEvent();
            assertTrue(button("gui.image-classifier.button.skip-folder").isEnabled());
            assertTrue(button("gui.image-classifier.button.next-group").isEnabled());
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("切换到空目录或读取失败时撤销旧图片和导航，重开可以恢复")
    void clearsPreviousFolder(boolean failRead) throws Exception {
        release.countDown();
        openWindow(new CountDownLatch(0), false);
        awaitUi(() -> frame.getTitle().contains("Current artwork"));
        listedFolders = List.of();
        if (failRead) folderFailure = new IOException("Controlled folder read failure");
        dismissMessages(() -> {
            folderField().setText(root.resolve("empty").toString());
            folderField().postActionEvent();
        });
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(button("gui.image-classifier.button.next-group").isEnabled());
            assertFalse(button("gui.image-classifier.button.skip-folder").isEnabled());
            assertFalse(button("gui.image-classifier.button.classify-folder").isEnabled());
            assertFalse(frame.getTitle().contains("Current artwork"));
            assertEquals(10, components(frame).stream().filter(component -> component instanceof JLabel label
                    && GuiMessages.get("gui.image-classifier.thumbnail.empty").equals(label.getText())
                    && label.getIcon() == null).count());
        });
        folderFailure = null;
        listedFolders = null;
        SwingUtilities.invokeAndWait(() -> {
            folderField().setText(root.toString());
            folderField().postActionEvent();
            assertTrue(button("gui.image-classifier.button.next-group").isEnabled());
        });
    }

    @Test
    @DisplayName("末尾目录清空当前图片并禁用继续，返回上一目录恢复")
    void completesFolderNavigation() throws Exception {
        release.countDown();
        openWindow(new CountDownLatch(0), false);
        awaitUi(() -> frame.getTitle().contains("Current artwork"));
        dismissMessages(() -> button("gui.image-classifier.button.skip-folder").doClick(0));
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(button("gui.image-classifier.button.next-group").isEnabled());
            assertFalse(button("gui.image-classifier.button.skip-folder").isEnabled());
            assertFalse(button("gui.image-classifier.button.classify-folder").isEnabled());
            assertTrue(button("gui.image-classifier.button.prev-folder").isEnabled());
            button("gui.image-classifier.button.prev-folder").doClick(0);
            assertTrue(button("gui.image-classifier.button.skip-folder").isEnabled());
            assertTrue(button("gui.image-classifier.button.next-group").isEnabled());
        });
    }

    @Test
    @DisplayName("设置写失败保留草稿及原有界面，重试成功才发布设置")
    void keepsSettingsUntilSaved() throws Exception {
        autoOpen = false;
        release.countDown();
        saveFailure = new IOException("Controlled settings write failure");
        openWindow(new CountDownLatch(0), false);
        SwingUtilities.invokeLater(() -> button("gui.image-classifier.button.settings").doClick(0));
        awaitUi(() -> settingsDialog() != null);
        JDialog dialog = settingsDialog();
        SwingUtilities.invokeAndWait(() -> {
            components(dialog).stream().filter(JCheckBox.class::isInstance).map(JCheckBox.class::cast)
                    .findFirst().orElseThrow().setSelected(false);
            components(dialog).stream().filter(JTextField.class::isInstance).map(JTextField.class::cast)
                    .findFirst().orElseThrow().setText(root.resolve("draft").toString());
        });
        List<Object> failures = dismissMessages(() -> button(dialog, "gui.button.save").doClick(0));
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(dialog.isShowing());
            assertTrue(button("gui.image-classifier.button.skip-folder").isVisible());
            assertNull(savedSettings);
            assertEquals(1, failures.size());
            assertEquals(GuiMessages.get("gui.image-classifier.dialog.config-save-failed.message",
                    saveFailure.getMessage()), failures.get(0));
            assertEquals(root.resolve("draft").toString(), components(dialog).stream()
                    .filter(JTextField.class::isInstance).map(JTextField.class::cast)
                    .findFirst().orElseThrow().getText());
        });
        saveFailure = null;
        List<Object> successes = dismissMessages(() -> button(dialog, "gui.button.save").doClick(0));
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(dialog.isShowing());
            assertFalse(button("gui.image-classifier.button.skip-folder").isVisible());
            assertNotNull(savedSettings);
            assertEquals(root.resolve("draft").toString(), savedSettings.defaultFolder());
            assertFalse(savedSettings.showSkipButton());
            assertEquals(List.of(GuiMessages.get("gui.image-classifier.dialog.settings-saved.message")), successes);
        });
    }

    @Test
    @DisplayName("取消失败的设置草稿后，重新打开仍使用已保存值")
    void cancelsFailedSettingsDraft() throws Exception {
        autoOpen = false;
        release.countDown();
        saveFailure = new IOException("Controlled settings write failure");
        openWindow(new CountDownLatch(0), false);
        SwingUtilities.invokeLater(() -> button("gui.image-classifier.button.settings").doClick(0));
        awaitUi(() -> settingsDialog() != null);
        JDialog dialog = settingsDialog();
        SwingUtilities.invokeAndWait(() -> components(dialog).stream().filter(JTextField.class::isInstance)
                .map(JTextField.class::cast).findFirst().orElseThrow().setText("draft"));
        dismissMessages(() -> button(dialog, "gui.button.save").doClick(0));
        SwingUtilities.invokeAndWait(() -> button(dialog, "gui.image-classifier.button.cancel").doClick(0));
        SwingUtilities.invokeLater(() -> button("gui.image-classifier.button.settings").doClick(0));
        awaitUi(() -> settingsDialog() != null);
        SwingUtilities.invokeAndWait(() -> assertEquals("", components(settingsDialog()).stream()
                .filter(JTextField.class::isInstance).map(JTextField.class::cast)
                .findFirst().orElseThrow().getText()));
    }

    @Test
    @DisplayName("独立查看器在主窗口换目录后仍能翻看原图片")
    void viewerKeepsOpenedImages() throws Exception {
        Path source = java.nio.file.Files.createDirectories(root.resolve("42"));
        var image = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 11; i++) javax.imageio.ImageIO.write(image, "png", source.resolve(i + ".png").toFile());
        image.flush();
        release.countDown();
        openWindow(new CountDownLatch(0), false);
        awaitUi(() -> components(frame).stream().anyMatch(component -> component instanceof JLabel label
                && label.getIcon() != null));
        SwingUtilities.invokeAndWait(() -> {
            JLabel thumbnail = components(frame).stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                    .filter(label -> label.getIcon() != null).findFirst().orElseThrow();
            thumbnail.dispatchEvent(new MouseEvent(thumbnail, MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, 1, 1, 1, false));
        });
        JDialog viewer = (JDialog) frame.getOwnedWindows()[0];
        awaitUi(() -> components(viewer).stream().anyMatch(component -> component instanceof JLabel label
                && label.getIcon() != null));
        listedFolders = List.of();
        dismissMessages(() -> {
            folderField().setText(root.resolve("empty").toString());
            folderField().postActionEvent();
        });
        SwingUtilities.invokeAndWait(() -> {
            button(viewer, "gui.image-classifier.button.next-image").doClick(0);
            assertTrue(components(viewer).stream().anyMatch(component -> component instanceof JLabel label
                    && "1.png".equals(label.getText())));
            button(viewer, "gui.image-classifier.button.prev-image").doClick(0);
            assertTrue(components(viewer).stream().anyMatch(component -> component instanceof JLabel label
                    && "0.png".equals(label.getText())));
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("复制提示在翻页或关闭后不恢复旧状态")
    void invalidatesCopyFeedback(boolean close) throws Exception {
        release.countDown();
        openWindow(new CountDownLatch(0), false);
        awaitUi(() -> frame.getTitle().contains("Current artwork"));
        var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        var original = clipboard.getContents(null);
        try {
            JLabel[] status = new JLabel[1];
            String[] expected = new String[1];
            SwingUtilities.invokeAndWait(() -> {
                status[0] = components(frame).stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                        .filter(label -> label.getText() != null && label.getText().contains(GuiMessages.get(
                                "gui.image-classifier.status.tag.r18"))).findFirst().orElseThrow();
                status[0].dispatchEvent(new MouseEvent(status[0], MouseEvent.MOUSE_CLICKED,
                        System.currentTimeMillis(), 0, 1, 1, 1, false));
                if (close) frame.dispose();
                else button("gui.image-classifier.button.next-group").doClick(0);
            });
            if (!close) awaitUi(() -> queries.get() == 2 && status[0].getText().contains(
                    GuiMessages.get("gui.image-classifier.status.tag.r18")));
            SwingUtilities.invokeAndWait(() -> expected[0] = status[0].getText());
            Thread.sleep(1900);
            SwingUtilities.invokeAndWait(() -> assertEquals(expected[0], status[0].getText()));
        } finally {
            if (original != null) clipboard.setContents(original, null);
        }
    }

    private JTextField folderField() {
        return components(frame).stream().filter(JTextField.class::isInstance).map(JTextField.class::cast)
                .findFirst().orElseThrow();
    }

    private JDialog settingsDialog() {
        return java.util.Arrays.stream(frame.getOwnedWindows()).filter(JDialog.class::isInstance)
                .map(JDialog.class::cast).filter(dialog -> dialog.isShowing() && GuiMessages.get(
                        "gui.image-classifier.dialog.settings.title").equals(dialog.getTitle())).findFirst().orElse(null);
    }

    private void awaitUi(BooleanSupplier condition) throws Exception {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            SwingUtilities.invokeAndWait(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("界面状态未按时更新");
    }

    private List<Object> dismissMessages(Runnable action) throws Exception {
        List<Object> messages = new ArrayList<>();
        Timer dismiss = new Timer(30, event -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing()) {
                    components(dialog).stream().filter(JOptionPane.class::isInstance).map(JOptionPane.class::cast)
                            .findFirst().ifPresent(pane -> {
                                messages.add(pane.getMessage());
                                pane.setValue(JOptionPane.OK_OPTION);
                            });
                }
            }
        });
        SwingUtilities.invokeAndWait(dismiss::start);
        try {
            SwingUtilities.invokeAndWait(action);
        } finally {
            SwingUtilities.invokeAndWait(dismiss::stop);
        }
        return messages;
    }

    private void openWindow(CountDownLatch started, boolean delayServer) throws Exception {
        Path folder = root.resolve("42");
        var english = new DesktopUiHost.UiLocale("en-US", "English", "en");
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(), new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "resolveLocale" -> new DesktopUiHost.UiLocaleResolution(english, List.of(english));
                    case "loadImageClassifierSettings" -> new DesktopUiHost.ImageClassifierSettings(autoOpen ? root.toString() : "", true, "", List.of());
                    case "saveImageClassifierSettings" -> {
                        if (saveFailure != null) throw saveFailure;
                        savedSettings = (DesktopUiHost.ImageClassifierSettings) arguments[1];
                        yield null;
                    }
                    case "isImageClassifierDirectory" -> true;
                    case "listImageClassifierFolders" -> {
                        if (folderFailure != null) throw folderFailure;
                        yield listedFolders == null ? List.of(folder) : listedFolders;
                    }
                    case "deleteImageClassifierFolderIfEmpty" -> null;
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
        return button(frame, key);
    }

    private JButton button(Container parent, String key) {
        return components(parent).stream().filter(JButton.class::isInstance).map(JButton.class::cast)
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
