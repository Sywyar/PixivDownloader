package top.sywyar.pixivdownload.notificationbase;

import java.util.Arrays;
import java.util.Locale;

/** 站内信稳定分类；公告和调查先共用消息模型与可选链接。 */
public enum NotificationCategory {
    DOWNLOAD("download"),
    ANNOUNCEMENT("announcement"),
    SURVEY("survey"),
    SYSTEM("system");

    private final String token;

    NotificationCategory(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public static NotificationCategory fromToken(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(category -> category.token.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown notification category"));
    }
}
