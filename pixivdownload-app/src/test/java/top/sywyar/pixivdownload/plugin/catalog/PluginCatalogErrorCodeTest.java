package top.sywyar.pixivdownload.plugin.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PluginCatalogErrorCode} 单测：每个 catalog 失败码都映射到一个 HTTP 状态与一条<b>两种语言都存在</b>的 i18n 文案
 * key。守护「新增 catalog 失败码必须同步补 HTTP 映射与中英文文案」（镜像 {@code PluginInstallOutcomeMappingTest}）。
 */
@DisplayName("PluginCatalogErrorCode catalog 失败码 → HTTP 状态 + i18n 文案 key")
class PluginCatalogErrorCodeTest {

    @ParameterizedTest(name = "{0} → 有 HTTP 状态 + 中英文文案")
    @EnumSource(PluginCatalogErrorCode.class)
    @DisplayName("每个失败码都有 HTTP 状态，且其 messageKey 在中英文 messages 中都有非空文案")
    void everyCodeHasStatusAndLocalizedMessage(PluginCatalogErrorCode code) {
        assertThat(code.status()).as("HTTP 状态不应为空").isNotNull();

        String key = code.messageKey();
        assertThat(key).startsWith("plugin.catalog.error.");
        assertThat(messages("/i18n/messages.properties").getProperty(key))
                .as("中文文案缺失：%s", key).isNotNull().isNotBlank();
        assertThat(messages("/i18n/messages_en.properties").getProperty(key))
                .as("英文文案缺失：%s", key).isNotNull().isNotBlank();
    }

    private static Properties messages(String classpathResource) {
        try (InputStream in = PluginCatalogErrorCodeTest.class.getResourceAsStream(classpathResource);
             Reader reader = new InputStreamReader(requireResource(in, classpathResource), StandardCharsets.UTF_8)) {
            Properties properties = new Properties();
            properties.load(reader);
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream requireResource(InputStream in, String resource) {
        if (in == null) {
            throw new IllegalStateException("missing classpath resource: " + resource);
        }
        return in;
    }
}
