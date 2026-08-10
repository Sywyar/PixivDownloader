package top.sywyar.pixivdownload.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.notification.NotificationSeverity;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

    private final PushMessageFactory factory =
            new PushMessageFactory(TestNotificationTemplates.catalog());

    @Test
    @DisplayName("贡献模板替换占位符并保留严重程度与 Markdown 格式")
    void contributedTemplateIsRendered() {
        PushMessage message = factory.render(
                NotificationScenario.RUN_SUMMARY.id(),
                NotificationSeverity.INFO,
                Locale.US,
                Map.of("task_name", "Example task", "completed", "5"));

        assertThat(message.title()).contains("Notification").contains("5");
        assertThat(message.content()).contains("Example task").doesNotContain("{{");
        assertThat(message.level()).isEqualTo(NotificationSeverity.INFO);
        assertThat(message.sourceFormat()).isEqualTo(PushFormat.MARKDOWN);
    }

    @Test
    @DisplayName("正文数据占位符转义而标记型 Markdown 原样代入")
    void markdownPlaceholderPolicyIsPreserved() {
        PushMessage message = factory.render(
                NotificationScenario.POLICY_ACCOUNT_SUSPENDED.id(),
                NotificationSeverity.WARNING,
                Locale.SIMPLIFIED_CHINESE,
                Map.of(
                        "task_name", "画*师_计划",
                        "completed", "1*2",
                        "tasks_list_md", "- 任务*A*\n- 任务_B_"));

        assertThat(message.content())
                .contains("画\\*师\\_计划")
                .contains("1\\*2")
                .contains("- 任务*A*\n- 任务_B_");
        assertThat(message.title()).contains("1*2").doesNotContain("1\\*2");
    }

    @Test
    @DisplayName("push 插件资源只保留介质诊断而不再固定业务模板")
    void pushBundleContainsOnlyMediumOwnedMessages() throws IOException {
        Properties chinese = loadProperties("i18n/push/messages.properties");
        Properties english = loadProperties("i18n/push/messages_en.properties");

        assertThat(english.stringPropertyNames())
                .containsExactlyInAnyOrderElementsOf(chinese.stringPropertyNames());
        for (Properties bundle : List.of(chinese, english)) {
            assertThat(bundle.stringPropertyNames()).containsAll(RUNTIME_LOG_KEYS);
            assertThat(bundle.stringPropertyNames())
                    .noneMatch(key -> key.startsWith("push.message."));
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
