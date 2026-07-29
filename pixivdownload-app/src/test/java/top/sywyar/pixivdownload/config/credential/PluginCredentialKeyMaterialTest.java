package top.sywyar.pixivdownload.config.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件凭证根密钥配置")
class PluginCredentialKeyMaterialTest {

    @Test
    @DisplayName("profile 不接受前后空白以免绕过开源与生产密钥关系校验")
    void rejectsWhitespaceAroundProfile() {
        Properties production = properties(
                "production ", repeated((byte) 0x41), repeated((byte) 0x41));
        Properties openSource = properties(
                " open-source", repeated((byte) 0x42), repeated((byte) 0x43));

        assertThatThrownBy(() -> PluginCredentialKeyMaterial.fromProperties(production))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported");
        assertThatThrownBy(() -> PluginCredentialKeyMaterial.fromProperties(openSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported");
    }

    @Test
    @DisplayName("开源 profile 必须等于公开回退密钥，生产 profile 必须使用不同密钥")
    void validatesProfileKeyRelationship() {
        byte[] first = repeated((byte) 0x44);
        byte[] second = repeated((byte) 0x45);

        assertThatThrownBy(() -> PluginCredentialKeyMaterial.fromProperties(
                properties("open-source", first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Open-source");
        assertThatThrownBy(() -> PluginCredentialKeyMaterial.fromProperties(
                properties("production", first, first)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production");
    }

    @Test
    @DisplayName("根密钥必须是 canonical Base64 编码的 32 字节")
    void rejectsNonCanonicalOrWrongLengthKeys() {
        Properties wrongLength = properties(
                "open-source", new byte[31], new byte[31]);
        Properties nonCanonical = properties(
                "open-source", repeated((byte) 0x46), repeated((byte) 0x46));
        nonCanonical.setProperty(
                "current-key-base64",
                nonCanonical.getProperty("current-key-base64") + "\n");

        assertThatThrownBy(() -> PluginCredentialKeyMaterial.fromProperties(wrongLength))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 32 bytes");
        assertThatThrownBy(() -> PluginCredentialKeyMaterial.fromProperties(nonCanonical))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid plugin credential key property");
    }

    private static Properties properties(String profile, byte[] current, byte[] fallback) {
        Properties properties = new Properties();
        properties.setProperty("profile", profile);
        properties.setProperty("current-key-base64", Base64.getEncoder().encodeToString(current));
        properties.setProperty(
                "open-source-fallback-key-base64",
                Base64.getEncoder().encodeToString(fallback));
        return properties;
    }

    private static byte[] repeated(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return key;
    }
}
