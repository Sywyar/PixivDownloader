package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.render.SwingDesktopUiNodeRenderer;
import top.sywyar.pixivdownload.guitheme.GuiSwingPlugin;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiModel;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPasswordField;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Swing 桌面真实渲染闭环")
class SwingDesktopUiConformanceTest {

    @Test
    @DisplayName("真实 EDT 驱动共享场景且不重读最新修订号")
    void drivesSharedScenariosOnTheRealEdt() throws Exception {
        for (Scenario scenario : scenarios()) {
            if (scenario.id().equals("password-generation")) {
                runPasswordScenario(scenario);
                continue;
            }
            DesktopUiNode node = node(scenario.id(), 1L);
            TestModel model = new TestModel(node);
            DesktopUiContext context = context(model);
            DesktopUiSnapshot observed = context.currentSnapshot();
            SwingDesktopUiNodeRenderer renderer = new SwingDesktopUiNodeRenderer(
                    DesktopUiNode.TextToken::fallback,
                    event -> context.dispatchEvent(observed, event));
            JComponent component = onEdt(() -> renderer.render(node));

            onEdt(() -> {
                drive(component, scenario);
                return null;
            });

            assertThat(model.acceptedValues()).as(scenario.id())
                    .isEqualTo(scenario.expected());
        }
    }

    private static void runPasswordScenario(Scenario scenario) throws Exception {
        DesktopUiNode.TextInput firstNode = (DesktopUiNode.TextInput) node(scenario.id(), 1L);
        TestModel model = new TestModel(firstNode);
        DesktopUiContext context = context(model);
        DesktopUiSnapshot firstSnapshot = context.currentSnapshot();
        SwingDesktopUiNodeRenderer firstRenderer = renderer(context, firstSnapshot);
        JComponent first = onEdt(() -> firstRenderer.render(firstNode));

        onEdt(() -> {
            password(first).replaceSelection(scenario.operations().get(0));
            return null;
        });
        Map<String, MainFrame.ComponentState> firstState = onEdt(() -> MainFrame.captureState(first));

        model.publish(firstNode);
        DesktopUiSnapshot refreshedSnapshot = context.currentSnapshot();
        SwingDesktopUiNodeRenderer refreshedRenderer = renderer(context, refreshedSnapshot);
        JComponent refreshed = onEdt(() -> refreshedRenderer.render(firstNode));
        onEdt(() -> {
            refreshedRenderer.withoutEvents(() -> MainFrame.restoreState(refreshed, firstState));
            password(refreshed).replaceSelection(scenario.operations().get(2));
            return null;
        });
        Map<String, MainFrame.ComponentState> refreshedState = onEdt(() -> MainFrame.captureState(refreshed));

        DesktopUiNode.TextInput clearedNode = (DesktopUiNode.TextInput) node(scenario.id(), 2L);
        model.publish(clearedNode);
        DesktopUiSnapshot clearedSnapshot = context.currentSnapshot();
        SwingDesktopUiNodeRenderer clearedRenderer = renderer(context, clearedSnapshot);
        JComponent cleared = onEdt(() -> clearedRenderer.render(clearedNode));
        onEdt(() -> {
            clearedRenderer.withoutEvents(() -> MainFrame.restoreState(cleared, refreshedState));
            password(cleared).replaceSelection(scenario.operations().get(4));
            return null;
        });

        assertThat(model.acceptedValues()).as(scenario.id()).isEqualTo(scenario.expected());
    }

    private static SwingDesktopUiNodeRenderer renderer(
            DesktopUiContext context, DesktopUiSnapshot observed) {
        return new SwingDesktopUiNodeRenderer(DesktopUiNode.TextToken::fallback,
                event -> context.dispatchEvent(observed, event));
    }

