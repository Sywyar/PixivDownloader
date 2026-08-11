package top.sywyar.pixivdownload.notificationbase;

import top.sywyar.pixivdownload.notification.NotificationSeverity;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContribution;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class NotificationInboxService {

    private static final int MAX_ACTION_URL_BYTES = 8 * 1_024;

    private final NotificationInboxMapper mapper;

    public NotificationInboxService(NotificationInboxMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "notification inbox mapper");
    }

    public NotificationMessage publish(NotificationCategory category,
                                       NotificationSeverity severity,
                                       String scenarioId,
                                       String title,
                                       String body,
                                       String actionUrl) {
        long now = System.currentTimeMillis();
        NotificationMessage message = new NotificationMessage(
                UUID.randomUUID().toString(),
                Objects.requireNonNull(category, "notification category").token(),
                Objects.requireNonNull(severity, "notification severity").name(),
                optional(scenarioId),
                requiredText(title, NotificationTemplateContribution.MAX_TITLE_BYTES, "notification title"),
                requiredText(body, NotificationTemplateContribution.MAX_BODY_BYTES, "notification body"),
                safeActionUrl(actionUrl),
                now,
                null);
        mapper.insert(message);
        return message;
    }

    public List<NotificationMessage> latest(NotificationCategory category, boolean unreadOnly, int limit) {
        return List.copyOf(mapper.findLatest(categoryToken(category), unreadOnly, Math.max(1, Math.min(100, limit))));
    }

    public long unreadCount() {
        return mapper.countUnread(null);
    }

    public long unreadCount(NotificationCategory category) {
        return mapper.countUnread(categoryToken(category));
    }

    public NotificationMessage find(String id) {
        return mapper.findById(Objects.requireNonNull(id, "notification id"));
    }

    public NotificationMessage markRead(String id) {
        mapper.markRead(Objects.requireNonNull(id, "notification id"), System.currentTimeMillis());
        return mapper.findById(id);
    }

    public int markAllRead(NotificationCategory category) {
        return mapper.markAllRead(categoryToken(category), System.currentTimeMillis());
    }

    private static String categoryToken(NotificationCategory category) {
        return category == null ? null : category.token();
    }

    private static String requiredText(String value, int maxBytes, String field) {
        String text = Objects.requireNonNull(value, field);
        if (text.isBlank() || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " must not be blank or contain NUL");
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(field + " exceeds size limit");
        }
        return text;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String safeActionUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_ACTION_URL_BYTES
                || normalized.indexOf('\0') >= 0 || normalized.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("notification action URL is invalid");
        }
        try {
            URI uri = new URI(normalized);
            if (normalized.startsWith("/") && !normalized.startsWith("//")
                    && !uri.isAbsolute() && uri.getRawAuthority() == null
                    && uri.normalize().toString().equals(normalized)) {
                return normalized;
            }
            String scheme = uri.getScheme();
            if (uri.isAbsolute() && uri.getHost() != null && uri.getUserInfo() == null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return normalized;
            }
        } catch (URISyntaxException ignored) {
            // 统一在下方拒绝。
        }
        throw new IllegalArgumentException("notification action URL must be a safe path or HTTP(S) URL");
    }
}
