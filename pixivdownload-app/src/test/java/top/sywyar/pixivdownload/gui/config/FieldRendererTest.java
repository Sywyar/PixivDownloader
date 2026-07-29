package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;

import javax.swing.JPasswordField;
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

    @Test
    @DisplayName("PASSWORD 字段以非明文状态提示区分已保存、替换与清除")
    void passwordFieldShowsCredentialStateWithoutEchoingStoredValue() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FieldRenderer.RenderedField renderedField = FieldRenderer.render(
                    pluginCredentialSpec("fixture.api-key"));

            assertThat(renderedField.control()).isInstanceOf(JPasswordField.class);
            assertThat(renderedField.getValue().get()).isEmpty();
            assertThat(renderedField.credentialStored()).isFalse();
            assertThat(renderedField.credentialStatusText())
                    .isEqualTo(GuiMessages.get("gui.credential.status.not-saved"));

            renderedField.setValue().accept("first-value");

            assertThat(renderedField.credentialStatusText())
                    .isEqualTo(GuiMessages.get("gui.credential.status.save-pending"));

            renderedField.requestCredentialClear();

            assertThat(renderedField.getValue().get()).isEmpty();
            assertThat(renderedField.credentialClearRequested()).isFalse();
            assertThat(renderedField.credentialStatusText())
                    .isEqualTo(GuiMessages.get("gui.credential.status.not-saved"));

            renderedField.setCredentialStored(true);

            assertThat(renderedField.getValue().get()).isEmpty();
            assertThat(renderedField.credentialStatusText())
                    .isEqualTo(GuiMessages.get("gui.credential.status.saved"));

            renderedField.setValue().accept("replacement");

            assertThat(renderedField.credentialClearRequested()).isFalse();
            assertThat(renderedField.credentialStatusText())
                    .isEqualTo(GuiMessages.get("gui.credential.status.replace-pending"));

            renderedField.requestCredentialClear();

            assertThat(renderedField.getValue().get()).isEmpty();
            assertThat(renderedField.credentialClearRequested()).isTrue();
            assertThat(renderedField.credentialStatusText())
                    .isEqualTo(GuiMessages.get("gui.credential.status.clear-pending"));

            renderedField.setValue().accept("");

            assertThat(renderedField.credentialClearRequested()).isFalse();
            assertThat(renderedField.credentialStatusText())
                    .isEqualTo(GuiMessages.get("gui.credential.status.saved"));
        });
    }

    @Test
    @DisplayName("核心 PASSWORD 字段不显示插件凭证存储状态")
    void corePasswordFieldDoesNotShowPluginCredentialState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FieldRenderer.RenderedField renderedField = FieldRenderer.render(
                    spec("server.ssl.key-store-password", FieldType.PASSWORD, ""));

            renderedField.setValue().accept("core-password");

            assertThat(renderedField.getValue().get()).isEqualTo("core-password");
            assertThat(renderedField.credentialStatusText()).isEmpty();
        });
    }

    private static ConfigFieldSpec spec(String key, FieldType type, String defaultValue) {
        return ConfigFieldSpec.builder(key, key, type, "test")
                .defaultValue(defaultValue)
                .build();
    }

    private static ConfigFieldSpec pluginCredentialSpec(String key) {
        return ConfigFieldSpec.builder(key, key, FieldType.PASSWORD, "test")
                .ownerPluginId("fixture")
                .build();
    }
}
