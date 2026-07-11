package top.sywyar.pixivdownload.plugin.api.schedule.source;

/** 来源类型的声明式展示元数据；只保存 i18n key 与受控展示 token。 */
public record ScheduledSourcePresentation(
        String displayNamespace,
        String displayNameKey,
        String descriptionKey,
        String iconKey,
        String colorToken
) {

    public ScheduledSourcePresentation {
        if (displayNamespace == null || displayNamespace.isBlank()) {
            throw new IllegalArgumentException("display namespace must not be blank");
        }
        if (displayNameKey == null || displayNameKey.isBlank()) {
            throw new IllegalArgumentException("display name key must not be blank");
        }
        if (descriptionKey == null || descriptionKey.isBlank()) {
            throw new IllegalArgumentException("description key must not be blank");
        }
        displayNamespace = displayNamespace.trim();
        displayNameKey = displayNameKey.trim();
        descriptionKey = descriptionKey.trim();
        iconKey = normalizeToken(iconKey, "schedule");
        colorToken = normalizeToken(colorToken, "neutral");
    }

    private static String normalizeToken(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
