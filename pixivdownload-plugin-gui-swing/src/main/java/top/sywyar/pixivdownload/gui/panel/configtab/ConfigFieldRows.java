package top.sywyar.pixivdownload.gui.panel.configtab;

import top.sywyar.pixivdownload.gui.config.ConfigFieldSpec;
import top.sywyar.pixivdownload.gui.config.FieldRenderer;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;

/**
 * Wraps a normal configuration field panel together with its trailing layout gap.
 */
public final class ConfigFieldRows {

    static final String FIELD_KEY_PROPERTY = "pixivdownload.guiConfig.fieldRowKey";
    static final String TRAILING_SPACING_PROPERTY = "pixivdownload.guiConfig.fieldRowTrailingSpacing";
    private static final int FIELD_SPACING = 2;

    private ConfigFieldRows() {
    }

    public static FieldRenderer.RenderedField render(ConfigFieldSpec spec) {
        return wrap(spec.key(), FieldRenderer.render(spec));
    }

    public static FieldRenderer.RenderedField wrap(String key, FieldRenderer.RenderedField field) {
        FieldRowPanel row = new FieldRowPanel();
        row.putClientProperty(FIELD_KEY_PROPERTY, key);
        field.panel().setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(field.panel());
        JComponent spacing = (JComponent) Box.createVerticalStrut(FIELD_SPACING);
        spacing.putClientProperty(TRAILING_SPACING_PROPERTY, Boolean.TRUE);
        row.add(spacing);
        return new FieldRenderer.RenderedField(
                row,
                field.getValue(),
                field.setValue(),
                field.control(),
                field.validationError());
    }

    private static final class FieldRowPanel extends JPanel {
        private FieldRowPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        @Override
        public Dimension getMaximumSize() {
            Dimension preferred = getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, preferred.height);
        }
    }
}
