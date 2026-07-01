package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("配置字段渲染器")
class FieldRendererTest {

    @Test
    @DisplayName("INT 字段渲染为左对齐文本框且保留 1000 原文")
    void intFieldUsesPlainText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FieldRenderer.RenderedField renderedField = FieldRenderer.render(
                    spec("download.max-concurrent", FieldType.INT, "10"));

            assertThat(renderedField.control()).isInstanceOf(JTextField.class);
            JTextField textField = (JTextField) renderedField.control();
            assertThat(textField.getHorizontalAlignment()).isEqualTo(JTextField.LEFT);

            renderedField.setValue().accept("1000");

            assertThat(textField.getText()).isEqualTo("1000");
            assertThat(renderedField.getValue().get()).isEqualTo("1000");
        });
    }

    @Test
    @DisplayName("PORT 字段渲染为左对齐文本框且空值回到默认值")
    void portFieldUsesPlainTextAndDefaultForBlankSetValue() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FieldRenderer.RenderedField renderedField = FieldRenderer.render(
                    spec("server.port", FieldType.PORT, "6999"));

            assertThat(renderedField.control()).isInstanceOf(JTextField.class);
            JTextField textField = (JTextField) renderedField.control();
            assertThat(textField.getHorizontalAlignment()).isEqualTo(JTextField.LEFT);

            renderedField.setValue().accept("");

            assertThat(textField.getText()).isEqualTo("6999");
            assertThat(renderedField.getValue().get()).isEqualTo("6999");
        });
    }

    private static ConfigFieldSpec spec(String key, FieldType type, String defaultValue) {
        return ConfigFieldSpec.builder(key, key, type, "test")
                .defaultValue(defaultValue)
                .build();
    }
}
