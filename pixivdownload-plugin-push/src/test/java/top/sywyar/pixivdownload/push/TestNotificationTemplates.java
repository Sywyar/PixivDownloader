package top.sywyar.pixivdownload.push;

import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.plugin.api.notification.ImmutableNotificationTemplateCatalog;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateCatalog;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContribution;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class TestNotificationTemplates {

    private TestNotificationTemplates() {
    }

    public static NotificationTemplateCatalog catalog() {
        List<NotificationTemplateContribution> templates = new ArrayList<>();
        for (NotificationScenario scenario : NotificationScenario.values()) {
            for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.US)) {
                String title = ("zh".equals(locale.getLanguage()) ? "通知 " : "Notification ")
                        + scenario.id() + " {{completed}}";
                String body = "**{{task_name}}** {{task_id}} {{task_type}} {{task_trigger}} "
                        + "{{account_id}} {{tasks_count}}\n{{tasks_list_md}}\n{{warning_time}} "
                        + "{{trigger_time}} {{next_run_time}} {{last_error_excerpt}} {{work_id}} "
                        + "{{work_kind}} {{work_url}} {{attempts}} {{completed}}";
                templates.add(new NotificationTemplateContribution(
                        scenario.id(), "push", locale, title, body));
            }
        }
        return new ImmutableNotificationTemplateCatalog(templates);
    }

    public static NotificationTemplateCatalog throwing() {
        return new NotificationTemplateCatalog() {
            @Override
            public Optional<NotificationTemplateContribution> find(
                    String scenarioId, String medium, Locale locale) {
                throw new IllegalStateException("test template failure");
            }

            @Override
            public Set<String> scenarioIds(String medium) {
                throw new IllegalStateException("test template failure");
            }
        };
    }
}
