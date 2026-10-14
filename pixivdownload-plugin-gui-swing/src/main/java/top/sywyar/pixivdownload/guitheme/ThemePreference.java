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

    boolean isNamedTheme() {
        return this != SYSTEM && this != LIGHT && this != DARK;
    }
}
