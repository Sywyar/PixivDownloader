package top.sywyar.pixivdownload.notificationbase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.notification.NotificationSink;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateCatalog;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContribution;
import top.sywyar.pixivdownload.push.PushFormat;
import top.sywyar.pixivdownload.push.PushFormatConverter;
import top.sywyar.pixivdownload.push.PushMessage;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将业务通知渲染为纯文本并持久化到管理员站内信。 */
public class InboxNotificationSink implements NotificationSink {

    private static final Logger LOG = LoggerFactory.getLogger(InboxNotificationSink.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.\\-]+)\\s*}}");

    private final NotificationTemplateCatalog templates;
    private final NotificationInboxService inbox;
    private final List<Locale> verifyLocales;
    private final PushFormatConverter formatConverter = new PushFormatConverter();

    public InboxNotificationSink(NotificationTemplateCatalog templates, NotificationInboxService inbox,
                                 List<Locale> verifyLocales) {
        this.templates = Objects.requireNonNull(templates, "notification templates");
        this.inbox = Objects.requireNonNull(inbox, "notification inbox");
        this.verifyLocales = List.copyOf(Objects.requireNonNull(verifyLocales, "supported locales"));
        if (this.verifyLocales.isEmpty()) {
            throw new IllegalArgumentException("supported locales must not be empty");
        }
    }

    @Override
    public String medium() {
        return "inbox";
    }

    @Override
    public void deliver(NotificationScenario scenario, Locale locale, Map<String, String> placeholders) {
        try {
            NotificationTemplateContribution template = template(scenario, locale);
            Map<String, String> values = placeholders == null ? Map.of() : placeholders;
            String title = substitute(template.titleTemplate(), key -> values.getOrDefault(key, ""));
            String markdownBody = substitute(template.bodyTemplate(), key -> markdownValue(key, values.get(key)));
            String body = formatConverter.render(
                    PushMessage.markdown("", markdownBody, scenario.level()), PushFormat.PLAIN_TEXT).body();
            inbox.publish(NotificationCategory.DOWNLOAD, scenario.level(), scenario.id(), title, body, null);
        } catch (Exception exception) {
            LOG.error("Inbox notification [{}] failed: {}", scenario.id(), exception.getMessage());
        }
    }

    @Override
    public void verifyRenderable(NotificationScenario scenario) {
        for (Locale locale : verifyLocales) {
            NotificationTemplateContribution template = template(scenario, locale);
            if (template.titleTemplate().isBlank() || template.bodyTemplate().isBlank()) {
                throw new IllegalStateException("inbox template missing for scenario "
                        + scenario.id() + " @ " + locale);
            }
        }
    }

    private NotificationTemplateContribution template(NotificationScenario scenario, Locale locale) {
        Locale effective = locale == null ? Locale.getDefault() : locale;
        return templates.find(scenario.id(), medium(), effective)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown inbox notification template: " + scenario.id()));
    }

    private String markdownValue(String key, String value) {
        String text = value == null ? "" : value;
        if (key.endsWith("_md")) {
            return text;
        }
        if (key.endsWith("_html")) {
            text = formatConverter.render(PushMessage.html("", text, null), PushFormat.PLAIN_TEXT).body();
        }
        return escapeMarkdownLiteral(text);
    }

    private static String escapeMarkdownLiteral(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ("\\`*_[]".indexOf(character) >= 0) {
                escaped.append('\\');
            }
            escaped.append(character);
        }
        return escaped.toString();
    }

    private static String substitute(String text, Function<String, String> resolver) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder rendered = new StringBuilder(text.length());
        while (matcher.find()) {
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(resolver.apply(matcher.group(1))));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
