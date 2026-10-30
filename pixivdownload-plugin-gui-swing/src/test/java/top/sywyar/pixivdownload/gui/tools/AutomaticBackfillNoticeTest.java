package top.sywyar.pixivdownload.gui.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class AutomaticBackfillNoticeTest {
    @Test
    @DisplayName("原生非模态提示等待引导和已有对话框，按执行去重且关闭释放窗口")
    void noticeLifecycle() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        var locale = new DesktopUiHost.UiLocale("en-US", "English", "_en");
        var host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.class}, (proxy, method, args) -> {
                    if (method.getName().equals("resolveLocale")) return new DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                    throw new AssertionError("Notice cannot invoke business commands: " + method.getName());
                });
        SwingHost.install(new DesktopUiContext(false, 8080, "downloads", Path.of("config.yaml"), "gui-swing",
                host, List.of(), List::of, text -> text.key(), () -> "system"));
        SwingUtilities.invokeAndWait(() -> {
            // 焦点准入在此固定；窗口、布局、按钮与释放仍使用真实 Swing 实现。
            JFrame frame = new JFrame() { @Override public boolean isActive() { return true; } };
            frame.setSize(640, 480);
            var views = new AtomicInteger();
            var notice = new AutomaticBackfillNotice(frame, views::incrementAndGet);
            try {
                UUID id = UUID.randomUUID();
                notice.refresh(task(id, 0, true, null, BackfillState.RUNNING), true);
                assertNull(visibleDialog(frame));
                frame.setVisible(true);
                notice.refresh(task(id, 1, true, 0, BackfillState.COMPLETED), true);
                assertNull(visibleDialog(frame));
                notice.refresh(task(id, 2, false, 3, BackfillState.RUNNING), true);
                assertNull(visibleDialog(frame));
                var running = task(id, 3, true, 3, BackfillState.RUNNING);
                notice.refresh(running, false);
                assertNull(visibleDialog(frame));
                JDialog blocker = new JDialog(frame);
                blocker.setSize(200, 100);
                blocker.setVisible(true);
                blocker.setModal(true);
                notice.refresh(running, true);
                assertEquals(blocker, visibleDialog(frame));
                blocker.dispose();
                notice.refresh(running, true);
                JDialog shown = visibleDialog(frame);
                assertNotNull(shown);
                assertFalse(shown.isModal());
                assertFalse(shown.isAutoRequestFocus());
                JButton view = button(shown, "view");
                notice.refresh(task(id, 4, true, 3, BackfillState.RUNNING), true);
                assertSame(view, button(shown, "view"), "Unrelated progress must retain focused controls");
                assertTrue(button(shown, "background").isShowing());
                button(shown, "background").doClick();
                assertFalse(shown.isDisplayable());
                notice.refresh(running, true);
                assertNull(visibleDialog(frame));
                UUID next = UUID.randomUUID();
                notice.refresh(task(next, 0, true, 3, BackfillState.INCOMPLETE), true);
                JDialog result = visibleDialog(frame);
                assertNotNull(result);
                view.doClick();
                assertEquals(0, views.get(), "Old buttons must not navigate for a new execution");
                assertSame(result, visibleDialog(frame));
                assertTrue(descendants(result).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                        .anyMatch(label -> label.getText().equals(GuiMessages.get("gui.tools.backfill.state.incomplete"))));
                button(result, "results").doClick();
                assertEquals(1, views.get());
                assertNull(visibleDialog(frame));
                notice.refresh(task(UUID.randomUUID(), 0, true, null, BackfillState.FAILED), true);
                JDialog failed = visibleDialog(frame);
                assertNotNull(failed);
                failed.dispatchEvent(new WindowEvent(failed, WindowEvent.WINDOW_CLOSING));
                assertNull(visibleDialog(frame));
                notice.refresh(task(UUID.randomUUID(), 0, true, 3, BackfillState.RUNNING), true);
                JDialog closing = visibleDialog(frame);
                notice.close();
                assertFalse(closing.isDisplayable());
                notice.refresh(running, true);
                assertNull(visibleDialog(frame));
            } finally {
                notice.close();
                frame.dispose();
            }
        });
    }

    private static BackfillTask task(UUID id, long revision, boolean automatic, Integer total, BackfillState state) {
        return new BackfillTask(id, revision, state, BackfillPhase.NETWORK, automatic, false,
                1, 0, 0, total, 0, 0, 0, null, null, null, "fixture.html");
    }

    private static JDialog visibleDialog(JFrame owner) {
        return java.util.Arrays.stream(owner.getOwnedWindows()).filter(Window::isVisible)
                .filter(JDialog.class::isInstance).map(JDialog.class::cast).findFirst().orElse(null);
    }

    private static JButton button(Container root, String key) {
        return descendants(root).filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(button -> button.getText().equals(GuiMessages.get("gui.tools.backfill.notice." + key)))
                .findFirst().orElseThrow();
    }

    private static Stream<Component> descendants(Component component) {
        return Stream.concat(Stream.of(component), component instanceof Container container
                ? java.util.Arrays.stream(container.getComponents()).flatMap(AutomaticBackfillNoticeTest::descendants)
                : Stream.empty());
    }
}
