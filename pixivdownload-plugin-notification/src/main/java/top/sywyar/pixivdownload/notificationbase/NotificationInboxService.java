package top.sywyar.pixivdownload.notificationbase;

import top.sywyar.pixivdownload.notification.NotificationSeverity;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContribution;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public class NotificationInboxService {

    private static final int MAX_ACTION_URL_BYTES = 8 * 1_024;
    private static final int MAX_CONTENT_URL_BYTES = 2 * 1_024;
    private static final String CONTENT_HOST = "sywyar.github.io";
    private static final Pattern CONTENT_PATH = Pattern.compile(
            "/PixivDownloader-Remote-Content/(?:[A-Za-z0-9][A-Za-z0-9._-]*/)*"
                    + "[A-Za-z0-9][A-Za-z0-9._-]*\\.html");

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
        return publish(category, severity, scenarioId, title, body, actionUrl, null);
    }

    public NotificationMessage publish(NotificationCategory category,
                                       NotificationSeverity severity,
                                       String scenarioId,
                                       String title,
                                       String body,
                                       String actionUrl,
                                       String contentUrl) {
        long now = System.currentTimeMillis();
        NotificationMessage message = new NotificationMessage(
                UUID.randomUUID().toString(),
                Objects.requireNonNull(category, "notification category").token(),
                Objects.requireNonNull(severity, "notification severity").name(),
                optional(scenarioId),
                requiredText(title, NotificationTemplateContribution.MAX_TITLE_BYTES, "notification title"),
                requiredText(body, NotificationTemplateContribution.MAX_BODY_BYTES, "notification body"),
                safeContentUrl(contentUrl),
                safeActionUrl(actionUrl),
                now,
                null);
        mapper.insert(message);
        return message;
    }

    public List<NotificationMessage> latest(NotificationCategory category, boolean unreadOnly, int limit) {
        return mapper.findLatest(categoryToken(category), unreadOnly, Math.max(1, Math.min(100, limit))).stream()
                .map(NotificationInboxService::safeStoredMessage)
                .toList();
    }

    public long unreadCount() {
        return mapper.countUnread(null);
    }

    public long unreadCount(NotificationCategory category) {
        return mapper.countUnread(categoryToken(category));
    }

    public NotificationMessage find(String id) {
        return safeStoredMessage(mapper.findById(Objects.requireNonNull(id, "notification id")));
    }

    public NotificationMessage markRead(String id) {
        mapper.markRead(Objects.requireNonNull(id, "notification id"), System.currentTimeMillis());
        return safeStoredMessage(mapper.findById(id));
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

    private static String safeContentUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_URL_BYTES
                || hasForbiddenUrlCharacter(normalized)) {
            throw new IllegalArgumentException("notification content URL is invalid");
        }
        try {
            URI uri = new URI(normalized);
            String rawPath = uri.getRawPath();
            if ("https".equals(uri.getScheme())
                    && CONTENT_HOST.equals(uri.getHost())
                    && uri.getUserInfo() == null
                    && uri.getPort() == -1
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && rawPath != null
                    && CONTENT_PATH.matcher(rawPath).matches()
                    && uri.normalize().equals(uri)
                    && uri.toASCIIString().equals(normalized)) {
                return normalized;
            }
        } catch (URISyntaxException ignored) {
            // 统一在下方拒绝。
        }
        throw new IllegalArgumentException("notification content URL is outside the trusted content source");
    }

    private static boolean hasForbiddenUrlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\' || Character.isISOControl(character)) {
                return true;
            }
        }
        return false;
    }

    private static NotificationMessage safeStoredMessage(NotificationMessage message) {
        if (message == null) {
            return null;
        }
        String contentUrl;
        try {
            contentUrl = safeContentUrl(message.contentUrl());
        } catch (IllegalArgumentException ignored) {
            contentUrl = null;
        }
        if (Objects.equals(contentUrl, message.contentUrl())) {
            return message;
        }
        return new NotificationMessage(
                message.id(), message.category(), message.severity(), message.scenarioId(),
                message.title(), message.body(), contentUrl, message.actionUrl(),
                message.createdTime(), message.readTime());
    }
}
