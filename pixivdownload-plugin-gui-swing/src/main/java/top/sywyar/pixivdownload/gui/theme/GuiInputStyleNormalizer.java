package top.sywyar.pixivdownload.gui.theme;

import javax.swing.JFormattedTextField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.text.InternationalFormatter;
import java.awt.Component;
import java.awt.Container;
import java.text.Format;
import java.text.NumberFormat;

/**
 * Keeps Swing input widgets stable after LookAndFeel updates.
 */
public final class GuiInputStyleNormalizer {

    private GuiInputStyleNormalizer() {
    }

    public static void apply(Component root) {
        if (root == null) {
            return;
        }
        normalize(root);
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                apply(child);
            }
        }
    }

    private static void normalize(Component component) {
        if (component instanceof JSpinner spinner) {
            normalizeSpinner(spinner);
        }
        if (component instanceof JFormattedTextField formattedTextField) {
            normalizeFormattedTextField(formattedTextField);
        } else if (component instanceof JTextField textField) {
            textField.setHorizontalAlignment(JTextField.LEFT);
        }
    }

    private static void normalizeSpinner(JSpinner spinner) {
        if (!(spinner.getEditor() instanceof JSpinner.DefaultEditor editor)) {
            return;
        }
        JFormattedTextField textField = editor.getTextField();
        textField.setHorizontalAlignment(JTextField.LEFT);
        boolean numberFormat = disableNumberGrouping(textField.getFormatter());
        if (numberFormat && !textField.isFocusOwner()) {
            textField.setValue(spinner.getValue());
        }
    }

    private static void normalizeFormattedTextField(JFormattedTextField textField) {
        textField.setHorizontalAlignment(JTextField.LEFT);
        boolean numberFormat = disableNumberGrouping(textField.getFormatter());
        if (numberFormat && !textField.isFocusOwner() && textField.getValue() != null) {
            textField.setValue(textField.getValue());
        }
    }

    private static boolean disableNumberGrouping(JFormattedTextField.AbstractFormatter formatter) {
        if (!(formatter instanceof InternationalFormatter internationalFormatter)) {
            return false;
        }
        Format format = internationalFormatter.getFormat();
        if (!(format instanceof NumberFormat numberFormat)) {
            return false;
        }
        numberFormat.setGroupingUsed(false);
        return true;
    }
}
