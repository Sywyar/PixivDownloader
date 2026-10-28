package top.sywyar.pixivdownload.schedule.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.testsupport.HostTestMessages;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.plugin.api.notification.ImmutableNotificationTemplateCatalog;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContribution;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

@DisplayName("计划任务通知模板贡献")
class ScheduleNotificationTemplateContributorTest {

    @Test
    @DisplayName("每个场景一次性贡献中英文邮件、推送与站内信模板纯值")
    void everyScenarioContributesAllMediaAndLocales() {
        ScheduleNotificationTemplateContributor contributor =
                new ScheduleNotificationTemplateContributor(
                        HostTestMessages.messages(),
                        List.of(Locale.SIMPLIFIED_CHINESE, Locale.US));
        List<NotificationTemplateContribution> templates = contributor.notificationTemplates();
        ImmutableNotificationTemplateCatalog catalog =
                new ImmutableNotificationTemplateCatalog(templates);

        List<NotificationScenario> scenarios = java.util.Arrays.stream(NotificationScenario.values())
                .filter(scenario -> "download".equals(scenario.categoryId()))
                .toList();
        assertThat(templates).hasSize(scenarios.size() * 6);
        List<String> expectedOrder = new ArrayList<>();
        for (NotificationScenario scenario : scenarios) {
            for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.US)) {
                for (String medium : List.of("mail", "push", "inbox")) {
                    expectedOrder.add(scenario.id() + ":" + locale + ":" + medium);
                }
                NotificationTemplateContribution mail = catalog
                        .find(scenario.id(), "mail", locale).orElseThrow();
                NotificationTemplateContribution push = catalog
                        .find(scenario.id(), "push", locale).orElseThrow();
                NotificationTemplateContribution inbox = catalog
                        .find(scenario.id(), "inbox", locale).orElseThrow();

                assertThat(mail.titleTemplate()).isNotBlank();
                assertThat(mail.bodyTemplate())
                        .startsWith("<table")
                        .doesNotContain("{{i18n:", "PHPSESSID");
                assertThat(push.titleTemplate()).isNotBlank();
                assertThat(push.bodyTemplate())
                        .isNotBlank()
                        .doesNotContain("PHPSESSID");
                assertThat(inbox.titleTemplate()).isEqualTo(push.titleTemplate());
                assertThat(inbox.bodyTemplate())
                        .isEqualTo(push.bodyTemplate());
            }
        }
        assertThat(templates).extracting(template ->
                template.scenarioId() + ":" + template.locale() + ":" + template.medium())
                .containsExactlyElementsOf(expectedOrder);
        assertThatThrownBy(templates::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "push.message.run-summary.body"})
    @DisplayName("后续场景文案缺失或空白时拒绝构造完整模板贡献")
    void missingMessageRejectsContribution(String value) {
        MessageResolver messages = spy(HostTestMessages.messages());
        doReturn(value).when(messages).get(Locale.US, "push.message.run-summary.body");

        assertThatThrownBy(() -> new ScheduleNotificationTemplateContributor(messages, List.of(Locale.US)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("push.message.run-summary.body", "en-US");
    }

    @Test
    @DisplayName("邮件解析失败优先于后续推送失败且已生成快照保持不变")
    void mailFailurePrecedesPushFailureAndKeepsPreviousSnapshot() {
        MessageResolver messages = spy(HostTestMessages.messages());
        List<NotificationTemplateContribution> previous =
                new ScheduleNotificationTemplateContributor(messages, List.of(Locale.US)).notificationTemplates();
        List<NotificationTemplateContribution> snapshot = new ArrayList<>(previous);
        RuntimeException mailFailure = new IllegalStateException("mail lookup failed");
        doThrow(mailFailure).when(messages).get(Locale.US, "mail.template.overuse-paused.subject");
        doThrow(new IllegalStateException("push lookup failed"))
                .when(messages).get(Locale.US, "push.message.overuse-paused.title");

        assertThatThrownBy(() -> new ScheduleNotificationTemplateContributor(messages, List.of(Locale.US)))
                .isSameAs(mailFailure);
        assertThat(previous).containsExactlyElementsOf(snapshot);
    }
}
