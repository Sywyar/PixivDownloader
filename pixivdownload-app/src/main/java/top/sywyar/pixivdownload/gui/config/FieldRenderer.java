package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.gui.theme.GuiInputStyleNormalizer;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.io.File;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 根据 {@link FieldType} 自动渲染对应的 Swing 控件。
 * 每个字段返回一个 {@link RenderedField}，包含控件面板、取值和赋值方法。
 */
public final class FieldRenderer {

    private static final String CREDENTIAL_CLEAR_REQUESTED = "pixivdownload.credential.clearRequested";
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

        public boolean credentialClearRequested() {
            return Boolean.TRUE.equals(control.getClientProperty(CREDENTIAL_CLEAR_REQUESTED));
        }

        public void requestCredentialClear() {
            control.putClientProperty(CREDENTIAL_CLEAR_REQUESTED, Boolean.TRUE);
            setValue.accept("");
            control.putClientProperty(CREDENTIAL_CLEAR_REQUESTED, Boolean.TRUE);
        }
    }

    private record RenderedPanel(JPanel panel, JTextArea validationError) {}

    public static RenderedField render(ConfigFieldSpec spec) {
        return switch (spec.type()) {
            case BOOL -> renderBool(spec);
            case PORT, INT -> renderNumberText(spec);
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

    private static RenderedField renderNumberText(ConfigFieldSpec spec) {
        JTextField tf = new JTextField(defaultIfBlank(spec.defaultValue(), ""), 12);
        tf.setHorizontalAlignment(JTextField.LEFT);
        tf.setPreferredSize(new Dimension(120, tf.getPreferredSize().height));
        RenderedPanel p = renderFieldPanel(spec, tf);
        return new RenderedField(p.panel(),
                tf::getText,
                v -> tf.setText(defaultIfBlank(v, spec.defaultValue())),
                tf,
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
        cb.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                if (value instanceof String id) {
                    label.setText(spec.enumValueLabels().getOrDefault(id, id));
                }
                return label;
            }
        });
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
        pf.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                pf.putClientProperty(CREDENTIAL_CLEAR_REQUESTED, Boolean.FALSE);
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                changed();
            }
        });
        JButton clear = new JButton(message("gui.button.clear-credential"));
        clear.addActionListener(event -> {
            pf.setText("");
            pf.putClientProperty(CREDENTIAL_CLEAR_REQUESTED, Boolean.TRUE);
        });
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.add(pf, BorderLayout.CENTER);
        row.add(clear, BorderLayout.EAST);
        RenderedPanel p = renderFieldPanel(spec, row);
        return new RenderedField(p.panel(),
                () -> new String(pf.getPassword()).trim(),
                v -> {
                    pf.setText(v == null ? "" : v);
                    pf.putClientProperty(CREDENTIAL_CLEAR_REQUESTED, Boolean.FALSE);
                },
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
        JTextArea validationError = createTextArea("");
        validationError.setForeground(VALIDATION_ERROR_COLOR);
        validationError.setVisible(false);

        return new RenderedPanel(buildFieldPanel(
                spec.label() + message("gui.punctuation.colon"),
                control,
                buildEffectLabel(spec.requiresRestart()),
                spec.helpText(),
                validationError), validationError);
    }

    public static JPanel fieldPanel(String labelText, JComponent control, JComponent effect, String helpText) {
        return buildFieldPanel(labelText, control, effect, helpText, null);
    }

    /**
     * 为不进入普通字段注册表、但仍需采用标准配置项外观的控件构建统一字段面板。
     */
    public static JPanel fieldPanel(String labelText, JComponent control,
                                    boolean requiresRestart, String helpText) {
        return buildFieldPanel(labelText, control, buildEffectLabel(requiresRestart), helpText, null);
    }

    private static JLabel buildEffectLabel(boolean requiresRestart) {
        JLabel effect = new JLabel(message(requiresRestart
                ? "gui.label.restart-required"
                : "gui.label.hot-reload"));
        effect.setFont(effect.getFont().deriveFont(Font.PLAIN, 11f));
        effect.setForeground(requiresRestart
                ? new Color(180, 100, 0)
                : new Color(0, 128, 96));
        return effect;
    }

    private static JPanel buildFieldPanel(String labelText, JComponent control, JComponent effect,
                                          String helpText, JTextArea validationError) {
        GuiInputStyleNormalizer.apply(control);
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

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
