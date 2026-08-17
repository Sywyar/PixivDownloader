package top.sywyar.pixivdownload.plugin.api.schedule.source;

import java.util.regex.Pattern;

/**
 * 来源类型的声明式展示元数据；namespace、i18n key 与图标 / 颜色均为有界受控 token，
 * 不接受路径、样式片段或其它自由文本。
 */
public record ScheduledSourcePresentation(
        String displayNamespace,
        String displayNameKey,
        String descriptionKey,
        String iconKey,
        String colorToken
) {

    private static final Pattern NAMESPACE =
            Pattern.compile("[a-z][a-z0-9._-]{0,63}");
    private static final Pattern I18N_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,191}");
    private static final Pattern PRESENTATION_TOKEN =
            Pattern.compile("[a-z][a-z0-9-]{0,39}");

    /**
     * 创建 {@code ScheduledSourcePresentation} 实例。
     *
     * @param displayNamespace 显示命名空间
     * @param displayNameKey 显示名称键
     * @param descriptionKey 描述键
     * @param iconKey 图标键
     * @param colorToken 颜色令牌
     */
    public ScheduledSourcePresentation {
        displayNamespace = requireToken(
                displayNamespace, NAMESPACE, "display namespace");
        displayNameKey = requireToken(displayNameKey, I18N_KEY, "display name key");
        descriptionKey = requireToken(descriptionKey, I18N_KEY, "description key");
        iconKey = normalizeToken(iconKey, "schedule");
        colorToken = normalizeToken(colorToken, "neutral");
    }

    private static String requireToken(String value, Pattern pattern, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is not a valid presentation token");
        }
        return normalized;
    }

    private static String normalizeToken(String value, String fallback) {
        return requireToken(
                value == null || value.isBlank() ? fallback : value,
                PRESENTATION_TOKEN,
                "presentation token");
    }
}
