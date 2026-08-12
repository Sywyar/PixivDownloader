package top.sywyar.pixivdownload.core.notification;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContribution;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContributor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 宿主系统事件的 mail/push/inbox 模板 owner。 */
@Component
public final class SystemNotificationTemplateContributor implements NotificationTemplateContributor {

    private static final Pattern I18N_PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*i18n:([a-zA-Z0-9_.-]+)\\s*}}");

    private final List<NotificationTemplateContribution> templates;

    public SystemNotificationTemplateContributor(AppMessages messages, LocaleBundlePolicy localePolicy) {
        List<NotificationTemplateContribution> result = new ArrayList<>();
        for (NotificationScenario scenario : NotificationScenario.values()) {
            if (!"system".equals(scenario.categoryId())) {
                continue;
            }
            String html = loadHtml(scenario.id());
            for (Locale locale : localePolicy.supportedLocales()) {
                String prefix = "notification.system." + scenario.id();
                String title = required(messages, locale, prefix + ".title");
                String body = required(messages, locale, prefix + ".body");
                result.add(new NotificationTemplateContribution(
                        scenario.id(), "mail", locale, title,
                        resolveI18n(html, locale, messages)));
                result.add(new NotificationTemplateContribution(
                        scenario.id(), "push", locale, title, body));
                result.add(new NotificationTemplateContribution(
                        scenario.id(), "inbox", locale, title, body));
            }
        }
        templates = List.copyOf(result);
    }

    @Override
    public List<NotificationTemplateContribution> notificationTemplates() {
        return templates;
    }

    private static String loadHtml(String id) {
        String path = "notification/templates/mail/" + id + ".html";
        try (InputStream input = SystemNotificationTemplateContributor.class
                .getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("system notification mail template not found: " + id);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read system notification mail template: " + id, exception);
        }
    }

    private static String resolveI18n(String html, Locale locale, AppMessages messages) {
        Matcher matcher = I18N_PLACEHOLDER.matcher(html);
        StringBuilder resolved = new StringBuilder(html.length());
        while (matcher.find()) {
            matcher.appendReplacement(resolved,
                    Matcher.quoteReplacement(required(messages, locale, matcher.group(1))));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private static String required(AppMessages messages, Locale locale, String key) {
        String value = messages.get(locale, key);
        if (value == null || value.isBlank() || key.equals(value)) {
            throw new IllegalStateException("system notification i18n missing: " + key
                    + " @ " + locale.toLanguageTag());
        }
        return value;
    }
}
