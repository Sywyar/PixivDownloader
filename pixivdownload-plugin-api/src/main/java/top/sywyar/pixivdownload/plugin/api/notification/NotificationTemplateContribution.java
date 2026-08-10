package top.sywyar.pixivdownload.plugin.api.notification;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 插件贡献的一份已本地化通知模板。模板只携带纯值，不得捕获插件 Bean、资源句柄或 ClassLoader。
 *
 * @param scenarioId   稳定场景 id
 * @param medium       通知介质 id，例如 {@code mail} / {@code push}
 * @param locale       模板语言
 * @param titleTemplate 标题模板，可含 {@code {{key}}} 运行期占位符
 * @param bodyTemplate  正文模板，可含 {@code {{key}}} 运行期占位符
 */
public record NotificationTemplateContribution(
        String scenarioId,
        String medium,
        Locale locale,
        String titleTemplate,
        String bodyTemplate
) {

    public static final int MAX_TITLE_BYTES = 16 * 1_024;
    public static final int MAX_BODY_BYTES = 1_024 * 1_024;

    private static final Pattern SCENARIO_ID = Pattern.compile("[a-z0-9][a-z0-9.-]{0,127}");
    private static final Pattern MEDIUM = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    public NotificationTemplateContribution {
        scenarioId = token(scenarioId, SCENARIO_ID, "notification scenario id");
        medium = token(medium, MEDIUM, "notification medium");
        locale = normalizeLocale(locale);
        titleTemplate = template(titleTemplate, MAX_TITLE_BYTES, "notification template title");
        bodyTemplate = template(bodyTemplate, MAX_BODY_BYTES, "notification template body");
    }

    private static String token(String value, Pattern pattern, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static Locale normalizeLocale(Locale value) {
        Locale locale = Objects.requireNonNull(value, "notification template locale");
        if (locale.getLanguage().isBlank()) {
            throw new IllegalArgumentException("notification template locale must have a language");
        }
        return Locale.forLanguageTag(locale.toLanguageTag());
    }

    private static String template(String value, int maxBytes, String field) {
        String template = Objects.requireNonNull(value, field);
        if (template.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (template.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " must not contain NUL");
        }
        if (template.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(field + " exceeds size limit");
        }
        return template;
    }
}
