package top.sywyar.pixivdownload.douyin.settings;

import java.util.Locale;

public enum DouyinProxyMode {
    INHERIT("inherit"),
    PROXY("proxy"),
    CUSTOM("custom"),
    DIRECT("direct");

    private final String wireValue;

    DouyinProxyMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static DouyinProxyMode from(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "proxy" -> PROXY;
            case "custom" -> CUSTOM;
            case "direct" -> DIRECT;
            default -> INHERIT;
        };
    }
}
