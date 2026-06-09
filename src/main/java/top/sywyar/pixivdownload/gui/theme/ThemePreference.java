package top.sywyar.pixivdownload.gui.theme;

/**
 * GUI 主题偏好。{@link #SYSTEM} 表示跟随操作系统切换浅/深，
 * {@link #LIGHT} / {@link #DARK} 表示用户强制锁定。
 * 序列化为 {@code config.yaml} 的 {@code app.theme} 时统一使用小写名称。
 */
public enum ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
    MOONLIGHT;

    public String toConfigValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static ThemePreference fromConfigValue(String value) {
        if (value == null) return SYSTEM;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) return SYSTEM;
        return switch (normalized) {
            case "light" -> LIGHT;
            case "dark" -> DARK;
            case "moonlight" -> MOONLIGHT;
            default -> SYSTEM;
        };
    }

    public boolean isNamedTheme() {
        return this != SYSTEM && this != LIGHT && this != DARK;
    }
}
