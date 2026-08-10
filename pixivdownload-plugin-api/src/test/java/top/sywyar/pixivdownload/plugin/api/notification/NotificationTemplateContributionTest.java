package top.sywyar.pixivdownload.plugin.api.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("通知模板贡献契约")
class NotificationTemplateContributionTest {

    @Test
    @DisplayName("模板按场景介质语言查找并对语言变体稳定回退")
    void immutableCatalogFindsExactAndLanguageFallback() {
        NotificationTemplateContribution template = new NotificationTemplateContribution(
                "run-summary", "mail", Locale.US, "Subject {{count}}", "<p>{{count}}</p>");
        ImmutableNotificationTemplateCatalog catalog =
                new ImmutableNotificationTemplateCatalog(List.of(template));

        assertThat(catalog.find("run-summary", "mail", Locale.US)).contains(template);
        assertThat(catalog.find("run-summary", "mail", Locale.ENGLISH)).contains(template);
        assertThat(catalog.scenarioIds("mail")).containsExactly("run-summary");
        assertThat(catalog.find("missing", "mail", Locale.US)).isEmpty();
    }

    @Test
    @DisplayName("契约拒绝重复键和越界正文")
    void duplicateAndOversizedTemplatesAreRejected() {
        NotificationTemplateContribution template = new NotificationTemplateContribution(
                "run-summary", "push", Locale.US, "Title", "Body");

        assertThatThrownBy(() -> new ImmutableNotificationTemplateCatalog(List.of(template, template)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate notification template");
        assertThatThrownBy(() -> new NotificationTemplateContribution(
                "run-summary", "push", Locale.US, "Title",
                "a".repeat(NotificationTemplateContribution.MAX_BODY_BYTES + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size limit");
    }
}
