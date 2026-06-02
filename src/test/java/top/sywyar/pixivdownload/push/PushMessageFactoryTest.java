package top.sywyar.pixivdownload.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PushMessageFactory 单元测试")
class PushMessageFactoryTest {

    private final AppMessages messages = TestI18nBeans.appMessages();
    private final PushMessageFactory factory = new PushMessageFactory(messages);

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
    @DisplayName("缺失占位符兜底为空串，绝不外发裸 {{key}}")
    void missingPlaceholderFallsBackToEmpty() {
        PushMessage msg = factory.render("pending-exhausted", PushLevel.WARNING, Locale.SIMPLIFIED_CHINESE, Map.of());

        assertThat(msg.content()).doesNotContain("{{").doesNotContain("}}");
        assertThat(msg.title()).isNotBlank();
    }
}
