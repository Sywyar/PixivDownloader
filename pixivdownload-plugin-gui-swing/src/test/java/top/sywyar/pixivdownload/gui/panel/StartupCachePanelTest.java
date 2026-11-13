package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.api.*;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Swing 启动训练工具")
class StartupCachePanelTest {
    @Test @DisplayName("开始、取消与停止中状态准确，关闭面板保留宿主任务")
    void lifecycle() throws Exception {
        UUID id = UUID.randomUUID();
        AtomicReference<DesktopStartupCache.Snapshot> state = new AtomicReference<>(
                new DesktopStartupCache.Snapshot(null, DesktopStartupCache.State.IDLE,
                        DesktopStartupCache.Phase.FINISHED, DesktopStartupCache.Failure.NONE, 0));
        AtomicInteger starts = new AtomicInteger(), cancels = new AtomicInteger();
        DesktopStartupCache service = new DesktopStartupCache() {
            public Snapshot snapshot() { return state.get(); }
            public UUID start() {
                starts.incrementAndGet();
                state.set(new Snapshot(id, State.RUNNING, Phase.RECORDING, Failure.NONE, 3));
                return id;
            }
            public boolean cancel(UUID execution) {
                assertEquals(id, execution);
                cancels.incrementAndGet();
                state.set(new Snapshot(id, State.STOPPING, Phase.RECORDING, Failure.NONE, 3));
                return true;
            }
        };
        SwingUtilities.invokeAndWait(() -> {
            var panel = new StartupCachePanel(host(Optional.of(service)));
            try {
                JButton start = button(panel, "startup-cache.start"), cancel = button(panel, "startup-cache.cancel");
                assertTrue(start.isEnabled());
                start.doClick();
                assertEquals(1, starts.get());
                assertFalse(start.isEnabled());
                assertTrue(cancel.isEnabled());
                panel.close();
                assertEquals(0, cancels.get());
                cancel.doClick();
                assertEquals(1, cancels.get());
                assertFalse(cancel.isEnabled());
                state.set(new DesktopStartupCache.Snapshot(id, DesktopStartupCache.State.CANCELLED,
                        DesktopStartupCache.Phase.FINISHED, DesktopStartupCache.Failure.NONE, 3));
                panel.refresh();
                assertTrue(start.isEnabled());
                assertFalse(cancel.isVisible());
            } finally { panel.close(); }
        });
    }

    @Test @DisplayName("不支持训练时提供说明且不能启动")
    void unavailable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new StartupCachePanel(host(Optional.empty()));
            assertFalse(button(panel, "startup-cache.start").isEnabled());
            assertTrue(descendants(panel).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                    .anyMatch(value -> value.getText().endsWith(".unavailable")));
            panel.close();
        });
    }

    private static DesktopUiHost host(Optional<DesktopStartupCache> service) {
        return (DesktopUiHost) java.lang.reflect.Proxy.newProxyInstance(DesktopUiHost.class.getClassLoader(),
                new Class<?>[]{DesktopUiHost.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "startupCache" -> service;
                    case "message" -> args[0];
                    default -> throw new AssertionError(method.getName());
                });
    }
    private static JButton button(Container panel, String name) {
        return descendants(panel).filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(button -> name.equals(button.getName())).findFirst().orElseThrow();
    }
    private static Stream<Component> descendants(Component value) {
        return Stream.concat(Stream.of(value), value instanceof Container container
                ? Arrays.stream(container.getComponents()).flatMap(StartupCachePanelTest::descendants) : Stream.empty());
    }
}
