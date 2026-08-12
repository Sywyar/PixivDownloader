package top.sywyar.pixivdownload.notificationbase;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 可由任意站内信分类复用的本地 HTML 快照。 */
public record NotificationHtmlContent(String sourceUrl, String html) {

    public static final int MAX_HTML_BYTES = 1_024 * 1_024;

    public NotificationHtmlContent {
        if (sourceUrl != null && sourceUrl.isBlank()) {
            throw new IllegalArgumentException("notification HTML source URL must not be blank");
        }
        html = Objects.requireNonNull(html, "notification HTML");
        if (html.isBlank() || html.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("notification HTML must not be blank or contain NUL");
        }
        if (html.getBytes(StandardCharsets.UTF_8).length > MAX_HTML_BYTES) {
            throw new IllegalArgumentException("notification HTML exceeds size limit");
        }
    }
}
