package top.sywyar.pixivdownload.notificationbase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.plugin.api.notification.ImmutableNotificationTemplateCatalog;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContribution;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("站内信通知介质")
class InboxNotificationSinkTest {

    private static final NotificationScenario SCENARIO = NotificationScenario.RUN_SUMMARY;

    @Test
    @DisplayName("将贡献模板渲染为不含 Markdown 标记的下载通知")
    void rendersTemplateIntoPlainInboxMessage() {
        NotificationInboxServiceTest.MemoryMapper mapper = new NotificationInboxServiceTest.MemoryMapper();
        InboxNotificationSink sink = new InboxNotificationSink(
                catalog(true), new NotificationInboxService(mapper), verifyLocales(), () -> true);

        sink.deliver(SCENARIO, Locale.US, Map.of(
                "task_name", "Daily *Task*",
                "tasks_list_md", "- **First**\n- Second"));

        assertThat(mapper.findLatest(null, false, 10)).singleElement().satisfies(message -> {
            assertThat(message.category()).isEqualTo("download");
            assertThat(message.scenarioId()).isEqualTo(SCENARIO.id());
            assertThat(message.title()).isEqualTo("Done: Daily *Task*");
            assertThat(message.body())
                    .contains("Task Daily *Task*", "- First", "- Second")
                    .doesNotContain("**");
        });
    }

    @Test
    @DisplayName("齐全校验覆盖中英文且发送路径始终 best-effort")
    void verifiesLocalesAndNeverThrowsOnMissingTemplate() {
        NotificationInboxServiceTest.MemoryMapper mapper = new NotificationInboxServiceTest.MemoryMapper();
        InboxNotificationSink complete = new InboxNotificationSink(
                catalog(true), new NotificationInboxService(mapper), verifyLocales(), () -> true);
        InboxNotificationSink incomplete = new InboxNotificationSink(
                catalog(false), new NotificationInboxService(mapper), verifyLocales(), () -> true);

        assertThatCode(() -> complete.verifyRenderable(SCENARIO)).doesNotThrowAnyException();
        assertThatThrownBy(() -> incomplete.verifyRenderable(SCENARIO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> incomplete.deliver(SCENARIO, Locale.SIMPLIFIED_CHINESE, Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("关闭站内信服务后不再保存新消息")
    void skipsDeliveryWhenDisabled() {
        NotificationInboxServiceTest.MemoryMapper mapper = new NotificationInboxServiceTest.MemoryMapper();
        InboxNotificationSink sink = new InboxNotificationSink(
                catalog(true), new NotificationInboxService(mapper), verifyLocales(), () -> false);

        sink.deliver(SCENARIO, Locale.US, Map.of());

        assertThat(mapper.findLatest(null, false, 10)).isEmpty();
    }

    private static ImmutableNotificationTemplateCatalog catalog(boolean includeChinese) {
        List<NotificationTemplateContribution> templates = new java.util.ArrayList<>();
        templates.add(new NotificationTemplateContribution(
                SCENARIO.id(), "inbox", Locale.US,
                "Done: {{task_name}}", "**Task** {{task_name}}\n\n{{tasks_list_md}}"));
        if (includeChinese) {
            templates.add(new NotificationTemplateContribution(
                    SCENARIO.id(), "inbox", Locale.SIMPLIFIED_CHINESE,
                    "完成：{{task_name}}", "**任务** {{task_name}}\n\n{{tasks_list_md}}"));
        }
        return new ImmutableNotificationTemplateCatalog(templates);
    }

    private static List<Locale> verifyLocales() {
        return List.of(Locale.SIMPLIFIED_CHINESE, Locale.US);
    }
}
