package top.sywyar.pixivdownload.schedule.notification;

import top.sywyar.pixivdownload.i18n.MessageResolver;
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

/** 计划任务通知模板的实际所有者；构造时一次性物化中英文 mail/push/inbox 纯值。 */
public final class ScheduleNotificationTemplateContributor implements NotificationTemplateContributor {

    private static final Pattern I18N_PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*i18n:([a-zA-Z0-9_.-]+)\\s*}}");

    private final List<NotificationTemplateContribution> templates;

    public ScheduleNotificationTemplateContributor(MessageResolver messages, List<Locale> locales) {
        templates = loadTemplates(messages, List.copyOf(locales));
    }

    @Override
    public List<NotificationTemplateContribution> notificationTemplates() {
        return templates;
    }

    private static List<NotificationTemplateContribution> loadTemplates(
            MessageResolver messages, List<Locale> locales) {
        List<NotificationTemplateContribution> result = new ArrayList<>();
        for (NotificationScenario scenario : NotificationScenario.values()) {
            String id = scenario.id();
            String html = loadHtml(id);
            for (Locale locale : locales) {
                result.add(new NotificationTemplateContribution(
                        id,
                        "mail",
                        locale,
                        message(messages, locale, "mail.template." + id + ".subject"),
                        resolveI18n(html, locale, messages)));
                result.add(new NotificationTemplateContribution(
                        id,
                        "push",
                        locale,
                        message(messages, locale, "push.message." + id + ".title"),
                        message(messages, locale, "push.message." + id + ".body")));
                result.add(new NotificationTemplateContribution(
                        id,
                        "inbox",
                        locale,
                        message(messages, locale, "push.message." + id + ".title"),
                        message(messages, locale, "push.message." + id + ".body")));
            }
        }
        return List.copyOf(result);
    }

    private static String loadHtml(String id) {
        String path = "notification/templates/mail/" + id + ".html";
        try (InputStream input = ScheduleNotificationTemplateContributor.class
                .getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("notification mail template not found: " + id);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read notification mail template: " + id, exception);
        }
    }

    private static String resolveI18n(String html, Locale locale, MessageResolver messages) {
        Matcher matcher = I18N_PLACEHOLDER.matcher(html);
        StringBuilder resolved = new StringBuilder(html.length());
        while (matcher.find()) {
            matcher.appendReplacement(
                    resolved,
                    Matcher.quoteReplacement(message(messages, locale, matcher.group(1))));
        }
        matcher.appendTail(resolved);
        if (I18N_PLACEHOLDER.matcher(resolved).find()) {
            throw new IllegalStateException("notification mail template contains unresolved i18n placeholder");
        }
        return resolved.toString();
    }

    private static String message(MessageResolver messages, Locale locale, String code) {
        String value = messages.get(locale, code);
        if (value == null || value.isBlank() || value.equals(code)) {
            throw new IllegalStateException("notification template i18n missing: " + code
                    + " @ " + locale.toLanguageTag());
        }
        return value;
    }
}
