package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.gui.i18n.GuiMessages;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.InternationalFormatter;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.io.File;
import java.text.NumberFormat;
import java.text.Format;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 根据 {@link FieldType} 自动渲染对应的 Swing 控件。
 * 每个字段返回一个 {@link RenderedField}，包含控件面板、取值和赋值方法。
 */
public final class FieldRenderer {

    private static final double LABEL_WIDTH_RATIO = 0.25;
    private static final int MIN_LABEL_WIDTH = 96;
    private static final int MIN_DESCRIPTION_WIDTH = 120;
    private static final int LABEL_HEIGHT = 24;
    private static final int DESCRIPTION_HEIGHT_PADDING = 2;
    private static final int DESCRIPTION_WIDTH_PADDING = 24;
    private static final Color VALIDATION_ERROR_COLOR = new Color(180, 40, 40);

    private FieldRenderer() {}

    public record RenderedField(
            JPanel panel,
            Supplier<String> getValue,
            Consumer<String> setValue,
            JComponent control,
            JTextArea validationError
    ) {
        public void setValidationError(String message) {
            boolean hasError = message != null && !message.isBlank();
            validationError.setText(hasError ? message : "");
            validationError.setVisible(hasError);
            panel.revalidate();
            panel.repaint();
        }
    }

    private record RenderedPanel(JPanel panel, JTextArea validationError) {}

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
        RenderedPanel p = renderFieldPanel(spec, cb);
        return new RenderedField(p.panel(),
                () -> Boolean.toString(cb.isSelected()),
                v -> cb.setSelected("true".equalsIgnoreCase(v)),
                cb,
                p.validationError());
    }

    private static RenderedField renderSpinner(ConfigFieldSpec spec, int min, int max) {
        int def = parseIntSafe(spec.defaultValue(), min);
        JSpinner sp = new JSpinner(new SpinnerNumberModel(def, min, max, 1));
        sp.setPreferredSize(new Dimension(120, sp.getPreferredSize().height));
        if (sp.getEditor() instanceof JSpinner.DefaultEditor de) {
            de.getTextField().setHorizontalAlignment(JTextField.LEFT);
            JFormattedTextField.AbstractFormatter formatter = de.getTextField().getFormatter();
            if (formatter instanceof InternationalFormatter intlFmt) {
                Format fmt = intlFmt.getFormat();
                if (fmt instanceof NumberFormat nf) {
                    nf.setGroupingUsed(false);
                    de.getTextField().setValue(sp.getValue());
                }
            }
        }
        RenderedPanel p = renderFieldPanel(spec, sp);
        return new RenderedField(p.panel(),
                () -> String.valueOf(((Number) sp.getValue()).intValue()),
                v -> sp.setValue(parseIntSafe(v, def)),
                sp,
                p.validationError());
    }

    private static RenderedField renderPath(ConfigFieldSpec spec, boolean dirMode) {
        JTextField tf = new JTextField(spec.defaultValue(), 24);
        JButton browse = new JButton(message("gui.button.browse"));
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(dirMode ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
            if (!dirMode) {
                fc.setFileFilter(new FileNameExtensionFilter(message("gui.filechooser.cert-files"),
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

        RenderedPanel p = renderFieldPanel(spec, row);
        return new RenderedField(p.panel(),
                () -> tf.getText().trim(),
                v -> tf.setText(v == null ? "" : v),
                tf,
                p.validationError());
    }

    private static RenderedField renderEnum(ConfigFieldSpec spec) {
        JComboBox<String> cb = new JComboBox<>(spec.enumValues().toArray(new String[0]));
        cb.setSelectedItem(spec.defaultValue());
        RenderedPanel p = renderFieldPanel(spec, cb);
        return new RenderedField(p.panel(),
                () -> (String) cb.getSelectedItem(),
                cb::setSelectedItem,
                cb,
                p.validationError());
    }

    private static RenderedField renderPassword(ConfigFieldSpec spec) {
        JPasswordField pf = new JPasswordField(24);
        RenderedPanel p = renderFieldPanel(spec, pf);
        return new RenderedField(p.panel(),
                () -> new String(pf.getPassword()).trim(),
                v -> pf.setText(v == null ? "" : v),
                pf,
                p.validationError());
    }

    private static RenderedField renderString(ConfigFieldSpec spec) {
        JTextField tf = new JTextField(spec.defaultValue(), 24);
        RenderedPanel p = renderFieldPanel(spec, tf);
        return new RenderedField(p.panel(),
                () -> tf.getText().trim(),
                v -> tf.setText(v == null ? "" : v),
                tf,
                p.validationError());
    }

    // ── 字段面板布局 ──────────────────────────────────────────────────────────────

    /**
     * 布局：[标签] [控件 + 帮助文字 + 需重启标记]
     */
    private static RenderedPanel renderFieldPanel(ConfigFieldSpec spec, JComponent control) {
        JLabel effect = new JLabel(message(spec.requiresRestart()
                ? "gui.label.restart-required"
                : "gui.label.hot-reload"));
        effect.setFont(effect.getFont().deriveFont(Font.PLAIN, 11f));
        effect.setForeground(spec.requiresRestart()
                ? new Color(180, 100, 0)
                : new Color(0, 128, 96));
        JTextArea validationError = createTextArea("");
        validationError.setForeground(VALIDATION_ERROR_COLOR);
        validationError.setVisible(false);

        return new RenderedPanel(buildFieldPanel(
                spec.label() + message("gui.punctuation.colon"),
                control,
                effect,
                spec.helpText(),
                validationError), validationError);
    }

    public static JPanel fieldPanel(String labelText, JComponent control, JComponent effect, String helpText) {
        return buildFieldPanel(labelText, control, effect, helpText, null);
    }

    private static JPanel buildFieldPanel(String labelText, JComponent control, JComponent effect,
                                          String helpText, JTextArea validationError) {
        JLabel label = new JLabel(labelText);
        label.setToolTipText(labelText);

        JTextArea help = null;
        if (helpText != null && !helpText.isBlank()) {
            help = createTextArea(helpText);
            help.setForeground(Color.GRAY);
        }

        ResponsiveFieldPanel panel = new ResponsiveFieldPanel(label, help, validationError);
        panel.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        // 标签
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        panel.add(label, gbc);

        // 控件
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(control, gbc);

        // 生效方式标记
        if (effect != null) {
            gbc.gridx = 2;
            gbc.weightx = 0;
            panel.add(effect, gbc);
        }

        // 帮助文字
        if (help != null) {
            gbc.gridx = 1;
            gbc.gridy = 1;
            gbc.weightx = 1;
            gbc.gridwidth = effect == null ? 1 : 2;
            gbc.insets = new Insets(0, 4, validationError == null ? 6 : 2, 4);
            panel.add(help, gbc);
        }

        // 校验错误
        if (validationError != null) {
            gbc.gridx = 1;
            gbc.gridy = help == null ? 1 : 2;
            gbc.weightx = 1;
            gbc.gridwidth = effect == null ? 1 : 2;
            gbc.insets = new Insets(0, 4, 6, 4);
            panel.add(validationError, gbc);
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

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }

    private static JTextArea createTextArea(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(BorderFactory.createEmptyBorder());
        Font labelFont = UIManager.getFont("Label.font");
        area.setFont((labelFont == null ? area.getFont() : labelFont).deriveFont(Font.PLAIN, 11f));
        return area;
    }

    private static final class ResponsiveFieldPanel extends JPanel {
        private final JLabel label;
        private final JTextArea description;
        private final JTextArea validationError;
        private final ComponentAdapter windowResizeListener = new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                refreshAndRelayout();
            }
        };
        private Window observedWindow;

        ResponsiveFieldPanel(JLabel label, JTextArea description, JTextArea validationError) {
            super(new GridBagLayout());
            this.label = label;
            this.description = description;
            this.validationError = validationError;
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    refreshAndRelayout();
                }
            });
            addHierarchyListener(e -> {
                long flags = e.getChangeFlags();
                if ((flags & (HierarchyEvent.PARENT_CHANGED
                        | HierarchyEvent.SHOWING_CHANGED
                        | HierarchyEvent.DISPLAYABILITY_CHANGED)) != 0) {
                    updateWindowResizeListener();
                    refreshAndRelayout();
                }
            });
        }

        @Override
        public void doLayout() {
            refreshPreferredSizes();
            super.doLayout();
        }

        @Override
        public Dimension getPreferredSize() {
            refreshPreferredSizes();
            return super.getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            Dimension preferred = getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, preferred.height);
        }

        private void updateWindowResizeListener() {
            Window window = SwingUtilities.getWindowAncestor(this);
            if (observedWindow == window) {
                return;
            }
            if (observedWindow != null) {
                observedWindow.removeComponentListener(windowResizeListener);
            }
            observedWindow = window;
            if (observedWindow != null) {
                observedWindow.addComponentListener(windowResizeListener);
            }
        }

        private void refreshAndRelayout() {
            if (refreshPreferredSizes()) {
                revalidate();
                repaint();
            }
        }

        private boolean refreshPreferredSizes() {
            int width = availableWidth();
            if (width <= 0) {
                return false;
            }

            int labelWidth = Math.max(MIN_LABEL_WIDTH, (int) Math.round(width * LABEL_WIDTH_RATIO));
            boolean changed = setFixedSize(label, labelWidth, LABEL_HEIGHT);

            if (description != null) {
                changed |= refreshTextAreaSize(description, width, labelWidth);
            }
            if (validationError != null && validationError.isVisible()) {
                changed |= refreshTextAreaSize(validationError, width, labelWidth);
            }

            return changed;
        }

        private boolean refreshTextAreaSize(JTextArea area, int width, int labelWidth) {
            int textWidth = Math.max(MIN_DESCRIPTION_WIDTH,
                    width - labelWidth - DESCRIPTION_WIDTH_PADDING);
            Dimension current = area.getPreferredSize();
            area.setPreferredSize(null);
            area.setMinimumSize(null);
            area.setMaximumSize(null);
            area.setSize(new Dimension(textWidth, Integer.MAX_VALUE));
            Dimension preferred = area.getPreferredSize();
            int textHeight = preferred.height + DESCRIPTION_HEIGHT_PADDING;
            Dimension next = new Dimension(textWidth, textHeight);
            boolean changed = current == null || !current.equals(next);
            setFixedSize(area, textWidth, textHeight);
            return changed;
        }

        private int availableWidth() {
            if (getWidth() > 0) {
                return getWidth();
            }
            Container parent = getParent();
            if (parent != null && parent.getWidth() > 0) {
                Insets insets = parent.getInsets();
                return Math.max(0, parent.getWidth() - insets.left - insets.right);
            }
            Container viewport = SwingUtilities.getAncestorOfClass(JViewport.class, this);
            if (viewport != null && viewport.getWidth() > 0) {
                return viewport.getWidth();
            }
            Window window = SwingUtilities.getWindowAncestor(this);
            if (window != null && window.getWidth() > 0) {
                return window.getWidth();
            }
            return 0;
        }

        private static boolean setFixedSize(JComponent component, int width, int height) {
            Dimension next = new Dimension(width, height);
            Dimension preferred = component.getPreferredSize();
            if (preferred != null && preferred.equals(next)) {
                return false;
            }
            component.setPreferredSize(next);
            component.setMinimumSize(next);
            component.setMaximumSize(next);
            return true;
        }
    }
}