    private static void drive(JComponent component, Scenario scenario) {
        switch (scenario.id()) {
            case "burst-text" -> scenario.operations().forEach(value -> {
                JTextField field = descendants(component, JTextField.class).get(0);
                field.setCaretPosition(field.getDocument().getLength());
                field.replaceSelection(value);
            });
            case "number-spinner" -> {
                JSpinner spinner = descendants(component, JSpinner.class).get(0);
                scenario.operations().forEach(operation -> spinner.setValue(
                        ((Number) spinner.getValue()).intValue() + (operation.equals("+") ? 1 : -1)));
            }
            case "number-slider" -> {
                JSlider slider = descendants(component, JSlider.class).get(0);
                scenario.operations().forEach(value -> slider.setValue(Integer.parseInt(value)));
            }
            case "toggle-burst" -> {
                AbstractButton toggle = descendants(component, AbstractButton.class).get(0);
                scenario.operations().forEach(ignored -> toggle.doClick());
            }
            case "choice-single" -> descendants(component, JComboBox.class).get(0)
                    .setSelectedIndex(optionIndex(scenario.operations().get(0)));
            case "choice-multiple" -> scenario.operations().forEach(value ->
                    descendants(component, JCheckBox.class).stream()
                            .filter(box -> box.getText().equals(label(value)))
                            .findFirst().orElseThrow().doClick());
            case "table-multiple" -> {
                JTable table = descendants(component, JTable.class).get(0);
                scenario.operations().forEach(value -> table.addRowSelectionInterval(
                        optionIndex(value), optionIndex(value)));
            }
            case "tree-multiple" -> {
                JTree tree = descendants(component, JTree.class).get(0);
                scenario.operations().forEach(value -> tree.addSelectionPath(path(tree, label(value))));
            }
            case "stale-action" -> {
                JButton button = descendants(component, JButton.class).get(0);
                scenario.operations().forEach(ignored -> button.doClick());
            }
            default -> throw new IllegalArgumentException("unknown desktop UI scenario: " + scenario.id());
        }
    }

    private static DesktopUiContext context(TestModel model) {
        GuiSwingPlugin provider = new GuiSwingPlugin();
        return new DesktopUiContext(false, "Conformance", model,
                DesktopUiNode.TextToken::fallback, () -> { }, () -> "system",
                provider.id(), provider.supportedNodeKinds(), provider.supportedCapabilities());
    }

    private static DesktopUiNode node(String id, long stateRevision) {
        DesktopUiNode.TextToken label = raw(label(id));
        return switch (id) {
            case "burst-text" -> new DesktopUiNode.TextInput(id, id + ".value", label, null,
                    DesktopUiNode.InputKind.TEXT, "", 12, 1, true);
            case "password-generation" -> new DesktopUiNode.TextInput(id, id + ".value", label, null,
                    DesktopUiNode.InputKind.PASSWORD, "", 12, 1, true, stateRevision);
            case "number-spinner" -> new DesktopUiNode.NumberInput(id, id + ".value", label, null,
                    DesktopUiNode.NumberStyle.SPINNER, 1, 0, 5, 1, true);
            case "number-slider" -> new DesktopUiNode.NumberInput(id, id + ".value", label, null,
                    DesktopUiNode.NumberStyle.SLIDER, 0, 0, 5, 1, true);
            case "toggle-burst" -> new DesktopUiNode.Toggle(id, id + ".value", label, null,
                    DesktopUiNode.ToggleStyle.SWITCH, false, true);
            case "choice-single" -> choice(id, DesktopUiNode.ChoiceStyle.COMBO_BOX,
                    DesktopUiNode.SelectionMode.SINGLE);
            case "choice-multiple" -> choice(id, DesktopUiNode.ChoiceStyle.CHECK_BOXES,
                    DesktopUiNode.SelectionMode.MULTIPLE);
            case "table-multiple" -> new DesktopUiNode.Table(id, id + ".value",
                    List.of(new DesktopUiNode.TableColumn("value", raw("Value"), 100)), rows(),
                    DesktopUiNode.SelectionMode.MULTIPLE, List.of(), true);
            case "tree-multiple" -> new DesktopUiNode.Tree(id, id + ".value",
                    List.of(new DesktopUiNode.TreeItem("one", raw("One"), List.of()),
                            new DesktopUiNode.TreeItem("child", raw("Child"), List.of()),
                            new DesktopUiNode.TreeItem("three", raw("Three"), List.of())),
                    DesktopUiNode.SelectionMode.MULTIPLE, List.of(), true);
            case "stale-action" -> new DesktopUiNode.Button(id, id + ".action", raw("Run"), null,
                    DesktopUiNode.ButtonStyle.PRIMARY, true);
            default -> throw new IllegalArgumentException("unknown desktop UI scenario: " + id);
        };
    }

