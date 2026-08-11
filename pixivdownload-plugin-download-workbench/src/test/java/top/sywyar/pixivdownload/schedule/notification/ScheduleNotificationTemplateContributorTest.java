package top.sywyar.pixivdownload.schedule.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.download.testsupport.WorkbenchTestMessages;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.plugin.api.notification.ImmutableNotificationTemplateCatalog;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContribution;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("计划任务通知模板贡献")
class ScheduleNotificationTemplateContributorTest {

    @Test
    @DisplayName("每个场景一次性贡献中英文邮件、推送与站内信模板纯值")
    void everyScenarioContributesAllMediaAndLocales() {
        ScheduleNotificationTemplateContributor contributor =
                new ScheduleNotificationTemplateContributor(
                        WorkbenchTestMessages.messages(),
                        List.of(Locale.SIMPLIFIED_CHINESE, Locale.US));
        List<NotificationTemplateContribution> templates = contributor.notificationTemplates();
        ImmutableNotificationTemplateCatalog catalog =
                new ImmutableNotificationTemplateCatalog(templates);

        List<NotificationScenario> scenarios = java.util.Arrays.stream(NotificationScenario.values())
                .filter(scenario -> "download".equals(scenario.categoryId()))
                .toList();
        assertThat(templates).hasSize(scenarios.size() * 6);
        for (NotificationScenario scenario : scenarios) {
            for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.US)) {
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
                        .isEqualTo(push.bodyTemplate())
                        .doesNotContain("PHPSESSID");
            }
        }
    }
}
