package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.render.SwingDesktopUiNodeRenderer;

import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Point;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Swing 顶层窗口回归门禁")
class MainFrameRegressionTest {

    @Test
    @DisplayName("固定窗口、对话框尺寸与关闭策略")
    void keepsWindowMetricsAndClosePolicy() {
        assertThat(MainFrame.defaultWindowSize()).isEqualTo(new Dimension(960, 720));
        assertThat(MainFrame.minimumWindowSize()).isEqualTo(new Dimension(760, 560));
        assertThat(MainFrame.dialogSize(new Dimension(480, 320), 0, 0))
                .isEqualTo(new Dimension(480, 320));
        assertThat(MainFrame.dialogSize(new Dimension(480, 320), 640, 0))
                .isEqualTo(new Dimension(640, 320));
        assertThat(MainFrame.closeBehavior(true)).isEqualTo(MainFrame.CloseBehavior.HIDE);
        assertThat(MainFrame.closeBehavior(false)).isEqualTo(MainFrame.CloseBehavior.EXIT);
    }

    @Test
    @DisplayName("文档刷新恢复内层标签、滚动、分栏与输入位置")
    void restoresTransientComponentStateAfterDocumentRefresh() throws Exception {
        JPanel root = onEdt(() -> {
            JPanel panel = new JPanel();
            JTabbedPane tabs = marked(new JTabbedPane(), "tabs");
            tabs.addTab("First", new JPanel());
            tabs.addTab("Second", new JPanel());
            tabs.setSelectedIndex(1);

            JPanel view = new JPanel();
            view.setPreferredSize(new Dimension(800, 800));
            JScrollPane scroll = marked(new JScrollPane(view), "scroll");
            scroll.setSize(240, 160);
            scroll.doLayout();
            scroll.getViewport().setViewPosition(new Point(70, 110));

            JSplitPane split = marked(new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                    new JPanel(), new JPanel()), "split");
            split.setSize(500, 200);
            split.setDividerLocation(180);

            JTextField text = marked(new JTextField("abcdef"), "text");
            text.setCaretPosition(4);
            JPasswordField password = marked(new JPasswordField("secret"), "password");
            panel.add(tabs);
            panel.add(scroll);
            panel.add(split);
            panel.add(text);
            panel.add(password);
            return panel;
        });

        Map<String, MainFrame.ComponentState> state = onEdt(() -> MainFrame.captureState(root));
        onEdt(() -> {
            component(root, "tabs", JTabbedPane.class).setSelectedIndex(0);
            component(root, "scroll", JScrollPane.class).getViewport().setViewPosition(new Point());
            component(root, "split", JSplitPane.class).setDividerLocation(40);
            component(root, "text", JTextField.class).setCaretPosition(0);
            component(root, "password", JPasswordField.class).setText("changed");
            MainFrame.restoreState(root, state);
            return null;
        });
        onEdt(() -> null);

        assertThat(onEdt(() -> component(root, "tabs", JTabbedPane.class).getSelectedIndex())).isEqualTo(1);
        assertThat(onEdt(() -> component(root, "scroll", JScrollPane.class)
                .getViewport().getViewPosition())).isEqualTo(new Point(70, 110));
        assertThat(onEdt(() -> component(root, "split", JSplitPane.class).getDividerLocation())).isEqualTo(180);
        assertThat(onEdt(() -> component(root, "text", JTextField.class).getCaretPosition())).isEqualTo(4);
        assertThat(onEdt(() -> new String(component(root, "password", JPasswordField.class).getPassword())))
                .isEqualTo("secret");
    }

    private static <T extends javax.swing.JComponent> T marked(T component, String id) {
        component.putClientProperty(SwingDesktopUiNodeRenderer.NODE_ID_PROPERTY, id);
        return component;
    }

    private static <T extends javax.swing.JComponent> T component(JPanel root, String id, Class<T> type) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof javax.swing.JComponent component
                    && id.equals(component.getClientProperty(SwingDesktopUiNodeRenderer.NODE_ID_PROPERTY))) {
                return type.cast(component);
            }
        }
        throw new IllegalArgumentException("unknown component: " + id);
    }

    private static <T> T onEdt(Callable<T> callable) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return callable.call();
        FutureTask<T> task = new FutureTask<>(callable);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
