package top.sywyar.pixivdownload.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PushMessageFactory 单元测试")
class PushMessageFactoryTest {

    private static final List<String> RUNTIME_LOG_KEYS = List.of(
            "push.log.value.unknown",
            "push.log.send.success",
            "push.log.send.failed",
            "push.log.notification.delivery-failed",
            "push.log.notification.render-failed");

    private static final List<String> CONTROLLED_DETAIL_KEYS = List.of(
            PushResult.DETAIL_CHANNEL_UNAVAILABLE,
            PushResult.DETAIL_CHANNEL_NOT_CONFIGURED,
            PushResult.DETAIL_SETTINGS_INCOMPLETE,
            PushResult.DETAIL_SETTINGS_TYPE_MISMATCH,
            PushResult.DETAIL_UNEXPECTED_ERROR,
            PushResult.DETAIL_SERIALIZATION_FAILED,
            PushResult.DETAIL_SIGNING_FAILED,
            PushResult.DETAIL_INVALID_CONTENT_TYPE,
            PushResult.DETAIL_INVALID_URL);

    private final PushMessageFactory factory = new PushMessageFactory(TestMessageResolver.INSTANCE);

    @Test
    @DisplayName("中文渲染：标题/正文走 push 专用 i18n，占位符被替换，级别透传，源格式为 Markdown")
    void chineseRenderSubstitutesPlaceholders() {
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("task_name", "画师计划");
        ph.put("task_id", "9");
        ph.put("consecutive_failures", "5");
        ph.put("trigger_time", "2026-05-27 12:00:00");
        ph.put("last_error_excerpt", "限制级需登录");

        PushMessage msg = factory.render("circuit-breaker", PushLevel.ERROR, Locale.SIMPLIFIED_CHINESE, ph);

        assertThat(msg.title()).contains("熔断挂起");
        assertThat(msg.content())
                .contains("画师计划")
                .contains("5")
                .contains("限制级需登录")
                .doesNotContain("{{")
                .doesNotContain("}}");
        assertThat(msg.level()).isEqualTo(PushLevel.ERROR);
        assertThat(msg.sourceFormat()).isEqualTo(PushFormat.MARKDOWN);
    }

    @Test
    @DisplayName("英文渲染：走 _en bundle 的英文文案")
    void englishRenderUsesEnglishBundle() {
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("account_id", "12345");
        ph.put("tasks_count", "3");
        ph.put("warning_time", "2026-05-27 12:00:00");
        ph.put("trigger_time", "2026-05-27 12:01:00");

        PushMessage msg = factory.render("overuse-paused", PushLevel.WARNING, Locale.US, ph);

        assertThat(msg.title().toLowerCase()).contains("overuse");
        assertThat(msg.content())
                .contains("12345")
                .contains("3")
                .doesNotContain("{{");
    }

    @Test
    @DisplayName("正文数据占位符做 Markdown 字面转义（* / _），标题占位符不转义")
    void bodyDataPlaceholdersEscapedTitleNot() {
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("task_name", "画*师_计划");
        ph.put("task_id", "7");
        ph.put("completed", "1*2");
        ph.put("trigger_time", "2026-05-27 12:00:00");
        ph.put("next_run_time", "2026-05-27 13:00:00");

        PushMessage msg = factory.render("run-summary", PushLevel.INFO, Locale.SIMPLIFIED_CHINESE, ph);

        // 正文：数据值里的 * / _ 被反斜杠转义，避免被推送渲染器吞掉。
        assertThat(msg.content()).contains("画\\*师\\_计划").contains("1\\*2");
        // 标题：不转义，星号原样保留（标题在各通道并非统一按 Markdown 渲染）。
        assertThat(msg.title()).contains("1*2").doesNotContain("1\\*2");
    }

    @Test
    @DisplayName("标记型占位符（*_md）原样代入、不转义，保留其 Markdown 结构")
    void rawMarkdownPlaceholderNotEscaped() {
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("account_id", "42");
        ph.put("tasks_count", "1");
        ph.put("warning_time", "2026-05-27 12:00:00");
        ph.put("trigger_time", "2026-05-27 12:01:00");
        ph.put("tasks_list_md", "- 任务*A*（ID 1）\n- 任务_B_（ID 2）");

        PushMessage msg = factory.render("overuse-paused", PushLevel.WARNING, Locale.SIMPLIFIED_CHINESE, ph);

        assertThat(msg.content()).contains("- 任务*A*（ID 1）\n- 任务_B_（ID 2）");
    }

    @Test
    @DisplayName("缺失占位符兜底为空串，绝不外发裸 {{key}}")
    void missingPlaceholderFallsBackToEmpty() {
        PushMessage msg = factory.render("pending-exhausted", PushLevel.WARNING, Locale.SIMPLIFIED_CHINESE, Map.of());

        assertThat(msg.content()).doesNotContain("{{").doesNotContain("}}");
        assertThat(msg.title()).isNotBlank();
    }

    @Test
    @DisplayName("插件中英文资源键完全一致并包含运行日志与受控详情")
    void pluginBundlesOwnAllRuntimeMessageKeys() throws IOException {
        Properties chinese = loadProperties("i18n/push/messages.properties");
        Properties english = loadProperties("i18n/push/messages_en.properties");

        assertThat(english.stringPropertyNames())
                .containsExactlyInAnyOrderElementsOf(chinese.stringPropertyNames());
        for (Properties bundle : List.of(chinese, english)) {
            assertThat(bundle.stringPropertyNames())
                    .containsAll(RUNTIME_LOG_KEYS)
                    .containsAll(CONTROLLED_DETAIL_KEYS);
            for (String key : RUNTIME_LOG_KEYS) {
                assertThat(bundle.getProperty(key)).as("push 插件资源缺少 %s", key).isNotBlank();
            }
            for (String key : CONTROLLED_DETAIL_KEYS) {
                assertThat(bundle.getProperty(key)).as("push 插件资源缺少 %s", key).isNotBlank();
            }
        }
    }

    private static Properties loadProperties(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Objects.requireNonNull(
                PushMessageFactoryTest.class.getClassLoader().getResourceAsStream(resource), resource);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
