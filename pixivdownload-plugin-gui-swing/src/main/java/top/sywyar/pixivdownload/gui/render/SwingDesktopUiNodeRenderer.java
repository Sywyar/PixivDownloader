package top.sywyar.pixivdownload.gui.render;

import top.sywyar.pixivdownload.gui.theme.GuiInputStyleNormalizer;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.Alignment;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.Axis;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.Event;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.EventType;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.Kind;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.Option;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.SelectionMode;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextToken;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TreeItem;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.Value;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.JTextComponent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Swing adapter for every node in the stable declarative desktop UI vocabulary. */
public final class SwingDesktopUiNodeRenderer {
    /** Client property used to preserve toolkit state across immutable document revisions. */
    public static final String NODE_ID_PROPERTY = "pixivdownload.desktopUi.nodeId";

    private final Function<TextToken, String> textResolver;
    private final Consumer<Event> eventSink;
    private boolean eventsSuppressed;

    public SwingDesktopUiNodeRenderer(Function<TextToken, String> textResolver, Consumer<Event> eventSink) {
        this.textResolver = Objects.requireNonNull(textResolver, "textResolver");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
    }

    /** Returns the complete node-kind set implemented by this renderer. */
    public static Set<Kind> supportedKinds() {
        return Set.copyOf(EnumSet.allOf(Kind.class));
    }

    /** Runs toolkit state restoration without projecting it as user input. */
    public void withoutEvents(Runnable action) {
        boolean previous = eventsSuppressed;
        eventsSuppressed = true;
        try {
            action.run();
        } finally {
            eventsSuppressed = previous;
        }
    }

    /** Validates and renders one complete declarative subtree. */
    public JComponent render(DesktopUiNode root) {
        DesktopUiNode.validateTree(root);
        return renderNode(root);
    }

    private JComponent renderNode(DesktopUiNode node) {
        JComponent component;
        if (node instanceof DesktopUiNode.Container value) component = renderContainer(value);
        else if (node instanceof DesktopUiNode.Dock value) component = renderDock(value);
        else if (node instanceof DesktopUiNode.Surface value) component = renderSurface(value);
        else if (node instanceof DesktopUiNode.Group value) component = renderGroup(value);
        else if (node instanceof DesktopUiNode.Form value) component = renderForm(value);
        else if (node instanceof DesktopUiNode.Tabs value) component = renderTabs(value);
        else if (node instanceof DesktopUiNode.Scroll value) component = scrollContent(renderNode(value.content()));
        else if (node instanceof DesktopUiNode.Split value) component = renderSplit(value);
        else if (node instanceof DesktopUiNode.Text value) component = renderText(value);
        else if (node instanceof DesktopUiNode.Image value) component = renderImage(value);
        else if (node instanceof DesktopUiNode.Separator value) component = new JSeparator(
                value.axis() == Axis.HORIZONTAL ? SwingConstants.HORIZONTAL : SwingConstants.VERTICAL);
        else if (node instanceof DesktopUiNode.Spacer value) component = new Box.Filler(
                new Dimension(value.width(), value.height()),
                new Dimension(value.width(), value.height()),
                new Dimension(value.width(), value.height()));
        else if (node instanceof DesktopUiNode.Progress value) component = renderProgress(value);
        else if (node instanceof DesktopUiNode.TextInput value) component = renderTextInput(value);
        else if (node instanceof DesktopUiNode.Toggle value) component = renderToggle(value);
        else if (node instanceof DesktopUiNode.Choice value) component = renderChoice(value);
        else if (node instanceof DesktopUiNode.NumberInput value) component = renderNumber(value);
        else if (node instanceof DesktopUiNode.Table value) component = renderTable(value);
        else if (node instanceof DesktopUiNode.Tree value) component = renderTree(value);
        else if (node instanceof DesktopUiNode.Button value) component = renderButton(value);
        else if (node instanceof DesktopUiNode.Link value) component = renderLink(value);
        else throw new IllegalArgumentException("Unsupported desktop UI node: " + node.getClass().getName());
        component.putClientProperty(NODE_ID_PROPERTY, node.id());
        return component;
    }

    private JComponent renderContainer(DesktopUiNode.Container node) {
        JPanel panel;
        if (node.layout() == DesktopUiNode.ContainerLayout.GRID) {
            panel = new JPanel(new GridLayout(0, node.columns(), node.gap(), node.gap()));
        } else if (node.layout() == DesktopUiNode.ContainerLayout.FLOW) {
            panel = new JPanel(new FlowLayout(flowAlignment(node.alignment()), node.gap(), node.gap()));
        } else {
            panel = new JPanel();
            panel.setLayout(new BoxLayout(panel,
                    node.layout() == DesktopUiNode.ContainerLayout.COLUMN ? BoxLayout.Y_AXIS : BoxLayout.X_AXIS));
        }
        panel.setOpaque(false);
        boolean vertical = node.layout() == DesktopUiNode.ContainerLayout.COLUMN;
        for (int index = 0; index < node.children().size(); index++) {
            JComponent child = renderNode(node.children().get(index));
            applyBoxAlignment(child, node.alignment(), vertical);
            panel.add(child);
            if (index + 1 < node.children().size() && node.gap() > 0
                    && (node.layout() == DesktopUiNode.ContainerLayout.COLUMN
                    || node.layout() == DesktopUiNode.ContainerLayout.ROW)) {
                panel.add(vertical ? Box.createVerticalStrut(node.gap()) : Box.createHorizontalStrut(node.gap()));
            }
        }
        return panel;
    }

