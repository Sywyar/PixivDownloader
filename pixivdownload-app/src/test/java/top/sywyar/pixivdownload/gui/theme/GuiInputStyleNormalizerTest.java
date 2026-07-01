package top.sywyar.pixivdownload.gui.theme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JFormattedTextField;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.text.InternationalFormatter;
import java.text.NumberFormat;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GUI 输入样式归一化")
class GuiInputStyleNormalizerTest {

    @Test
    @DisplayName("递归强制文本输入左对齐且数字格式化不使用分组")
    void normalizesTextInputsRecursively() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel root = new JPanel();
            JTextField textField = new JTextField("plain");
            textField.setHorizontalAlignment(JTextField.RIGHT);
            JPasswordField passwordField = new JPasswordField("secret");
            passwordField.setHorizontalAlignment(JTextField.TRAILING);
            JFormattedTextField formattedTextField = groupedNumberField();
            formattedTextField.setHorizontalAlignment(JTextField.RIGHT);
            root.add(textField);
            root.add(passwordField);
            root.add(formattedTextField);

            assertThat(formattedTextField.getText()).isEqualTo("1,000");

            GuiInputStyleNormalizer.apply(root);

            assertThat(textField.getHorizontalAlignment()).isEqualTo(JTextField.LEFT);
            assertThat(passwordField.getHorizontalAlignment()).isEqualTo(JTextField.LEFT);
            assertThat(formattedTextField.getHorizontalAlignment()).isEqualTo(JTextField.LEFT);
            assertThat(formattedTextField.getText()).isEqualTo("1000");
            assertNumberGroupingDisabled(formattedTextField);
        });
    }

    @Test
    @DisplayName("Spinner 编辑器左对齐且 1000 不显示为 1,000")
    void normalizesSpinnerEditor() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JSpinner spinner = new JSpinner(new SpinnerNumberModel(1000, 0, 10_000, 1));
            spinner.setEditor(new JSpinner.NumberEditor(spinner, "#,##0"));
            JFormattedTextField textField = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
            textField.setHorizontalAlignment(JTextField.RIGHT);
            textField.setValue(1000);

            assertThat(textField.getText()).isEqualTo("1,000");

            GuiInputStyleNormalizer.apply(spinner);
            GuiInputStyleNormalizer.apply(spinner);

            assertThat(textField.getHorizontalAlignment()).isEqualTo(JTextField.LEFT);
            assertThat(textField.getText()).isEqualTo("1000");
            assertNumberGroupingDisabled(textField);
        });
    }

    private static JFormattedTextField groupedNumberField() {
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.US);
        format.setGroupingUsed(true);
        JFormattedTextField field = new JFormattedTextField(format);
        field.setValue(1000);
        return field;
    }

    private static void assertNumberGroupingDisabled(JFormattedTextField textField) {
        assertThat(textField.getFormatter()).isInstanceOf(InternationalFormatter.class);
        InternationalFormatter formatter = (InternationalFormatter) textField.getFormatter();
        assertThat(formatter.getFormat()).isInstanceOf(NumberFormat.class);
        NumberFormat format = (NumberFormat) formatter.getFormat();
        assertThat(format.isGroupingUsed()).isFalse();
    }
}
