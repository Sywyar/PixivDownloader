package top.sywyar.pixivdownload.gui.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Swing 声明式桌面 UI 渲染器")
class SwingDesktopUiNodeRendererTest {

    @Test
    @DisplayName("完整稳定词汇可渲染为 Swing 控件并投射受控事件")
    void rendersEveryStableNodeKindAndProjectsEvents() throws Exception {
        DesktopUiNode document = completeDocument();
        List<DesktopUiNode.Event> events = new ArrayList<>();
        SwingDesktopUiNodeRenderer renderer = new SwingDesktopUiNodeRenderer(
                token -> token.fallback(), events::add);

        JComponent rendered = onEdt(() -> renderer.render(document));

        assertThat(DesktopUiNode.validateTree(document)).containsExactlyInAnyOrderElementsOf(
                EnumSet.allOf(DesktopUiNode.Kind.class));
        assertThat(SwingDesktopUiNodeRenderer.supportedKinds()).containsExactlyInAnyOrderElementsOf(
                EnumSet.allOf(DesktopUiNode.Kind.class));
        assertThat(onEdt(() -> descendants(rendered, JList.class))).hasSize(1);
        assertThat(onEdt(() -> descendants(rendered, JTable.class))).hasSize(1);
        assertThat(onEdt(() -> descendants(rendered, JTree.class))).hasSize(1);
        assertThat(onEdt(() -> descendants(rendered, JComboBox.class))).hasSize(1);
        assertThat(onEdt(() -> descendants(rendered, JPasswordField.class))).hasSize(1);
        assertThat(onEdt(() -> descendants(rendered, JTextArea.class))).isNotEmpty();
        assertThat(onEdt(() -> descendants(rendered, JCheckBox.class))).isNotEmpty();
        assertThat(onEdt(() -> descendants(rendered, JToggleButton.class))).isNotEmpty();
        assertThat(onEdt(() -> descendants(rendered, JSpinner.class))).hasSize(1);
        assertThat(onEdt(() -> descendants(rendered, JSlider.class))).hasSize(1);
        assertThat(onEdt(() -> descendants(rendered, JProgressBar.class))).hasSize(1);
        assertThat(onEdt(() -> descendants(rendered, JSplitPane.class))).hasSize(1);
        assertThat(onEdt(() -> descendants(rendered, JTabbedPane.class))).hasSize(1);

        onEdt(() -> {
            JList<?> list = descendants(rendered, JList.class).get(0);
            list.setSelectedIndex(1);
            list.setSelectedIndex(2);
            descendants(rendered, JCheckBox.class).stream()
                    .filter(box -> box.getText().equals("Two"))
                    .findFirst().orElseThrow().doClick();
            descendants(rendered, JButton.class).stream()
                    .filter(button -> button.getText().equals("Run"))
                    .findFirst().orElseThrow().doClick();
            return null;
        });

        assertThat(events.stream()
                .filter(event -> event.nodeId().equals("choice.list"))
                .map(event -> event.value().values())
                .toList()).containsExactly(List.of("two"));
        assertThat(events.stream()
                .filter(event -> event.nodeId().equals("choice.checkboxes"))
                .map(event -> event.value().values())
                .toList()).containsExactly(List.of("one", "two"));
        assertThat(events).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo(DesktopUiNode.EventType.ACTIVATE);
            assertThat(event.nodeId()).isEqualTo("button");
        });
    }

    @Test
    @DisplayName("响应式表单保持多行控件左边界一致")
    void responsiveFormAlignsControls() throws Exception {
        DesktopUiNode form = new DesktopUiNode.Form("form.geometry", DesktopUiNode.FormStyle.RESPONSIVE,
                raw(":"), List.of(
                new DesktopUiNode.FormRow("form.geometry.first", raw("Short"), null,
                        input("form.geometry.first.input", DesktopUiNode.InputKind.TEXT, "one"), null),
                new DesktopUiNode.FormRow("form.geometry.second", raw("A much longer label"), raw("Help"),
                        input("form.geometry.second.input", DesktopUiNode.InputKind.TEXT, "two"), null)));
        SwingDesktopUiNodeRenderer renderer = new SwingDesktopUiNodeRenderer(
                token -> token.fallback(), ignored -> { });

        JComponent rendered = onEdt(() -> {
            JComponent component = renderer.render(form);
            component.setSize(720, component.getPreferredSize().height);
            layoutTree(component);
            return component;
        });

        JComponent first = node(rendered, "form.geometry.first.input");
        JComponent second = node(rendered, "form.geometry.second.input");
        assertThat(SwingUtilities.convertPoint(first, 0, 0, rendered).x)
                .isEqualTo(SwingUtilities.convertPoint(second, 0, 0, rendered).x);
    }

    @Test
    @DisplayName("工具包状态恢复不会冒充用户输入")
    void suppressesEventsWhileRestoringToolkitState() throws Exception {
        List<DesktopUiNode.Event> events = new ArrayList<>();
        SwingDesktopUiNodeRenderer renderer = new SwingDesktopUiNodeRenderer(
                token -> token.fallback(), events::add);
        JPasswordField password = onEdt(() -> descendants(renderer.render(
                input("password", DesktopUiNode.InputKind.PASSWORD, "")), JPasswordField.class).get(0));

        assertThat(password.getClientProperty(SwingDesktopUiNodeRenderer.NODE_ID_PROPERTY)).isEqualTo("password");
        assertThat(password.getClientProperty(SwingDesktopUiNodeRenderer.PASSWORD_STATE_KEY_PROPERTY))
                .isEqualTo("password@0");

        onEdt(() -> {
            renderer.withoutEvents(() -> password.setText("restored"));
            return null;
        });
        assertThat(events).isEmpty();

        onEdt(() -> {
            password.setText("typed");
            return null;
        });
        assertThat(events).isNotEmpty();
    }

    @Test
    @DisplayName("嵌套滚动区到达边界后把滚轮交给外层页面")
    void nestedScrollHandsWheelToTheOuterPageAtItsBoundary() throws Exception {
        List<DesktopUiNode.TableRow> rows = java.util.stream.IntStream.range(0, 30)
                .mapToObj(index -> new DesktopUiNode.TableRow("row." + index, List.of("Row " + index)))
                .toList();
        DesktopUiNode document = new DesktopUiNode.Scroll("outer.scroll",
                new DesktopUiNode.Container("outer.content", DesktopUiNode.ContainerLayout.COLUMN,
                        1, 4, DesktopUiNode.Alignment.STRETCH, List.of(
                        new DesktopUiNode.Spacer("outer.spacer", 1, 600),
                        new DesktopUiNode.Table("inner.table", "inner.selection",
                                List.of(new DesktopUiNode.TableColumn("value", raw("Value"), 160)),
                                rows, DesktopUiNode.SelectionMode.SINGLE, List.of(), true))));
        SwingDesktopUiNodeRenderer renderer = new SwingDesktopUiNodeRenderer(
                token -> token.fallback(), ignored -> { });

        int[] values = onEdt(() -> {
            JScrollPane outer = (JScrollPane) renderer.render(document);
            outer.setSize(320, 180);
            layoutTree(outer);
            JScrollPane inner = (JScrollPane) node(outer, "inner.table");
            inner.setSize(280, 120);
            layoutTree(inner);
            outer.getVerticalScrollBar().setValue(120);
            int before = outer.getVerticalScrollBar().getValue();
            inner.getVerticalScrollBar().setValue(inner.getVerticalScrollBar().getMinimum());
            inner.dispatchEvent(new MouseWheelEvent(inner, MouseEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(), 0, 10, 10, 0, false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1));
            return new int[]{before, outer.getVerticalScrollBar().getValue()};
        });

        assertThat(values[0]).isPositive();
        assertThat(values[1]).isLessThan(values[0]);
    }

    private static DesktopUiNode completeDocument() {
        return new DesktopUiNode.Container(
                "root", DesktopUiNode.ContainerLayout.COLUMN, 1, 4,
                DesktopUiNode.Alignment.STRETCH, List.of(
                        new DesktopUiNode.Dock("dock", 4,
                                text("dock.top", "Top"), text("dock.center", "Center"),
                                text("dock.bottom", "Bottom"), null, null),
                        new DesktopUiNode.Surface("surface", DesktopUiNode.SurfaceStyle.WARNING,
                                DesktopUiNode.Insets.all(8), true, text("surface.text", "Warning")),
                        new DesktopUiNode.Group("group", raw("Group"), text("group.text", "Body")),
                        new DesktopUiNode.Form("form", DesktopUiNode.FormStyle.RESPONSIVE, raw(":"), List.of(
                                new DesktopUiNode.FormRow("form.row", raw("Field"), raw("Help"),
                                        input("form.input", DesktopUiNode.InputKind.NUMBER, "1"),
                                        text("form.trailing", "Restart")))),
                        new DesktopUiNode.Tabs("tabs", List.of(
                                new DesktopUiNode.Tab("general", raw("General"), text("tabs.text", "Tab")))),
                        new DesktopUiNode.Scroll("scroll", text("scroll.text", "Scrollable")),
                        new DesktopUiNode.Split("split", DesktopUiNode.Axis.HORIZONTAL, 0.5,
                                text("split.first", "First"), text("split.second", "Second")),
                        new DesktopUiNode.Image("image",
                                new DesktopUiNode.ImageData("image/gif",
                                        "R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=="),
                                raw("Pixel"), 16, 16, DesktopUiNode.ScaleMode.FIT),
                        new DesktopUiNode.Separator("separator", DesktopUiNode.Axis.HORIZONTAL),
                        new DesktopUiNode.Spacer("spacer", 4, 4),
                        new DesktopUiNode.Progress("progress", 0.5, false, raw("Half")),
                        input("input.text", DesktopUiNode.InputKind.TEXT, "value"),
                        input("input.password", DesktopUiNode.InputKind.PASSWORD, ""),
                        input("input.multiline", DesktopUiNode.InputKind.MULTILINE, "line"),
                        input("input.search", DesktopUiNode.InputKind.SEARCH, "query"),
                        input("input.date", DesktopUiNode.InputKind.DATE, "2026-08-18"),
                        input("input.time", DesktopUiNode.InputKind.TIME, "18:30"),
                        input("input.datetime", DesktopUiNode.InputKind.DATE_TIME, "2026-08-18T18:30"),
                        input("input.file", DesktopUiNode.InputKind.FILE, ""),
                        input("input.directory", DesktopUiNode.InputKind.DIRECTORY, ""),
                        new DesktopUiNode.Toggle("toggle.checkbox", "toggle.checkbox.value", raw("Check"), null,
                                DesktopUiNode.ToggleStyle.CHECKBOX, true, true),
                        new DesktopUiNode.Toggle("toggle.switch", "toggle.switch.value", raw("Switch"), null,
                                DesktopUiNode.ToggleStyle.SWITCH, false, true),
                        choice("choice.combo", DesktopUiNode.ChoiceStyle.COMBO_BOX,
                                DesktopUiNode.SelectionMode.SINGLE),
                        choice("choice.list", DesktopUiNode.ChoiceStyle.LIST,
                                DesktopUiNode.SelectionMode.SINGLE),
                        choice("choice.radio", DesktopUiNode.ChoiceStyle.RADIO_BUTTONS,
                                DesktopUiNode.SelectionMode.SINGLE),
                        choice("choice.checkboxes", DesktopUiNode.ChoiceStyle.CHECK_BOXES,
                                DesktopUiNode.SelectionMode.MULTIPLE),
                        new DesktopUiNode.NumberInput("number.spinner", "number.spinner.value", raw("Spinner"), null,
                                DesktopUiNode.NumberStyle.SPINNER, 2, 0, 10, 1, true),
                        new DesktopUiNode.NumberInput("number.slider", "number.slider.value", raw("Slider"), null,
                                DesktopUiNode.NumberStyle.SLIDER, 4, 0, 10, 2, true),
                        new DesktopUiNode.Table("table", "table.rows",
                                List.of(new DesktopUiNode.TableColumn("name", raw("Name"), 100)),
                                List.of(new DesktopUiNode.TableRow("row.one", List.of("One"))),
                                DesktopUiNode.SelectionMode.SINGLE, List.of("row.one"), true),
                        new DesktopUiNode.Tree("tree", "tree.items",
                                List.of(new DesktopUiNode.TreeItem("root.item", raw("Root"),
                                        List.of(new DesktopUiNode.TreeItem("child.item", raw("Child"), List.of())))),
                                DesktopUiNode.SelectionMode.MULTIPLE, List.of("child.item"), true),
                        new DesktopUiNode.Button("button", "action.run", raw("Run"), null,
                                DesktopUiNode.ButtonStyle.PRIMARY, true),
                        new DesktopUiNode.Link("link", "action.help", raw("Help"), null, true)));
    }

    private static DesktopUiNode.TextInput input(String id, DesktopUiNode.InputKind kind, String value) {
        return new DesktopUiNode.TextInput(id, id + ".value", raw(id), null,
                kind, value, 12, 3, true);
    }

    private static DesktopUiNode.Choice choice(String id, DesktopUiNode.ChoiceStyle style,
                                               DesktopUiNode.SelectionMode mode) {
        return new DesktopUiNode.Choice(id, id, raw(id), null, style, mode,
                List.of(
                        new DesktopUiNode.Option("one", raw("One"), true),
                        new DesktopUiNode.Option("two", raw("Two"), true),
                        new DesktopUiNode.Option("disabled", raw("Disabled"), false)),
                List.of("one"), true);
    }

    private static DesktopUiNode.Text text(String id, String value) {
        return new DesktopUiNode.Text(id, raw(value), DesktopUiNode.TextStyle.BODY, false, false);
    }

    private static DesktopUiNode.TextToken raw(String value) {
        return DesktopUiNode.TextToken.raw(value);
    }

    private static <T extends Component> List<T> descendants(Component root, Class<T> type) {
        List<T> matches = new ArrayList<>();
        collect(root, type, matches);
        return matches;
    }

    private static JComponent node(Component root, String id) {
        return descendants(root, JComponent.class).stream()
                .filter(component -> id.equals(component.getClientProperty(
                        SwingDesktopUiNodeRenderer.NODE_ID_PROPERTY)))
                .findFirst().orElseThrow();
    }

    private static void layoutTree(Component component) {
        if (!(component instanceof Container container)) return;
        container.doLayout();
        for (Component child : container.getComponents()) layoutTree(child);
    }

    private static <T extends Component> void collect(Component component, Class<T> type, List<T> matches) {
        if (type.isInstance(component)) matches.add(type.cast(component));
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collect(child, type, matches);
        }
    }

    private static <T> T onEdt(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
