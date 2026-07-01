package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import top.sywyar.pixivdownload.plugin.install.PluginInstallOutcomeMapping;

/**
 * {@link PluginInstallOutcomeMapping} 单测：每个安装结果分类都映射到一个 HTTP 状态与一条<b>两种语言都存在</b>的 i18n
 * 文案 key；accepted / 包内容非法 / 冲突 / 服务端失败的状态分档正确。守护「新增 outcome 必须同步补 HTTP 映射与
 * 中英文文案」。
 */
@DisplayName("PluginInstallOutcomeMapping 安装结果 → HTTP 状态 + i18n 文案 key")
class PluginInstallOutcomeMappingTest {

    @ParameterizedTest(name = "{0} → 有 HTTP 状态 + 中英文文案")
    @EnumSource(PluginInstallOutcome.class)
    @DisplayName("每个 outcome 都有 HTTP 状态，且其 messageKey 在中英文 messages 中都有非空文案")
    void everyOutcomeHasStatusAndLocalizedMessage(PluginInstallOutcome outcome) {
        assertThat(PluginInstallOutcomeMapping.httpStatus(outcome)).as("HTTP 状态不应为空").isNotNull();

        String key = PluginInstallOutcomeMapping.messageKey(outcome);
        assertThat(key).startsWith("plugin.install.outcome.");
        assertThat(messages("/i18n/messages.properties").getProperty(key))
                .as("中文文案缺失：%s", key).isNotNull().isNotBlank();
        assertThat(messages("/i18n/messages_en.properties").getProperty(key))
                .as("英文文案缺失：%s", key).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("HTTP 状态分档：accepted→200、包内容非法→400、与核心/版本冲突→409、服务端失败→500")
    void httpStatusBuckets() {
        assertThat(PluginInstallOutcomeMapping.httpStatus(PluginInstallOutcome.INSTALLED)).isEqualTo(HttpStatus.OK);
        assertThat(PluginInstallOutcomeMapping.httpStatus(PluginInstallOutcome.UPGRADED)).isEqualTo(HttpStatus.OK);
        assertThat(PluginInstallOutcomeMapping.httpStatus(PluginInstallOutcome.DOWNGRADED)).isEqualTo(HttpStatus.OK);
        assertThat(PluginInstallOutcomeMapping.httpStatus(PluginInstallOutcome.DUPLICATE)).isEqualTo(HttpStatus.OK);

        assertThat(PluginInstallOutcomeMapping.httpStatus(PluginInstallOutcome.REJECTED_EMPTY))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(PluginInstallOutcomeMapping.httpStatus(PluginInstallOutcome.REJECTED_MALFORMED))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(PluginInstallOutcomeMapping.httpStatus(PluginInstallOutcome.REJECTED_UNSAFE))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(PluginInstallOutcomeMapping.httpStatus(PluginInstallOutcome.REJECTED_INVALID))
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(PluginInstallOutcomeMapping.httpStatus(PluginInstallOutcome.REJECTED_TOO_LARGE))
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(PluginInstallOutcomeMapping.httpStatus(PluginInstallOutcome.REJECTED_INTEGRITY))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(PluginInstallOutcomeMapping.httpStatus(PluginInstallOutcome.REJECTED_INCOMPATIBLE))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(PluginInstallOutcomeMapping.httpStatus(PluginInstallOutcome.DOWNGRADE_REJECTED))
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(PluginInstallOutcomeMapping.httpStatus(PluginInstallOutcome.FAILED))
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("messageKey 由枚举名规范派生（下划线 → 短横线、小写）")
    void messageKeyDerivation() {
        assertThat(PluginInstallOutcomeMapping.messageKey(PluginInstallOutcome.INSTALLED))
                .isEqualTo("plugin.install.outcome.installed");
        assertThat(PluginInstallOutcomeMapping.messageKey(PluginInstallOutcome.REJECTED_NO_DESCRIPTOR))
                .isEqualTo("plugin.install.outcome.rejected-no-descriptor");
        assertThat(PluginInstallOutcomeMapping.messageKey(PluginInstallOutcome.DOWNGRADE_REJECTED))
                .isEqualTo("plugin.install.outcome.downgrade-rejected");
    }

    private static Properties messages(String classpathResource) {
        try (InputStream in = PluginInstallOutcomeMappingTest.class.getResourceAsStream(classpathResource);
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
