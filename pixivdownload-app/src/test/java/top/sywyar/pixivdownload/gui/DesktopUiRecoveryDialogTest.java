package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("无 GUI 的原生恢复窗口")
class DesktopUiRecoveryDialogTest {
    @Test
    @DisplayName("多行和长 Unicode 摘要换行后保持完整")
    void wrapsSummaryWithoutSplittingUnicode() {
        var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            var metrics = graphics.getFontMetrics(new Font(Font.DIALOG, Font.PLAIN, 14));
            String text = "锁😀".repeat(40) + "\nmore details";
            var lines = DesktopUiDialogs.wrapBootstrapText(text, metrics, 120);
            assertThat(String.join("", lines)).isEqualTo(text.replace("\n", ""));
            assertThat(lines).allSatisfy(line -> assertThat(metrics.stringWidth(line)).isLessThanOrEqualTo(120));
        } finally { graphics.dispose(); }
    }

    @Test
    @EnabledIfSystemProperty(named = "pixivdownload.test.nativeRecovery", matches = "true")
    @DisplayName("真实 AWT 窗口保留市场入口，重启失败可重试并可正常退出")
    void nativeWindowRemainsAvailable() throws Exception {
        AtomicInteger markets = new AtomicInteger();
        AtomicInteger restarts = new AtomicInteger();
        AtomicInteger logs = new AtomicInteger();
        AtomicBoolean exits = new AtomicBoolean();
        AtomicReference<Runnable> activate = new AtomicReference<>();
        String title = "Recovery UI test";
        String body = MessageBundles.get(Locale.SIMPLIFIED_CHINESE, "recovery.explanation") + "\n\n"
                + MessageBundles.get(Locale.SIMPLIFIED_CHINESE, "recovery.advice.directory-in-use") + "\n\n"
                + MessageBundles.get(Locale.SIMPLIFIED_CHINESE, "recovery.error-summary")
                + "\nDIRECTORY_IN_USE: C:/isolated-test/plugins\n" + "异常细节😀 ".repeat(90);
        DesktopUiDialogs.showBootstrapRecoveryDialog(
                title,
                body,
                "打开插件市场",
                "重启软件",
                "退出软件",
                "打开日志目录",
                "操作未成功，请重试或查看日志。",
                markets::incrementAndGet,
                () -> restarts.incrementAndGet() > 1,
                () -> { exits.set(true); return true; },
                () -> logs.incrementAndGet() > 1,
                activate::set
        );
        EventQueue.invokeAndWait(() -> {});
        Dialog dialog = (Dialog) Arrays.stream(Window.getWindows())
                .filter(window -> window instanceof Dialog d && title.equals(d.getTitle())).findFirst().orElseThrow();
        try {
            EventQueue.invokeAndWait(() -> click(dialog, "打开日志目录"));
            org.awaitility.Awaitility.await().untilAsserted(() -> EventQueue.invokeAndWait(() ->
                    assertThat(button(dialog, "打开日志目录").isEnabled()).isTrue()));
            assertThat(logs).hasValue(1);
            EventQueue.invokeAndWait(() -> click(dialog, "打开日志目录"));
            org.awaitility.Awaitility.await().untilAsserted(() -> EventQueue.invokeAndWait(() ->
                    assertThat(button(dialog, "打开日志目录").isEnabled()).isTrue()));
            assertThat(logs).hasValue(2);
            EventQueue.invokeAndWait(() -> {
                click(dialog, "打开插件市场");
                click(dialog, "重启软件");
                assertThat(dialog.isVisible()).isTrue();
                assertThat(button(dialog, "重启软件").isEnabled()).isTrue();
            });
            assertThat(markets).hasValue(1);
            assertThat(restarts).hasValue(1);
            assertThat(activate.get()).isNotNull();
            activate.get().run();
            new Robot().waitForIdle();
            Path output = Path.of("target/native-recovery.png");
            Files.createDirectories(output.getParent());
            ImageIO.write(new Robot().createScreenCapture(dialog.getBounds()), "png", output.toFile());
            EventQueue.invokeAndWait(() -> {
                click(dialog, "重启软件");
                assertThat(button(dialog, "重启软件").isEnabled()).isFalse();
                assertThat(button(dialog, "打开日志目录").isEnabled()).isFalse();
                dialog.dispatchEvent(new java.awt.event.WindowEvent(dialog, java.awt.event.WindowEvent.WINDOW_CLOSING));
            });
            assertThat(exits).isTrue();
            assertThat(dialog.isDisplayable()).isFalse();
        } finally { EventQueue.invokeAndWait(dialog::dispose); }
    }

    private static void click(Container root, String label) {
        Button button = button(root, label);
        button.dispatchEvent(new ActionEvent(button, ActionEvent.ACTION_PERFORMED, label));
    }

    private static Button button(Container root, String label) {
        for (Component component : root.getComponents()) {
            if (component instanceof Button button && label.equals(button.getAccessibleContext().getAccessibleName()))
                return button;
            if (component instanceof Container container) {
                Button nested = button(container, label);
                if (nested != null) return nested;
            }
        }
        return null;
    }
}
