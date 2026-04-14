package top.sywyar.pixivdownload.gui.config;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 根据 {@link FieldType} 自动渲染对应的 Swing 控件。
 * 每个字段返回一个 {@link RenderedField}，包含控件面板、取值和赋值方法。
 */
public final class FieldRenderer {

    private FieldRenderer() {}

    public record RenderedField(
            JPanel panel,
            Supplier<String> getValue,
            Consumer<String> setValue,
            JComponent control
    ) {}

    public static RenderedField render(ConfigFieldSpec spec) {
        return switch (spec.type()) {
            case BOOL -> renderBool(spec);
            case PORT -> renderSpinner(spec, 1, 65535);
            case INT -> renderSpinner(spec, 0, Integer.MAX_VALUE);
            case PATH_DIR -> renderPath(spec, true);
            case PATH_FILE -> renderPath(spec, false);
            case ENUM -> renderEnum(spec);
            case PASSWORD -> renderPassword(spec);
            case STRING -> renderString(spec);
        };
    }

    // ── 各类型渲染 ──────────────────────────────────────────────────────────────

    private static RenderedField renderBool(ConfigFieldSpec spec) {
        JCheckBox cb = new JCheckBox();
        cb.setSelected("true".equalsIgnoreCase(spec.defaultValue()));
        JPanel p = fieldPanel(spec, cb);
        return new RenderedField(p,
                () -> cb.isSelected() ? "true" : "false",
                v -> cb.setSelected("true".equalsIgnoreCase(v)),
                cb);
    }

    private static RenderedField renderSpinner(ConfigFieldSpec spec, int min, int max) {
        int def = parseIntSafe(spec.defaultValue(), min);
        JSpinner sp = new JSpinner(new SpinnerNumberModel(def, min, max, 1));
        sp.setPreferredSize(new Dimension(120, sp.getPreferredSize().height));
        JPanel p = fieldPanel(spec, sp);
        return new RenderedField(p,
                () -> String.valueOf(((Number) sp.getValue()).intValue()),
                v -> sp.setValue(parseIntSafe(v, def)),
                sp);
    }

    private static RenderedField renderPath(ConfigFieldSpec spec, boolean dirMode) {
        JTextField tf = new JTextField(spec.defaultValue(), 24);
        JButton browse = new JButton("浏览...");
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(dirMode ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
            if (!dirMode) {
                fc.setFileFilter(new FileNameExtensionFilter("证书文件 (*.pem, *.jks, *.p12)",
                        "pem", "jks", "p12", "key"));
            }
            String cur = tf.getText().trim();
            if (!cur.isBlank()) {
                File curFile = new File(cur);
                fc.setCurrentDirectory(curFile.isDirectory() ? curFile : curFile.getParentFile());
            }
            if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                tf.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.add(tf, BorderLayout.CENTER);
        row.add(browse, BorderLayout.EAST);

        JPanel p = fieldPanel(spec, row);
        return new RenderedField(p,
                () -> tf.getText().trim(),
                v -> tf.setText(v == null ? "" : v),
                tf);
    }

    private static RenderedField renderEnum(ConfigFieldSpec spec) {
        JComboBox<String> cb = new JComboBox<>(spec.enumValues().toArray(new String[0]));
        cb.setSelectedItem(spec.defaultValue());
        JPanel p = fieldPanel(spec, cb);
        return new RenderedField(p,
                () -> (String) cb.getSelectedItem(),
                cb::setSelectedItem,
                cb);
    }

    private static RenderedField renderPassword(ConfigFieldSpec spec) {
        JPasswordField pf = new JPasswordField(24);
        JPanel p = fieldPanel(spec, pf);
        return new RenderedField(p,
                () -> new String(pf.getPassword()).trim(),
                v -> pf.setText(v == null ? "" : v),
                pf);
    }

    private static RenderedField renderString(ConfigFieldSpec spec) {
        JTextField tf = new JTextField(spec.defaultValue(), 24);
        JPanel p = fieldPanel(spec, tf);
        return new RenderedField(p,
                () -> tf.getText().trim(),
                v -> tf.setText(v == null ? "" : v),
                tf);
    }

    // ── 字段面板布局 ──────────────────────────────────────────────────────────────

    /**
     * 布局：[标签] [控件 + 帮助文字 + 需重启标记]
     */
    private static JPanel fieldPanel(ConfigFieldSpec spec, JComponent control) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        // 标签
        JLabel label = new JLabel(spec.label() + "：");
        label.setPreferredSize(new Dimension(160, 24));
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        panel.add(label, gbc);

        // 控件
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(control, gbc);

        // 需重启标记
        if (spec.requiresRestart()) {
            JLabel restart = new JLabel("需重启");
            restart.setFont(restart.getFont().deriveFont(Font.PLAIN, 11f));
            restart.setForeground(new Color(180, 100, 0));
            gbc.gridx = 2;
            gbc.weightx = 0;
            panel.add(restart, gbc);
        }

        // 帮助文字
        if (!spec.helpText().isBlank()) {
            JLabel help = new JLabel(spec.helpText());
            help.setFont(help.getFont().deriveFont(Font.PLAIN, 11f));
            help.setForeground(Color.GRAY);
            gbc.gridx = 1;
            gbc.gridy = 1;
            gbc.weightx = 1;
            gbc.gridwidth = 2;
            gbc.insets = new Insets(0, 4, 6, 4);
            panel.add(help, gbc);
        }

        return panel;
    }

    private static int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
