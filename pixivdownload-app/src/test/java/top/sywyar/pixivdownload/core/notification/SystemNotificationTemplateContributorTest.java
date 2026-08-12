package top.sywyar.pixivdownload.core.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.CatalogLocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.LocaleCatalog;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateCatalog;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("宿主系统通知模板贡献")
class SystemNotificationTemplateContributorTest {

    @Test
    @DisplayName("维护失败场景齐全贡献中英文邮件、推送和站内信模板")
    void maintenanceFailureContributesEveryMedium() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        LocaleCatalog catalog = LocaleCatalog.defaultCatalog();
        SystemNotificationTemplateContributor contributor = new SystemNotificationTemplateContributor(
                new AppMessages(source, catalog), new CatalogLocaleBundlePolicy(catalog));
        NotificationTemplateCatalog templates = new NotificationTemplateRegistry(List.of(contributor));

        Arrays.stream(NotificationScenario.values())
                .filter(scenario -> "system".equals(scenario.categoryId()))
                .forEach(scenario -> {
                    assertThat(templates.find(scenario.id(), "inbox", java.util.Locale.SIMPLIFIED_CHINESE))
                            .isPresent();
                    assertThat(templates.find(scenario.id(), "inbox", java.util.Locale.US)).isPresent();
                    String mailBody = templates.find(scenario.id(), "mail", java.util.Locale.US)
                            .orElseThrow().bodyTemplate();
                    assertThat(mailBody).contains("{{task_name_html}}").doesNotContain("{{i18n:");
                    assertThat(templates.find(scenario.id(), "push", java.util.Locale.US)).isPresent();
                });
    }
}
