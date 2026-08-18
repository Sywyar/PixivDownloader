package top.sywyar.pixivdownload.gui.render;

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
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
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
    private final Function<TextToken, String> textResolver;
    private final Consumer<Event> eventSink;

    public SwingDesktopUiNodeRenderer(Function<TextToken, String> textResolver, Consumer<Event> eventSink) {
        this.textResolver = Objects.requireNonNull(textResolver, "textResolver");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
    }

    /** Returns the complete node-kind set implemented by this renderer. */
    public static Set<Kind> supportedKinds() {
        return Set.copyOf(EnumSet.allOf(Kind.class));
    }

    /** Validates and renders one complete declarative subtree. */
    public JComponent render(DesktopUiNode root) {
        DesktopUiNode.validateTree(root);
        return renderNode(root);
    }

    private JComponent renderNode(DesktopUiNode node) {
        if (node instanceof DesktopUiNode.Container value) return renderContainer(value);
        if (node instanceof DesktopUiNode.Group value) return renderGroup(value);
        if (node instanceof DesktopUiNode.Tabs value) return renderTabs(value);
        if (node instanceof DesktopUiNode.Scroll value) return scroll(renderNode(value.content()));
        if (node instanceof DesktopUiNode.Split value) return renderSplit(value);
        if (node instanceof DesktopUiNode.Text value) return renderText(value);
        if (node instanceof DesktopUiNode.Image value) return renderImage(value);
        if (node instanceof DesktopUiNode.Separator value) return new JSeparator(
                value.axis() == Axis.HORIZONTAL ? SwingConstants.HORIZONTAL : SwingConstants.VERTICAL);
        if (node instanceof DesktopUiNode.Spacer value) return new Box.Filler(
                new Dimension(value.width(), value.height()),
                new Dimension(value.width(), value.height()),
                new Dimension(value.width(), value.height()));
        if (node instanceof DesktopUiNode.Progress value) return renderProgress(value);
        if (node instanceof DesktopUiNode.TextInput value) return renderTextInput(value);
        if (node instanceof DesktopUiNode.Toggle value) return renderToggle(value);
        if (node instanceof DesktopUiNode.Choice value) return renderChoice(value);
        if (node instanceof DesktopUiNode.NumberInput value) return renderNumber(value);
        if (node instanceof DesktopUiNode.Table value) return renderTable(value);
        if (node instanceof DesktopUiNode.Tree value) return renderTree(value);
        if (node instanceof DesktopUiNode.Button value) return renderButton(value);
        if (node instanceof DesktopUiNode.Link value) return renderLink(value);
        throw new IllegalArgumentException("Unsupported desktop UI node: " + node.getClass().getName());
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

    private JComponent renderGroup(DesktopUiNode.Group node) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createTitledBorder(text(node.title())));
        panel.add(renderNode(node.content()), BorderLayout.CENTER);
        return panel;
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
        JComponent component;
        if (node.wrap() || node.selectable() || node.style() == DesktopUiNode.TextStyle.CODE) {
            JTextArea area = new JTextArea(value);
            area.setEditable(false);
            area.setFocusable(node.selectable());
            area.setOpaque(false);
            area.setLineWrap(node.wrap());
            area.setWrapStyleWord(node.wrap());
            area.setBorder(BorderFactory.createEmptyBorder());
            component = area;
        } else {
            component = new JLabel(value);
        }
        applyTextStyle(component, node.style());
        return component;
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
        return labeled(node.label(), node.help(), input);
    }

    private JComponent renderToggle(DesktopUiNode.Toggle node) {
        javax.swing.AbstractButton toggle = node.toggleStyle() == DesktopUiNode.ToggleStyle.SWITCH
                ? new JToggleButton(text(node.label()), node.selected())
                : new JCheckBox(text(node.label()), node.selected());
        toggle.setEnabled(node.enabled());
        toggle.setToolTipText(optionalText(node.help()));
        toggle.addActionListener(event -> emit(EventType.CHANGE, node.id(), node.bindingId(),
                Value.bool(toggle.isSelected())));
        return toggle;
    }

    private JComponent renderChoice(DesktopUiNode.Choice node) {
        JComponent control = switch (node.choiceStyle()) {
            case COMBO_BOX -> renderCombo(node);
            case LIST -> renderList(node);
            case RADIO_BUTTONS -> renderRadioButtons(node);
            case CHECK_BOXES -> renderCheckBoxes(node);
        };
        if (!node.enabled()) setEnabledRecursively(control, false);
        return labeled(node.label(), node.help(), control);
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
        return labeled(node.label(), node.help(), control);
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
        JPanel panel = new JPanel(new BorderLayout(8, 4));
        panel.setOpaque(false);
        JLabel label = new JLabel(text(labelToken));
        label.setLabelFor(control);
        panel.add(label, BorderLayout.WEST);
        panel.add(control, BorderLayout.CENTER);
        String help = optionalText(helpToken);
        if (!help.isBlank()) {
            JLabel helpLabel = new JLabel(help);
            helpLabel.setForeground(Color.GRAY);
            panel.add(helpLabel, BorderLayout.SOUTH);
            control.setToolTipText(help);
        }
        return panel;
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
        eventSink.accept(new Event(type, nodeId, targetId, value));
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
            case CAPTION -> component.setFont(base.deriveFont(Font.PLAIN, Math.max(9f, base.getSize2D() - 2f)));
            case CODE -> component.setFont(new Font(Font.MONOSPACED, Font.PLAIN, base.getSize()));
            case SUCCESS -> component.setForeground(new Color(0, 128, 80));
            case WARNING -> component.setForeground(new Color(180, 110, 0));
            case ERROR -> component.setForeground(new Color(180, 45, 45));
            case BODY -> { }
        }
    }

    private record OptionView(String id, String label, boolean enabled) {
        @Override public String toString() { return label; }
    }

    private record TreeView(String id, String label) {
        @Override public String toString() { return label; }
    }
}
