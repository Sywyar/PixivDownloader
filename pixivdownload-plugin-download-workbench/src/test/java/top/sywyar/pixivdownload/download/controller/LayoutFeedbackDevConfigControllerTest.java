package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 布局偏好调查开发者模式配置注入：posthog.properties 有效值解析、校验与渲染。
 * 端点本身不依赖 Spring context，直接验证静态逻辑。
 */
@DisplayName("布局偏好调查开发者模式配置注入")
class LayoutFeedbackDevConfigControllerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("开发者模式且四项值全部有效时返回注入配置")
    void devModeInjectsValidConfig() throws IOException {
        writeProperties("""
                pixiv.layout-survey.project-token = phc_abc123
                pixiv.layout-survey.survey-id = aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
                pixiv.layout-survey.api-host = https://eu.posthog.com/
                pixiv.layout-survey.ui-host = https://eu.posthog.com
                """);
        LayoutFeedbackDevConfigController.DevConfig config = withDevMode(() ->
                LayoutFeedbackDevConfigController.resolveDevConfig(tempDir));
        assertThat(config).isNotNull();
        assertThat(config.projectToken()).isEqualTo("phc_abc123");
        assertThat(config.surveyId()).isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertThat(config.apiHost()).isEqualTo("https://eu.posthog.com");
        assertThat(config.uiHost()).isEqualTo("https://eu.posthog.com");
    }

    @Test
    @DisplayName("非开发者模式、文件缺失或配置无效时一律回退（返回 null）")
    void devModeRequiresValidProperties() throws IOException {
        writeProperties("""
                pixiv.layout-survey.project-token = phc_abc123
                pixiv.layout-survey.survey-id = aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
                pixiv.layout-survey.api-host = https://eu.posthog.com
                pixiv.layout-survey.ui-host = https://eu.posthog.com
                """);
        assertThat(withDevMode(() -> LayoutFeedbackDevConfigController.resolveDevConfig(tempDir)))
                .as("开发者模式开启且有效").isNotNull();
        assertThat(withDevModeOff(() -> LayoutFeedbackDevConfigController.resolveDevConfig(tempDir)))
                .as("非开发者模式").isNull();
        assertThat(withDevMode(() -> LayoutFeedbackDevConfigController.resolveDevConfig(
                tempDir.resolve("missing"))))
                .as("properties 缺失").isNull();
    }

    @Test
    @DisplayName("半配置、空模板、占位值、未知键、重复键和非法形状都按无效回退")
    void invalidPropertiesAreRejected() throws IOException {
        assertNullWithDevMode("""
                pixiv.layout-survey.project-token = phc_abc123
                pixiv.layout-survey.survey-id = aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
                pixiv.layout-survey.api-host = https://eu.posthog.com
                """);
        assertNullWithDevMode("""
                pixiv.layout-survey.project-token =
                pixiv.layout-survey.survey-id =
                pixiv.layout-survey.api-host =
                pixiv.layout-survey.ui-host =
                """);
        assertNullWithDevMode("""
                pixiv.layout-survey.project-token = project-token
                pixiv.layout-survey.survey-id = survey-id
                pixiv.layout-survey.api-host = api-host
                pixiv.layout-survey.ui-host = ui-host
                """);
        assertNullWithDevMode("""
                pixiv.layout-survey.project-token = phc_abc123
                pixiv.layout-survey.survey-id = aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
                pixiv.layout-survey.api-host = https://eu.posthog.com
                pixiv.layout-survey.ui-host = https://eu.posthog.com
                unknown.key = anything
                """);
        assertNullWithDevMode("""
                pixiv.layout-survey.project-token = phc_abc123
                pixiv.layout-survey.project-token = phc_other
                pixiv.layout-survey.survey-id = aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
                pixiv.layout-survey.api-host = https://eu.posthog.com
                pixiv.layout-survey.ui-host = https://eu.posthog.com
                """);
        assertNullWithDevMode("""
                pixiv.layout-survey.project-token = phc_abc123
                pixiv.layout-survey.survey-id = not a valid id!
                pixiv.layout-survey.api-host = https://eu.posthog.com
                pixiv.layout-survey.ui-host = https://eu.posthog.com
                """);
        assertNullWithDevMode("""
                pixiv.layout-survey.project-token = phc_abc123
                pixiv.layout-survey.survey-id = aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
                pixiv.layout-survey.api-host = http://not-localhost.example.com
                pixiv.layout-survey.ui-host = https://eu.posthog.com
                """);
        assertNullWithDevMode("""
                pixiv.layout-survey.project-token = phc_abc123
                pixiv.layout-survey.survey-id = aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
                pixiv.layout-survey.api-host = https://user:pass@eu.posthog.com
                pixiv.layout-survey.ui-host = https://eu.posthog.com
                """);
    }

    @Test
    @DisplayName("回环 http 允许用于本地开发，https 为正式值")
    void loopbackHttpAndHttpsAreAccepted() throws IOException {
        writeProperties("""
                pixiv.layout-survey.project-token = phc_abc123
                pixiv.layout-survey.survey-id = aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
                pixiv.layout-survey.api-host = http://localhost:3000
                pixiv.layout-survey.ui-host = http://127.0.0.1:3000/
                """);
        LayoutFeedbackDevConfigController.DevConfig config = withDevMode(() ->
                LayoutFeedbackDevConfigController.resolveDevConfig(tempDir));
        assertThat(config).isNotNull();
        assertThat(config.apiHost()).isEqualTo("http://localhost:3000");
        assertThat(config.uiHost()).isEqualTo("http://127.0.0.1:3000");
    }

    @Test
    @DisplayName("渲染结果与前端契约一致：Object.freeze 包裹、enabled=true、四项值可逆")
    void renderMatchesFrontendContract() throws IOException {
        LayoutFeedbackDevConfigController.DevConfig config =
                new LayoutFeedbackDevConfigController.DevConfig(
                        "phc_token", "survey-123", "https://eu.posthog.com", "https://eu.posthog.com");
        byte[] body = LayoutFeedbackDevConfigController.render(config);
        String js = new String(body, StandardCharsets.UTF_8);
        assertThat(js).startsWith("window.PixivLayoutFeedbackPublicConfig = Object.freeze(")
                .endsWith(");\n")
                .contains("\"enabled\":true")
                .contains("\"projectToken\":\"phc_token\"");
        String json = js.substring(js.indexOf('(') + 1, js.lastIndexOf(");"));
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = new ObjectMapper().readValue(json, Map.class);
        assertThat(parsed).containsEntry("enabled", true)
                .containsEntry("projectToken", "phc_token")
                .containsEntry("surveyId", "survey-123")
                .containsEntry("apiHost", "https://eu.posthog.com")
                .containsEntry("uiHost", "https://eu.posthog.com");
    }

    @Test
    @DisplayName("开发根目录优先取 plugin-dev.root，缺失时回退进程工作目录")
    void devRootPrefersConfiguredRoot() {
        String previous = System.getProperty(LayoutFeedbackDevConfigController.DEV_ROOT_PROPERTY);
        try {
            System.setProperty(LayoutFeedbackDevConfigController.DEV_ROOT_PROPERTY, tempDir.toString());
            assertThat(LayoutFeedbackDevConfigController.devRoot())
                    .isEqualTo(tempDir.toAbsolutePath());
            System.clearProperty(LayoutFeedbackDevConfigController.DEV_ROOT_PROPERTY);
            assertThat(LayoutFeedbackDevConfigController.devRoot())
                    .isEqualTo(Path.of("").toAbsolutePath());
        } finally {
            restore(previous, LayoutFeedbackDevConfigController.DEV_ROOT_PROPERTY);
        }
    }

    private void assertNullWithDevMode(String content) throws IOException {
        writeProperties(content);
        assertThat(withDevMode(() -> LayoutFeedbackDevConfigController.resolveDevConfig(tempDir)))
                .isNull();
    }

    private void writeProperties(String content) throws IOException {
        Path file = tempDir.resolve("scripts/properties/posthog.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static <T> T withDevMode(java.util.function.Supplier<T> supplier) {
        String previousEnabled = System.getProperty(LayoutFeedbackDevConfigController.DEV_MODE_PROPERTY);
        String previousRoot = System.getProperty(LayoutFeedbackDevConfigController.DEV_ROOT_PROPERTY);
        try {
            System.setProperty(LayoutFeedbackDevConfigController.DEV_MODE_PROPERTY, "true");
            return supplier.get();
        } finally {
            restore(previousEnabled, LayoutFeedbackDevConfigController.DEV_MODE_PROPERTY);
            restore(previousRoot, LayoutFeedbackDevConfigController.DEV_ROOT_PROPERTY);
        }
    }

    private static <T> T withDevModeOff(java.util.function.Supplier<T> supplier) {
        String previousEnabled = System.getProperty(LayoutFeedbackDevConfigController.DEV_MODE_PROPERTY);
        try {
            System.clearProperty(LayoutFeedbackDevConfigController.DEV_MODE_PROPERTY);
            return supplier.get();
        } finally {
            restore(previousEnabled, LayoutFeedbackDevConfigController.DEV_MODE_PROPERTY);
        }
    }

    private static void restore(String previous, String property) {
        if (previous == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, previous);
        }
    }
}