    private JComponent renderDock(DesktopUiNode.Dock node) {
        JPanel panel = new JPanel(new BorderLayout(node.gap(), node.gap()));
        panel.setOpaque(false);
        addDock(panel, node.top(), BorderLayout.NORTH);
        addDock(panel, node.center(), BorderLayout.CENTER);
        addDock(panel, node.bottom(), BorderLayout.SOUTH);
        addDock(panel, node.start(), BorderLayout.LINE_START);
        addDock(panel, node.end(), BorderLayout.LINE_END);
        return panel;
    }

    private void addDock(JPanel panel, DesktopUiNode child, String constraint) {
        if (child != null) panel.add(renderNode(child), constraint);
    }

    private JComponent renderSurface(DesktopUiNode.Surface node) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(renderNode(node.content()), BorderLayout.CENTER);
        DesktopUiNode.Insets value = node.padding();
        javax.swing.border.Border padding = BorderFactory.createEmptyBorder(
                value.top(), value.start(), value.bottom(), value.end());
        Color borderColor = semanticColor(node.style());
        if (node.style() == DesktopUiNode.SurfaceStyle.PLAIN) {
            panel.setOpaque(false);
            panel.setBorder(padding);
        } else {
            panel.setOpaque(node.style() != DesktopUiNode.SurfaceStyle.CARD);
            if (panel.isOpaque()) panel.setBackground(blend(panel.getBackground(), borderColor, 0.10f));
            panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(borderColor), padding));
        }
        if (node.fillWidth() || node.fillHeight()) {
            Dimension preferred = panel.getPreferredSize();
            panel.setMaximumSize(new Dimension(
                    node.fillWidth() ? Integer.MAX_VALUE : preferred.width,
                    node.fillHeight() ? Integer.MAX_VALUE : preferred.height));
        }
        return panel;
    }

    private JComponent renderGroup(DesktopUiNode.Group node) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(text(node.title())),
                BorderFactory.createEmptyBorder(10, 12, 12, 12)));
        panel.add(renderNode(node.content()), BorderLayout.CENTER);
        return panel;
    }

    private JComponent renderForm(DesktopUiNode.Form node) {
        FormPanel panel = new FormPanel(node.formStyle() == DesktopUiNode.FormStyle.RESPONSIVE);
        panel.setOpaque(false);
        String suffix = optionalText(node.labelSuffix());
        int gridY = 0;
        for (DesktopUiNode.FormRow row : node.rows()) {
            JLabel label = new JLabel(text(row.label()) + suffix);
            label.setToolTipText(label.getText());
            if (node.formStyle() == DesktopUiNode.FormStyle.KEY_VALUE) label.setForeground(Color.GRAY);

            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridy = gridY;
            constraints.gridx = 0;
            constraints.weightx = 0;
            constraints.anchor = GridBagConstraints.WEST;
            constraints.fill = GridBagConstraints.NONE;
            constraints.insets = node.formStyle() != DesktopUiNode.FormStyle.RESPONSIVE
                    ? new Insets(6, 4, 6, 16) : new Insets(4, 4, 4, 4);
            panel.add(label, constraints);

            JComponent content = renderFormContent(row.content());
            constraints.gridx = 1;
            constraints.weightx = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            panel.add(content, constraints);

            if (row.trailing() != null) {
                constraints.gridx = 2;
                constraints.weightx = 0;
                constraints.fill = GridBagConstraints.NONE;
                panel.add(renderNode(row.trailing()), constraints);
            }

            String help = optionalText(row.help());
            JTextArea description = null;
            if (!help.isBlank()) {
                description = secondaryTextArea(help);
                constraints.gridy = ++gridY;
                constraints.gridx = 1;
                constraints.weightx = 1;
                constraints.gridwidth = row.trailing() == null ? 1 : 2;
                constraints.fill = GridBagConstraints.HORIZONTAL;
                constraints.insets = new Insets(0, 4, 6, 4);
                panel.add(description, constraints);
            }
            panel.register(label, description);
            gridY++;
        }
        return panel;
    }

    private JComponent renderFormContent(DesktopUiNode node) {
        JComponent component;
        if (node instanceof DesktopUiNode.TextInput value) component = renderTextInput(value, false);
        else if (node instanceof DesktopUiNode.Toggle value) component = renderToggle(value, false);
        else if (node instanceof DesktopUiNode.Choice value) component = renderChoice(value, false);
        else if (node instanceof DesktopUiNode.NumberInput value) component = renderNumber(value, false);
        else if (node instanceof DesktopUiNode.Container value) component = renderFormContainer(value);
        else if (node instanceof DesktopUiNode.Dock value) component = renderFormDock(value);
        else component = renderNode(node);
        component.putClientProperty(NODE_ID_PROPERTY, node.id());
        return component;
    }

    private JComponent renderNestedFormContent(DesktopUiNode node) {
        if (node instanceof DesktopUiNode.Toggle) return renderNode(node);
        return renderFormContent(node);
    }

    private JComponent renderFormContainer(DesktopUiNode.Container node) {
        JPanel panel;
        if (node.layout() == DesktopUiNode.ContainerLayout.GRID) {
            panel = new JPanel(new GridLayout(0, node.columns(), node.gap(), node.gap()));
        } else if (node.layout() == DesktopUiNode.ContainerLayout.FLOW) {
            panel = new JPanel(new FlowLayout(flowAlignment(node.alignment()), node.gap(), node.gap()));
        } else {
            panel = new JPanel();
            panel.setLayout(new BoxLayout(panel,
                    node.layout() == DesktopUiNode.ContainerLayout.COLUMN ? BoxLayout.Y_AXIS : BoxLayout.X_AXIS));
        }
        panel.setOpaque(false);
        boolean vertical = node.layout() == DesktopUiNode.ContainerLayout.COLUMN;
        for (int index = 0; index < node.children().size(); index++) {
            JComponent child = renderNestedFormContent(node.children().get(index));
            applyBoxAlignment(child, node.alignment(), vertical);
            panel.add(child);
            if (index + 1 < node.children().size() && node.gap() > 0
                    && (node.layout() == DesktopUiNode.ContainerLayout.COLUMN
                    || node.layout() == DesktopUiNode.ContainerLayout.ROW)) {
                panel.add(vertical ? Box.createVerticalStrut(node.gap()) : Box.createHorizontalStrut(node.gap()));
            }
        }
        return panel;
    }

    private JComponent renderFormDock(DesktopUiNode.Dock node) {
        JPanel panel = new JPanel(new BorderLayout(node.gap(), node.gap()));
        panel.setOpaque(false);
        addFormDock(panel, node.top(), BorderLayout.NORTH);
        addFormDock(panel, node.center(), BorderLayout.CENTER);
        addFormDock(panel, node.bottom(), BorderLayout.SOUTH);
        addFormDock(panel, node.start(), BorderLayout.LINE_START);
        addFormDock(panel, node.end(), BorderLayout.LINE_END);
        return panel;
    }

    private void addFormDock(JPanel panel, DesktopUiNode child, String constraint) {
        if (child != null) panel.add(renderNestedFormContent(child), constraint);
    }

    private JComponent renderTabs(DesktopUiNode.Tabs node) {
        JTabbedPane tabs = new JTabbedPane();
        for (DesktopUiNode.Tab tab : node.tabs()) {
            tabs.addTab(text(tab.title()), renderNode(tab.content()));
        }
        return tabs;
    }

    private JComponent renderSplit(DesktopUiNode.Split node) {
        JSplitPane split = new JSplitPane(
                node.axis() == Axis.HORIZONTAL ? JSplitPane.HORIZONTAL_SPLIT : JSplitPane.VERTICAL_SPLIT,
                renderNode(node.first()), renderNode(node.second()));
        split.setResizeWeight(node.resizeWeight());
        return split;
    }

    private JComponent renderText(DesktopUiNode.Text node) {
        String value = text(node.text());
        if (node.style() == DesktopUiNode.TextStyle.BULLET) value = "• " + value;
        JComponent component;
        if (node.selectable() || node.style() == DesktopUiNode.TextStyle.CODE) {
            JTextArea area = new JTextArea(value);
            area.setEditable(false);
            area.setFocusable(node.selectable());
            area.setOpaque(false);
            area.setLineWrap(node.wrap());
            area.setWrapStyleWord(node.wrap());
            area.setBorder(BorderFactory.createEmptyBorder());
            component = area;
        } else {
            JLabel label = new JLabel(node.wrap() ? wrappedHtml(value, node.textAlignment()) : value);
            label.setHorizontalAlignment(swingAlignment(node.textAlignment()));
            component = label;
        }
        applyTextStyle(component, node.style());
        return component;
    }

    private static int swingAlignment(DesktopUiNode.TextAlignment alignment) {
        return switch (alignment) {
            case START -> SwingConstants.LEADING;
            case CENTER -> SwingConstants.CENTER;
            case END -> SwingConstants.TRAILING;
        };
    }

    private static String wrappedHtml(String value, DesktopUiNode.TextAlignment alignment) {
        String align = switch (alignment) {
            case START -> "left";
            case CENTER -> "center";
            case END -> "right";
        };
        return "<html><div style='text-align:" + align + "'>" + value
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\r\n", "<br>").replace("\n", "<br>") + "</div></html>";
    }

    private JComponent renderImage(DesktopUiNode.Image node) {
        ImageIcon source = new ImageIcon(node.image().bytes());
        ImageIcon icon = source;
        if (node.scaleMode() != DesktopUiNode.ScaleMode.NONE) {
            int width = node.preferredWidth();
            int height = node.preferredHeight();
            if (node.scaleMode() == DesktopUiNode.ScaleMode.FIT && source.getIconWidth() > 0 && source.getIconHeight() > 0) {
                double ratio = Math.min((double) width / source.getIconWidth(), (double) height / source.getIconHeight());
                width = Math.max(1, (int) Math.round(source.getIconWidth() * ratio));
                height = Math.max(1, (int) Math.round(source.getIconHeight() * ratio));
            }
            icon = new ImageIcon(source.getImage().getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH));
        }
        JLabel label = new JLabel(icon);
        String alt = text(node.altText());
        label.setToolTipText(alt);
        label.getAccessibleContext().setAccessibleDescription(alt);
        return label;
    }

    private JComponent renderProgress(DesktopUiNode.Progress node) {
        JProgressBar progress = new JProgressBar(0, 1_000);
        progress.setIndeterminate(node.indeterminate());
        if (!node.indeterminate()) progress.setValue((int) Math.round(node.progress() * 1_000));
        if (node.text() != null) {
            progress.setString(text(node.text()));
            progress.setStringPainted(true);
        }
        return progress;
    }

    private JComponent renderTextInput(DesktopUiNode.TextInput node) {
        return renderTextInput(node, true);
    }

    private JComponent renderTextInput(DesktopUiNode.TextInput node, boolean includeLabel) {
        JComponent input;
        if (node.inputKind() == DesktopUiNode.InputKind.MULTILINE) {
            JTextArea area = new JTextArea(node.value(), node.rows(), node.columns());
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            listen(area, node.id(), node.bindingId(), () -> Value.text(area.getText()));
            input = scroll(area);
        } else if (node.inputKind() == DesktopUiNode.InputKind.PASSWORD) {
            JPasswordField password = new JPasswordField(node.value(), node.columns());
            listen(password, node.id(), node.bindingId(), () -> {
                char[] chars = password.getPassword();
                try {
                    return Value.text(new String(chars));
                } finally {
                    Arrays.fill(chars, '\0');
                }
            });
            input = password;
        } else {
            JTextField field = new JTextField(node.value(), node.columns());
            listen(field, node.id(), node.bindingId(), () -> Value.text(field.getText()));
            if (node.inputKind() == DesktopUiNode.InputKind.FILE
                    || node.inputKind() == DesktopUiNode.InputKind.DIRECTORY) {
                JPanel picker = new JPanel(new BorderLayout(4, 0));
                picker.setOpaque(false);
                picker.add(field, BorderLayout.CENTER);
                JButton browse = new JButton(fileChooserButtonText());
                browse.addActionListener(event -> choosePath(field,
                        node.inputKind() == DesktopUiNode.InputKind.DIRECTORY));
                picker.add(browse, BorderLayout.EAST);
                input = picker;
            } else {
                input = field;
            }
        }
        setEnabledRecursively(input, node.enabled());
        tagInputControl(input, node.id() + "@" + node.stateRevision());
        GuiInputStyleNormalizer.apply(input);
        return includeLabel ? labeled(node.label(), node.help(), input) : input;
    }

    private static void tagInputControl(Component component, String nodeId) {
        if (component instanceof JComponent value) value.putClientProperty(NODE_ID_PROPERTY, nodeId);
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                if (child instanceof JTextComponent) tagInputControl(child, nodeId);
            }
        }
    }

    private JComponent renderToggle(DesktopUiNode.Toggle node) {
        return renderToggle(node, true);
    }

    private JComponent renderToggle(DesktopUiNode.Toggle node, boolean includeLabel) {
        javax.swing.AbstractButton toggle = node.toggleStyle() == DesktopUiNode.ToggleStyle.SWITCH
                ? new JToggleButton(includeLabel ? text(node.label()) : "", node.selected())
                : new JCheckBox(includeLabel ? text(node.label()) : "", node.selected());
        toggle.setEnabled(node.enabled());
        toggle.setToolTipText(optionalText(node.help()));
        toggle.addActionListener(event -> emit(EventType.CHANGE, node.id(), node.bindingId(),
                Value.bool(toggle.isSelected())));
        return toggle;
    }

    private JComponent renderChoice(DesktopUiNode.Choice node) {
        return renderChoice(node, true);
    }

    private JComponent renderChoice(DesktopUiNode.Choice node, boolean includeLabel) {
        JComponent control = switch (node.choiceStyle()) {
            case COMBO_BOX -> renderCombo(node);
            case LIST -> renderList(node);
            case RADIO_BUTTONS -> renderRadioButtons(node);
            case CHECK_BOXES -> renderCheckBoxes(node);
        };
        if (!node.enabled()) setEnabledRecursively(control, false);
        return includeLabel ? labeled(node.label(), node.help(), control) : control;
    }

    private JComponent renderCombo(DesktopUiNode.Choice node) {
        List<OptionView> views = node.options().stream().map(this::optionView).toList();
        JComboBox<OptionView> combo = new JComboBox<>(views.toArray(OptionView[]::new));
        combo.setRenderer(optionRenderer());
        node.selectedIds().stream().findFirst().flatMap(id -> views.stream().filter(view -> view.id().equals(id)).findFirst())
                .ifPresent(combo::setSelectedItem);
        OptionView[] previous = {(OptionView) combo.getSelectedItem()};
        boolean[] restoring = {false};
        combo.addActionListener(event -> {
            if (restoring[0]) return;
            OptionView selected = (OptionView) combo.getSelectedItem();
            if (selected != null && !selected.enabled()) {
                restoring[0] = true;
                combo.setSelectedItem(previous[0]);
                restoring[0] = false;
                return;
            }
            previous[0] = selected;
            emit(EventType.SELECTION, node.id(), node.bindingId(),
                    Value.selection(selected == null ? null : selected.id()));
        });
        return combo;
    }

    private JComponent renderList(DesktopUiNode.Choice node) {
        List<OptionView> views = node.options().stream().map(this::optionView).toList();
        JList<OptionView> list = new JList<>(views.toArray(OptionView[]::new));
        list.setCellRenderer(optionRenderer());
        list.setSelectionMode(node.selectionMode() == SelectionMode.SINGLE
                ? ListSelectionModel.SINGLE_SELECTION : ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        int[] selected = java.util.stream.IntStream.range(0, views.size())
                .filter(index -> node.selectedIds().contains(views.get(index).id())).toArray();
        list.setSelectedIndices(selected);
        int[][] previous = {selected};
        boolean[] restoring = {false};
        list.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || restoring[0]) return;
            if (list.getSelectedValuesList().stream().anyMatch(option -> !option.enabled())) {
                restoring[0] = true;
                list.setSelectedIndices(previous[0]);
                restoring[0] = false;
                return;
            }
            previous[0] = list.getSelectedIndices();
            emit(EventType.SELECTION, node.id(), node.bindingId(),
                    selectionValue(node.selectionMode(), list.getSelectedValuesList().stream().map(OptionView::id).toList()));
        });
        return scroll(list);
    }

    private JComponent renderRadioButtons(DesktopUiNode.Choice node) {
        JPanel panel = verticalPanel();
        ButtonGroup group = new ButtonGroup();
        for (Option option : node.options()) {
            JRadioButton button = new JRadioButton(text(option.label()), node.selectedIds().contains(option.id()));
            button.setEnabled(node.enabled() && option.enabled());
            button.addActionListener(event -> emit(EventType.SELECTION, node.id(), node.bindingId(),
                    Value.selection(option.id())));
            group.add(button);
            panel.add(button);
        }
        return panel;
    }

    private JComponent renderCheckBoxes(DesktopUiNode.Choice node) {
        JPanel panel = verticalPanel();
        Map<String, JCheckBox> boxes = new LinkedHashMap<>();
        for (Option option : node.options()) {
            JCheckBox box = new JCheckBox(text(option.label()), node.selectedIds().contains(option.id()));
            box.setEnabled(node.enabled() && option.enabled());
            boxes.put(option.id(), box);
            box.addActionListener(event -> emit(EventType.SELECTION, node.id(), node.bindingId(),
                    Value.selections(boxes.entrySet().stream().filter(entry -> entry.getValue().isSelected())
                            .map(Map.Entry::getKey).toList())));
            panel.add(box);
        }
        return panel;
    }

    private JComponent renderNumber(DesktopUiNode.NumberInput node) {
        return renderNumber(node, true);
    }

    private JComponent renderNumber(DesktopUiNode.NumberInput node, boolean includeLabel) {
        JComponent control;
        if (node.numberStyle() == DesktopUiNode.NumberStyle.SLIDER) {
            JSlider slider = new JSlider(node.minimum(), node.maximum(), node.value());
            slider.setMinorTickSpacing(node.step());
            slider.setSnapToTicks(true);
            slider.addChangeListener(event -> emit(EventType.CHANGE, node.id(), node.bindingId(),
                    Value.number(slider.getValue())));
            control = slider;
        } else {
            JSpinner spinner = new JSpinner(new SpinnerNumberModel(
                    node.value(), node.minimum(), node.maximum(), node.step()));
            spinner.addChangeListener(event -> emit(EventType.CHANGE, node.id(), node.bindingId(),
                    Value.number((Number) spinner.getValue())));
            control = spinner;
        }
        control.setEnabled(node.enabled());
        GuiInputStyleNormalizer.apply(control);
        return includeLabel ? labeled(node.label(), node.help(), control) : control;
    }

    private JComponent renderTable(DesktopUiNode.Table node) {
        String[] columnNames = node.columns().stream().map(column -> text(column.label())).toArray(String[]::new);
        Object[][] rows = node.rows().stream().map(row -> row.cells().toArray()).toArray(Object[][]::new);
        JTable table = new JTable(new DefaultTableModel(rows, columnNames) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        });
        table.setEnabled(node.enabled());
        table.setSelectionMode(node.selectionMode() == SelectionMode.SINGLE
                ? ListSelectionModel.SINGLE_SELECTION : ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        for (int index = 0; index < node.columns().size(); index++) {
            int width = node.columns().get(index).preferredWidth();
            if (width > 0) table.getColumnModel().getColumn(index).setPreferredWidth(width);
        }
        for (int index = 0; index < node.rows().size(); index++) {
            if (node.selectedRowIds().contains(node.rows().get(index).id())) table.addRowSelectionInterval(index, index);
        }
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                List<String> ids = Arrays.stream(table.getSelectedRows())
                        .mapToObj(index -> node.rows().get(table.convertRowIndexToModel(index)).id()).toList();
                emit(EventType.SELECTION, node.id(), node.bindingId(), selectionValue(node.selectionMode(), ids));
            }
        });
        return scroll(table);
    }

    private JComponent renderTree(DesktopUiNode.Tree node) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
        Map<String, TreePath> paths = new LinkedHashMap<>();
        for (TreeItem item : node.items()) addTreeItem(root, item, paths);
        JTree tree = new JTree(root);
        tree.setRootVisible(false);
        tree.setEnabled(node.enabled());
        tree.getSelectionModel().setSelectionMode(node.selectionMode() == SelectionMode.SINGLE
                ? javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION
                : javax.swing.tree.TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.setSelectionPaths(node.selectedIds().stream().map(paths::get).filter(Objects::nonNull).toArray(TreePath[]::new));
        tree.addTreeSelectionListener(event -> {
            TreePath[] selected = tree.getSelectionPaths();
            List<String> ids = selected == null ? List.of() : Arrays.stream(selected)
                    .map(path -> ((TreeView) ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject()).id())
                    .toList();
            emit(EventType.SELECTION, node.id(), node.bindingId(), selectionValue(node.selectionMode(), ids));
        });
        return scroll(tree);
    }

    private JComponent renderButton(DesktopUiNode.Button node) {
        JButton button = new JButton(text(node.label()));
        button.setEnabled(node.enabled());
        button.setToolTipText(optionalText(node.help()));
        if (node.buttonStyle() == DesktopUiNode.ButtonStyle.DANGER) button.setForeground(new Color(180, 45, 45));
        if (node.buttonStyle() == DesktopUiNode.ButtonStyle.PRIMARY) button.putClientProperty("JButton.buttonType", "default");
        button.addActionListener(event -> emit(EventType.ACTIVATE, node.id(), node.actionId(), Value.empty()));
        return button;
    }

    private JComponent renderLink(DesktopUiNode.Link node) {
        JButton link = new JButton(text(node.label()));
        link.setEnabled(node.enabled());
        link.setToolTipText(optionalText(node.help()));
        link.setBorderPainted(false);
        link.setContentAreaFilled(false);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Color linkColor = UIManager.getColor("Component.linkColor");
        link.setForeground(linkColor == null ? new Color(45, 100, 180) : linkColor);
        link.addActionListener(event -> emit(EventType.ACTIVATE, node.id(), node.actionId(), Value.empty()));
        return link;
    }

    private JPanel labeled(TextToken labelToken, TextToken helpToken, JComponent control) {
        String help = optionalText(helpToken);
        if (!help.isBlank()) control.setToolTipText(help);
        GuiInputStyleNormalizer.apply(control);
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel(text(labelToken)), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        panel.add(control, constraints);
        if (!help.isBlank()) {
            JTextArea description = secondaryTextArea(help);
            constraints.gridy = 1;
            constraints.insets = new Insets(0, 4, 6, 4);
            panel.add(description, constraints);
        }
        return panel;
    }

    private static JTextArea secondaryTextArea(String value) {
        JTextArea description = new JTextArea(value);
        description.setEditable(false);
        description.setFocusable(false);
        description.setOpaque(false);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setBorder(BorderFactory.createEmptyBorder());
        description.setForeground(Color.GRAY);
        Font labelFont = UIManager.getFont("Label.font");
        description.setFont((labelFont == null ? description.getFont() : labelFont).deriveFont(Font.PLAIN, 11f));
        return description;
    }

    private void listen(JTextField field, String nodeId, String bindingId,
                        java.util.function.Supplier<Value> valueSupplier) {
        field.getDocument().addDocumentListener(documentListener(
                () -> emit(EventType.CHANGE, nodeId, bindingId, valueSupplier.get())));
    }

    private void listen(JTextArea area, String nodeId, String bindingId,
                        java.util.function.Supplier<Value> valueSupplier) {
        area.getDocument().addDocumentListener(documentListener(
                () -> emit(EventType.CHANGE, nodeId, bindingId, valueSupplier.get())));
    }

    private static DocumentListener documentListener(Runnable changed) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { changed.run(); }
            @Override public void removeUpdate(DocumentEvent event) { changed.run(); }
            @Override public void changedUpdate(DocumentEvent event) { changed.run(); }
        };
    }

    private void choosePath(JTextField field, boolean directory) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(directory ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(field) == JFileChooser.APPROVE_OPTION) {
            field.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void addTreeItem(DefaultMutableTreeNode parent, TreeItem item, Map<String, TreePath> paths) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(new TreeView(item.id(), text(item.label())));
        parent.add(node);
        paths.put(item.id(), new TreePath(node.getPath()));
        for (TreeItem child : item.children()) addTreeItem(node, child, paths);
    }

    private void emit(EventType type, String nodeId, String targetId, Value value) {
        if (!eventsSuppressed) eventSink.accept(new Event(type, nodeId, value));
    }

    private String text(TextToken token) {
        String resolved = textResolver.apply(token);
        if (resolved != null && !resolved.isBlank()) return resolved;
        if (!token.fallback().isBlank()) return token.fallback();
        return token.key();
    }

    private String optionalText(TextToken token) {
        return token == null ? "" : text(token);
    }

    private OptionView optionView(Option option) {
        return new OptionView(option.id(), text(option.label()), option.enabled());
    }

    private static DefaultListCellRenderer optionRenderer() {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focused) {
                Component component = super.getListCellRendererComponent(list, value, index, selected, focused);
                component.setEnabled(list.isEnabled() && (!(value instanceof OptionView option) || option.enabled()));
                return component;
            }
        };
    }

    private static Value selectionValue(SelectionMode mode, List<String> values) {
        return mode == SelectionMode.SINGLE
                ? Value.selection(values.isEmpty() ? null : values.get(0))
                : Value.selections(values);
    }

    private static JComponent scroll(Component content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getHorizontalScrollBar().setUnitIncrement(16);
        scroll.addMouseWheelListener(event -> {
            javax.swing.JScrollBar bar = scroll.getVerticalScrollBar();
            int maximum = Math.max(bar.getMinimum(), bar.getMaximum() - bar.getVisibleAmount());
            boolean atEdge = event.getPreciseWheelRotation() < 0d
                    ? bar.getValue() <= bar.getMinimum()
                    : event.getPreciseWheelRotation() > 0d && bar.getValue() >= maximum;
            if (!atEdge) return;
            JScrollPane parent = (JScrollPane) javax.swing.SwingUtilities.getAncestorOfClass(
                    JScrollPane.class, scroll.getParent());
            if (parent == null) return;
            parent.dispatchEvent(new java.awt.event.MouseWheelEvent(parent, event.getID(), event.getWhen(),
                    event.getModifiersEx(), event.getX(), event.getY(), event.getClickCount(),
                    event.isPopupTrigger(), event.getScrollType(), event.getScrollAmount(),
                    event.getWheelRotation()));
            event.consume();
        });
        return scroll;
    }

    private static JComponent scrollContent(JComponent content) {
        ViewportPanel viewport = new ViewportPanel();
        viewport.add(content, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(viewport);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getHorizontalScrollBar().setUnitIncrement(16);
        scroll.addHierarchyListener(new java.awt.event.HierarchyListener() {
            @Override
            public void hierarchyChanged(java.awt.event.HierarchyEvent event) {
                if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) == 0
                        || !scroll.isShowing()) return;
                javax.swing.SwingUtilities.invokeLater(
                        () -> scroll.getViewport().setViewPosition(new java.awt.Point(0, 0)));
                scroll.removeHierarchyListener(this);
            }
        });
        return scroll;
    }

    private static JPanel verticalPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static void setEnabledRecursively(Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) setEnabledRecursively(child, enabled);
        }
    }

    private static String fileChooserButtonText() {
        String label = UIManager.getString("FileChooser.openButtonText");
        return label == null || label.isBlank() ? "…" : label;
    }

    private static int flowAlignment(Alignment alignment) {
        return switch (alignment) {
            case CENTER -> FlowLayout.CENTER;
            case END -> FlowLayout.TRAILING;
            case START, STRETCH -> FlowLayout.LEADING;
        };
    }

    private static void applyBoxAlignment(JComponent child, Alignment alignment, boolean vertical) {
        float value = switch (alignment) {
            case START, STRETCH -> Component.LEFT_ALIGNMENT;
            case CENTER -> Component.CENTER_ALIGNMENT;
            case END -> Component.RIGHT_ALIGNMENT;
        };
        if (vertical) child.setAlignmentX(value); else child.setAlignmentY(value);
        if (alignment == Alignment.STRETCH) {
            Dimension preferred = child.getPreferredSize();
            child.setMaximumSize(vertical
                    ? new Dimension(Integer.MAX_VALUE, preferred.height)
                    : new Dimension(preferred.width, Integer.MAX_VALUE));
        }
    }

    private static void applyTextStyle(JComponent component, DesktopUiNode.TextStyle style) {
        Font base = component.getFont();
        switch (style) {
            case TITLE -> component.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 6f));
            case HEADING -> component.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 2f));
            case EMPHASIS -> component.setFont(base.deriveFont(Font.BOLD));
            case SECONDARY -> component.setForeground(Color.GRAY);
            case CAPTION -> component.setFont(base.deriveFont(Font.PLAIN, Math.max(9f, base.getSize2D() - 2f)));
            case CODE -> component.setFont(new Font(Font.MONOSPACED, Font.PLAIN, base.getSize()));
            case SUCCESS -> component.setForeground(new Color(0, 128, 80));
            case WARNING -> component.setForeground(new Color(180, 110, 0));
            case ERROR -> component.setForeground(new Color(180, 45, 45));
            case BODY, BULLET -> { }
        }
    }

    private static Color semanticColor(DesktopUiNode.SurfaceStyle style) {
        Color fallback = UIManager.getColor("Component.borderColor");
        if (fallback == null) fallback = new Color(150, 150, 150);
        return switch (style) {
            case INFO -> new Color(55, 120, 190);
            case SUCCESS -> new Color(0, 128, 80);
            case WARNING -> new Color(190, 120, 0);
            case ERROR -> new Color(180, 45, 45);
            case MUTED -> new Color(120, 120, 120);
            case PLAIN, CARD -> fallback;
        };
    }

    private static Color blend(Color base, Color accent, float ratio) {
        float keep = 1f - ratio;
        return new Color(
                Math.round(base.getRed() * keep + accent.getRed() * ratio),
                Math.round(base.getGreen() * keep + accent.getGreen() * ratio),
                Math.round(base.getBlue() * keep + accent.getBlue() * ratio));
    }

    private static final class FormPanel extends JPanel {
        private static final double LABEL_WIDTH_RATIO = 0.25d;
        private static final int MIN_LABEL_WIDTH = 96;
        private static final int MIN_DESCRIPTION_WIDTH = 120;
        private static final int LABEL_HEIGHT = 24;

        private final boolean responsive;
        private final List<JLabel> labels = new ArrayList<>();
        private final List<JTextArea> descriptions = new ArrayList<>();
        private final ComponentAdapter resizeListener = new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { refreshAndRelayout(); }
        };
        private Window observedWindow;

        private FormPanel(boolean responsive) {
            super(new GridBagLayout());
            this.responsive = responsive;
            addComponentListener(resizeListener);
            addHierarchyListener(event -> {
                long flags = event.getChangeFlags();
                if ((flags & (HierarchyEvent.PARENT_CHANGED | HierarchyEvent.SHOWING_CHANGED
                        | HierarchyEvent.DISPLAYABILITY_CHANGED)) != 0) {
                    updateWindowListener();
                    refreshAndRelayout();
                }
            });
        }

        private void register(JLabel label, JTextArea description) {
            labels.add(label);
            if (description != null) descriptions.add(description);
        }

        @Override public void doLayout() {
            refreshPreferredSizes();
            super.doLayout();
        }

        @Override public Dimension getPreferredSize() {
            refreshPreferredSizes();
            return super.getPreferredSize();
        }

        @Override public Dimension getMaximumSize() {
            Dimension preferred = getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, preferred.height);
        }

        private void updateWindowListener() {
            Window window = javax.swing.SwingUtilities.getWindowAncestor(this);
            if (window == observedWindow) return;
            if (observedWindow != null) observedWindow.removeComponentListener(resizeListener);
            observedWindow = window;
            if (observedWindow != null) observedWindow.addComponentListener(resizeListener);
        }

        private void refreshAndRelayout() {
            if (refreshPreferredSizes()) {
                revalidate();
                repaint();
            }
        }

        private boolean refreshPreferredSizes() {
            if (!responsive) return false;
            int width = availableWidth();
            if (width <= 0) return false;
            int labelWidth = Math.max(MIN_LABEL_WIDTH, (int) Math.round(width * LABEL_WIDTH_RATIO));
            boolean changed = false;
            for (JLabel label : labels) changed |= setFixedSize(label, labelWidth, LABEL_HEIGHT);
            int descriptionWidth = Math.max(MIN_DESCRIPTION_WIDTH, width - labelWidth - 24);
            for (JTextArea description : descriptions) {
                description.setPreferredSize(null);
                description.setMinimumSize(null);
                description.setMaximumSize(null);
                description.setSize(new Dimension(descriptionWidth, Integer.MAX_VALUE));
                changed |= setFixedSize(description, descriptionWidth,
                        description.getPreferredSize().height + 2);
            }
            return changed;
        }

        private int availableWidth() {
            if (getWidth() > 0) return getWidth();
            Component parent = getParent();
            if (parent != null && parent.getWidth() > 0) return parent.getWidth();
            JViewport viewport = (JViewport) javax.swing.SwingUtilities.getAncestorOfClass(JViewport.class, this);
            if (viewport != null && viewport.getWidth() > 0) return viewport.getWidth();
            Window window = javax.swing.SwingUtilities.getWindowAncestor(this);
            return window == null ? 0 : window.getWidth();
        }

        private static boolean setFixedSize(JComponent component, int width, int height) {
            Dimension next = new Dimension(width, height);
            if (next.equals(component.getPreferredSize())) return false;
            component.setPreferredSize(next);
            component.setMinimumSize(next);
            component.setMaximumSize(next);
            return true;
        }
    }

    private static final class ViewportPanel extends JPanel implements javax.swing.Scrollable {
        private ViewportPanel() {
            super(new BorderLayout());
            setOpaque(false);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return 16;
        }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private record OptionView(String id, String label, boolean enabled) {
        @Override public String toString() { return label; }
    }

    private record TreeView(String id, String label) {
        @Override public String toString() { return label; }
    }
}