    private static DesktopUiNode.Choice choice(
            String id, DesktopUiNode.ChoiceStyle style, DesktopUiNode.SelectionMode mode) {
        return new DesktopUiNode.Choice(id, id + ".value", raw(label(id)), null, style, mode,
                List.of(new DesktopUiNode.Option("one", raw("One"), true),
                        new DesktopUiNode.Option("two", raw("Two"), true),
                        new DesktopUiNode.Option("three", raw("Three"), true)), List.of(), true);
    }

    private static List<DesktopUiNode.TableRow> rows() {
        return List.of(new DesktopUiNode.TableRow("one", List.of("One")),
                new DesktopUiNode.TableRow("two", List.of("Two")),
                new DesktopUiNode.TableRow("three", List.of("Three")));
    }

    private static int optionIndex(String id) {
        return switch (id) {
            case "one" -> 0;
            case "two" -> 1;
            case "three" -> 2;
            default -> throw new IllegalArgumentException("unknown option: " + id);
        };
    }

    private static String label(String id) {
        if (id.isEmpty()) return id;
        return Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    private static TreePath path(JTree tree, String label) {
        for (int row = 0; row < tree.getRowCount(); row++) {
            TreePath path = tree.getPathForRow(row);
            if (path.getLastPathComponent().toString().equals(label)) return path;
        }
        throw new IllegalArgumentException("tree item not found: " + label);
    }

    private static JPasswordField password(Component root) {
        return descendants(root, JPasswordField.class).get(0);
    }

    private static List<Scenario> scenarios() throws Exception {
        List<String> lines = Files.readAllLines(fixture(), StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals("id\toperations\texpected")) {
            throw new IllegalStateException("invalid desktop UI conformance header");
        }
        List<Scenario> scenarios = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 3) throw new IllegalStateException("invalid desktop UI conformance row: " + line);
            scenarios.add(new Scenario(fields[0], List.of(fields[1].split("\\|", -1)), fields[2]));
        }
        return List.copyOf(scenarios);
    }

    private static Path fixture() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("test-fixtures/desktop-ui/conformance-cases.tsv");
            if (Files.isRegularFile(candidate)) return candidate;
            directory = directory.getParent();
        }
        throw new IllegalStateException("desktop UI conformance fixture not found");
    }

    private static <T extends Component> List<T> descendants(Component root, Class<T> type) {
        List<T> matches = new ArrayList<>();
        if (type.isInstance(root)) matches.add(type.cast(root));
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) matches.addAll(descendants(child, type));
        }
        return matches;
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static DesktopUiNode.TextToken raw(String value) {
        return DesktopUiNode.TextToken.raw(value);
    }

    private record Scenario(String id, List<String> operations, String expected) { }

    private static final class TestModel implements DesktopUiModel {
        private DesktopUiSnapshot snapshot;
        private final List<DesktopUiNode.Event> accepted = new ArrayList<>();

        private TestModel(DesktopUiNode node) {
            snapshot = snapshot(1L, node);
        }

        @Override public synchronized DesktopUiSnapshot snapshot() {
            return snapshot;
        }

        @Override public synchronized void dispatch(DesktopUiNode.Event event) {
            boolean current = event.type() == DesktopUiNode.EventType.ACTIVATE
                    ? event.documentRevision() == snapshot.revision()
                    : Objects.equals(snapshot.interactionRevisions().get(event.nodeId()),
                    event.interactionRevision());
            if (!current) return;
            accepted.add(event);
            snapshot = new DesktopUiSnapshot(snapshot.revision() + 1L,
                    snapshot.document(), snapshot.interactionRevisions());
        }

        private synchronized void publish(DesktopUiNode node) {
            snapshot = snapshot(snapshot.revision() + 1L, node);
        }

        private synchronized String acceptedValues() {
            return accepted.stream().map(event -> event.type() == DesktopUiNode.EventType.ACTIVATE
                    ? "activate" : String.join(",", event.value().values()))
                    .reduce((left, right) -> left + ">" + right).orElse("");
        }

        private static DesktopUiSnapshot snapshot(long revision, DesktopUiNode node) {
            DesktopUiDocument document = new DesktopUiDocument(List.of(
                    new DesktopUiDocument.Page("page", raw("Page"), node)));
            return new DesktopUiSnapshot(revision, document, Map.of(node.id(), 1L));
        }
    }
}
