package top.sywyar.pixivdownload.gui.panel.configtab;

import top.sywyar.pixivdownload.gui.config.ConfigFieldSpec;
import top.sywyar.pixivdownload.gui.config.ConfigSnapshot;
import top.sywyar.pixivdownload.gui.config.FieldRenderer;
import top.sywyar.pixivdownload.gui.config.GuiConfigSectionSpec;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.*;

/** 目录模板覆盖列表；所有输入仍由配置页统一加载、校验和保存。 */
final class DirectoryTemplatesEditor extends JPanel {
    private final ConfigSectionContext ctx;
    private final Map<String, FieldRenderer.RenderedField> inputs = new LinkedHashMap<>();
    private final Map<String, ConfigFieldSpec> specs = new LinkedHashMap<>();
    private final Map<String, JComboBox<String>> modes = new LinkedHashMap<>();
    private final Set<String> active = new LinkedHashSet<>();
    private final JButton add;
    private boolean updating;

    DirectoryTemplatesEditor(
            ConfigSectionContext ctx,
            GuiConfigSectionSpec section,
            List<ConfigFieldSpec> fields
    ) {
        this.ctx = ctx;
        this.add = new JButton(ctx.desktopHost().message("gui.config.directory.add"));
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(Component.LEFT_ALIGNMENT);
        if (fields.isEmpty()) return;
        var defaultSpec = fields.getFirst();
        addHelpHeader(section.title(), defaultSpec.helpText());
        var defaultInput = ConfigFieldRows.wrap(defaultSpec.key(), FieldRenderer.renderString(defaultSpec,
                ctx.desktopHost().message("gui.config.directory.default"), ""));
        ctx.registerField(defaultSpec, defaultInput);
        add(defaultInput.panel());
        var fallback = helpText(ctx.desktopHost().message("gui.config.directory.help"));
        fallback.setFocusable(false);
        add(fallback);
        Map<String, String> labels = new LinkedHashMap<>();
        section.fieldLayouts().forEach(layout -> labels.put(layout.fieldKey(), layout.cardLabel()));
        for (var spec : fields.subList(1, fields.size())) {
            var original = FieldRenderer.render(spec);
            var row = new JPanel(new BorderLayout(8, 4)) {
                @Override public Dimension getMaximumSize() {
                    return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
                }
            };
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
            var combo = new JComboBox<String>();
            combo.getAccessibleContext().setAccessibleName(ctx.desktopHost().message("gui.config.directory.mode"));
            combo.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(
                        JList<?> list,
                        Object value,
                        int index,
                        boolean selected,
                        boolean focus
                ) {
                    return super.getListCellRendererComponent(list, labels.getOrDefault(value, spec.label()), index, selected, focus);
                }
            });
            combo.setPreferredSize(new Dimension(130, combo.getPreferredSize().height));
            var remove = new JButton(ctx.desktopHost().message("gui.config.directory.remove"));
            remove.getAccessibleContext().setAccessibleName(remove.getText() + " " + labels.getOrDefault(spec.key(), spec.label()));
            row.add(combo, BorderLayout.WEST);
            row.add(original.control(), BorderLayout.CENTER);
            row.add(remove, BorderLayout.EAST);
            row.add(original.validationError(), BorderLayout.SOUTH);
            var input = new FieldRenderer.RenderedField(row, original.getValue(), original.setValue(), original.control(), original.validationError());
            inputs.put(spec.key(), input);
            specs.put(spec.key(), spec);
            modes.put(spec.key(), combo);
            ctx.registerField(spec, input);
            add(row);
            combo.addActionListener(event -> {
                String target = (String) combo.getSelectedItem();
                if (updating || target == null || target.equals(spec.key()) || active.contains(target)
                        || !available(target, snapshot()) || !original.control().isEnabled()) return;
                updating = true;
                try {
                    ctx.setFieldValue(target, ctx.currentFieldValue(spec.key()));
                    ctx.setFieldValue(spec.key(), "");
                    active.remove(spec.key());
                    active.add(target);
                } finally { updating = false; }
                refresh();
                inputs.get(target).control().requestFocusInWindow();
            });
            remove.addActionListener(event -> {
                active.remove(spec.key());
                ctx.setFieldValue(spec.key(), "");
                refresh();
                add.requestFocusInWindow();
            });
        }
        add.setAlignmentX(Component.LEFT_ALIGNMENT);
        add.addActionListener(event -> {
            var snapshot = snapshot();
            inputs.keySet().stream().filter(key -> !active.contains(key) && available(key, snapshot))
                    .findFirst().ifPresent(key -> {
                        active.add(key);
                        refresh();
                        modes.get(key).requestFocusInWindow();
                    });
        });
        add(add);
        refresh();
    }

    private void addHelpHeader(String title, String helpText) {
        var header = new JPanel(new BorderLayout(12, 0)) {
            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(new JLabel(title), BorderLayout.CENTER);
        add(header);
        if (helpText == null || helpText.isBlank()) return;
        var help = helpText(helpText);
        help.setVisible(false);
        var toggle = new JToggleButton(ctx.desktopHost().message("gui.config.directory.variables"));
        toggle.addActionListener(event -> {
            help.setVisible(toggle.isSelected());
            revalidate();
            repaint();
        });
        help.getAccessibleContext().setAccessibleName(toggle.getText());
        header.add(toggle, BorderLayout.EAST);
        add(help);
        var close = new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                if (toggle.isSelected()) {
                    toggle.doClick();
                    toggle.requestFocusInWindow();
                }
            }
        };
        for (var component : List.of(toggle, help)) {
            component.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "close-help");
            component.getActionMap().put("close-help", close);
        }
    }

    private static JTextArea helpText(String text) {
        var area = new JTextArea(text) {
            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFont(UIManager.getFont("Label.font"));
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        area.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        return area;
    }

    void valuesLoaded() {
        active.clear();
        inputs.forEach((key, input) -> { if (!input.getValue().get().isBlank()) active.add(key); });
        refresh();
    }

    void refreshVisibility() {
        refresh();
    }

    private void refresh() {
        if (updating) return;
        updating = true;
        try {
            var snapshot = snapshot();
            modes.forEach((key, combo) -> {
                combo.removeAllItems();
                inputs.keySet().stream().filter(candidate -> candidate.equals(key)
                        || !active.contains(candidate) && available(candidate, snapshot)).forEach(combo::addItem);
                combo.setSelectedItem(key);
            });
            add.setEnabled(inputs.keySet().stream().anyMatch(key -> !active.contains(key) && available(key, snapshot)));
            inputs.forEach((key, input) -> input.panel().setVisible(active.contains(key) && specs.get(key).visibleWhen().test(snapshot)));
            revalidate();
            repaint();
        } finally { updating = false; }
    }

    private ConfigSnapshot snapshot() {
        Map<String, String> values = new LinkedHashMap<>();
        ctx.allFields().forEach(spec -> values.put(spec.key(), ctx.currentFieldValue(spec.key())));
        return new ConfigSnapshot(values);
    }

    private boolean available(String key, ConfigSnapshot snapshot) {
        var spec = specs.get(key);
        return spec.visibleWhen().test(snapshot) && spec.enabledWhen().test(snapshot) && inputs.get(key).control().isEnabled();
    }
}
