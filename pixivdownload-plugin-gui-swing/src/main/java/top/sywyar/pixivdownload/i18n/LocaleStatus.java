package top.sywyar.pixivdownload.i18n;

import java.util.Locale;

public enum LocaleStatus {
    SOURCE, SUPPORTED, CANDIDATE, DISABLED;

    public static LocaleStatus fromJson(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("locale status is missing");
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "source" -> SOURCE;
            case "supported" -> SUPPORTED;
            case "candidate" -> CANDIDATE;
            case "disabled" -> DISABLED;
            default -> throw new IllegalArgumentException("unknown locale status: " + value);
        };
    }

    public boolean visible() { return this == SOURCE || this == SUPPORTED; }
}
