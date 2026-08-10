package top.sywyar.pixivdownload.mail;

import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.plugin.api.notification.ImmutableNotificationTemplateCatalog;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateCatalog;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContribution;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TestNotificationTemplates {

    private TestNotificationTemplates() {
    }

    public static NotificationTemplateCatalog catalog() {
        List<NotificationTemplateContribution> templates = new ArrayList<>();
        for (NotificationScenario scenario : NotificationScenario.values()) {
            for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.US)) {
                String title = ("zh".equals(locale.getLanguage()) ? "通知 " : "Notification ")
                        + scenario.id() + " {{completed}}{{consecutive_failures}}";
                String body = "<p>{{task_name}} {{task_id}} {{task_type}} {{task_trigger}} "
                        + "{{account_id}} {{tasks_count}} {{tasks_list_html}} {{warning_time}} "
                        + "{{trigger_time}} {{next_run_time}} {{last_error_excerpt}} {{work_id}} "
                        + "{{work_kind}} {{work_url}} {{attempts}} {{completed}}</p>";
                templates.add(new NotificationTemplateContribution(
                        scenario.id(), "mail", locale, title, body));
            }
        }
        return new ImmutableNotificationTemplateCatalog(templates);
    }
}
