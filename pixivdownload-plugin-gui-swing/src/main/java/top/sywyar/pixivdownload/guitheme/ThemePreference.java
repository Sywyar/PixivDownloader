package top.sywyar.pixivdownload.guitheme;

import java.util.Locale;

enum ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
    MOONLIGHT;

    String toConfigValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    static ThemePreference fromConfigValue(String value) {
        if (value == null) {
            return SYSTEM;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return SYSTEM;
        }
        return switch (normalized) {
            case "light" -> LIGHT;
            case "dark" -> DARK;
            case "moonlight" -> MOONLIGHT;
            default -> SYSTEM;
        };
    }

    boolean isNamedTheme() {
        return this != SYSTEM && this != LIGHT && this != DARK;
    }
}
