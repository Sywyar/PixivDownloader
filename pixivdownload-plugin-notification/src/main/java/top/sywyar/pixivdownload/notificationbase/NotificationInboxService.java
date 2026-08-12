package top.sywyar.pixivdownload.notificationbase;

import top.sywyar.pixivdownload.notification.NotificationSeverity;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContribution;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;

public class NotificationInboxService {

    private static final int MAX_ACTION_URL_BYTES = 8 * 1_024;
    private static final int MAX_CONTENT_URL_BYTES = 2 * 1_024;
    private static final String CONTENT_HOST = "sywyar.github.io";
    private static final String REMOTE_ANNOUNCEMENT_ID_PREFIX = "remote-announcement:";
    private static final Pattern REMOTE_ANNOUNCEMENT_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,79}");
    private static final Pattern CONTENT_PATH = Pattern.compile(
            "/PixivDownloader-Remote-Content/(?:[A-Za-z0-9][A-Za-z0-9._-]*/)*"
                    + "[A-Za-z0-9][A-Za-z0-9._-]*\\.html");

    private final NotificationInboxMapper mapper;
    private final IntSupplier maxMessages;
    private final IntSupplier retentionDays;

    public NotificationInboxService(NotificationInboxMapper mapper) {
        this(mapper, () -> NotificationPlugin.DEFAULT_INBOX_MAX_MESSAGES,
                () -> NotificationPlugin.DEFAULT_INBOX_RETENTION_DAYS);
    }

    NotificationInboxService(NotificationInboxMapper mapper,
                             IntSupplier maxMessages,
                             IntSupplier retentionDays) {
        this.mapper = Objects.requireNonNull(mapper, "notification inbox mapper");
        this.maxMessages = Objects.requireNonNull(maxMessages, "notification inbox max messages");
        this.retentionDays = Objects.requireNonNull(retentionDays, "notification inbox retention days");
    }

    public NotificationMessage publish(NotificationCategory category,
                                       NotificationSeverity severity,
                                       String scenarioId,
                                       String title,
                                       String body,
                                       String actionUrl) {
        return publish(category, severity, scenarioId, title, body, actionUrl, null);
    }

    public NotificationMessage publishHtml(NotificationCategory category,
                                           NotificationSeverity severity,
                                           String scenarioId,
                                           String title,
                                           String body,
                                           String actionUrl,
                                           NotificationHtmlContent htmlContent) {
        return publish(category, severity, scenarioId, title, body, actionUrl,
                validatedHtmlContent(htmlContent));
    }

    private NotificationMessage publish(NotificationCategory category,
                                        NotificationSeverity severity,
                                        String scenarioId,
                                        String title,
                                        String body,
                                        String actionUrl,
                                        NotificationHtmlContent htmlContent) {
        NotificationCategory selectedCategory = Objects.requireNonNull(category, "notification category");
        long now = System.currentTimeMillis();
        NotificationMessage message = new NotificationMessage(
                UUID.randomUUID().toString(),
                selectedCategory.token(),
                Objects.requireNonNull(severity, "notification severity").name(),
                optional(scenarioId),
                requiredText(title, NotificationTemplateContribution.MAX_TITLE_BYTES, "notification title"),
                requiredText(body, NotificationTemplateContribution.MAX_BODY_BYTES, "notification body"),
                htmlContent == null ? null : htmlContent.sourceUrl(),
                htmlContent == null ? null : htmlContent.html(),
                safeActionUrl(actionUrl),
                now,
                null);
        if (mapper.insert(message) == 1 && isRetentionPoolCategory(selectedCategory)) {
            pruneRetentionPool(now);
        }
        return message;
    }

    boolean storeRemoteAnnouncement(String remoteId,
                                    NotificationSeverity severity,
                                    String title,
                                    String summary,
                                    NotificationHtmlContent htmlContent,
                                    long publishedAt) {
        String normalizedId = remoteAnnouncementId(remoteId);
        if (publishedAt < 0) {
            throw new IllegalArgumentException("remote announcement published time is invalid");
        }
        NotificationHtmlContent validatedHtml = validatedHtmlContent(
                Objects.requireNonNull(htmlContent, "remote announcement HTML"));
        NotificationMessage message = new NotificationMessage(
                REMOTE_ANNOUNCEMENT_ID_PREFIX + normalizedId,
                NotificationCategory.ANNOUNCEMENT.token(),
                Objects.requireNonNull(severity, "notification severity").name(),
                null,
                requiredText(title, 160 * 4, "remote announcement title"),
                requiredText(summary, 500 * 4, "remote announcement summary"),
                validatedHtml.sourceUrl(),
                validatedHtml.html(),
                null,
                publishedAt,
                null);
        return mapper.insert(message) == 1
                || mapper.restoreRemoteAnnouncementHtml(
                        message.id(), message.contentUrl(), message.contentHtml()) == 1;
    }

    boolean needsRemoteAnnouncementImport(String remoteId) {
        return mapper.needsRemoteAnnouncementImport(
                REMOTE_ANNOUNCEMENT_ID_PREFIX + remoteAnnouncementId(remoteId));
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

    public NotificationHtmlContent htmlContent(String id) {
        NotificationHtmlContent content = mapper.findHtmlContent(Objects.requireNonNull(id, "notification id"));
        if (content == null) {
            return null;
        }
        try {
            return validatedHtmlContent(content);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public NotificationMessage markRead(String id) {
        mapper.markRead(Objects.requireNonNull(id, "notification id"), System.currentTimeMillis());
        return safeStoredMessage(mapper.findById(id));
    }

    public int markAllRead(NotificationCategory category) {
        return mapper.markAllRead(categoryToken(category), System.currentTimeMillis());
    }

    public boolean delete(String id) {
        String requiredId = Objects.requireNonNull(id, "notification id");
        NotificationMessage message = mapper.findById(requiredId);
        if (message == null) {
            return false;
        }
        if (NotificationCategory.ANNOUNCEMENT.token().equals(message.category())) {
            return mapper.dismissAnnouncement(requiredId, System.currentTimeMillis()) == 1;
        }
        return mapper.deleteNonAnnouncement(requiredId) == 1;
    }

    int pruneRetentionPool() {
        return pruneRetentionPool(System.currentTimeMillis());
    }

    int pruneRetentionPool(long now) {
        int days = positiveSetting(retentionDays, NotificationPlugin.DEFAULT_INBOX_RETENTION_DAYS);
        long maxAgeMillis = TimeUnit.DAYS.toMillis(days);
        long cutoffTime = now > maxAgeMillis ? now - maxAgeMillis : 0;
        return mapper.pruneRetentionPool(
                cutoffTime,
                positiveSetting(maxMessages, NotificationPlugin.DEFAULT_INBOX_MAX_MESSAGES));
    }

    private static String categoryToken(NotificationCategory category) {
        return category == null ? null : category.token();
    }

    private static boolean isRetentionPoolCategory(NotificationCategory category) {
        return category == NotificationCategory.DOWNLOAD || category == NotificationCategory.SYSTEM;
    }

    private static String remoteAnnouncementId(String value) {
        String normalized = Objects.requireNonNull(value, "remote announcement id");
        if (!REMOTE_ANNOUNCEMENT_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("remote announcement id is invalid");
        }
        return normalized;
    }

    private static int positiveSetting(IntSupplier supplier, int defaultValue) {
        try {
            int value = supplier.getAsInt();
            return value > 0 ? value : defaultValue;
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
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

    static String safeContentUrl(String value) {
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

    private static NotificationHtmlContent validatedHtmlContent(NotificationHtmlContent content) {
        if (content == null) {
            return null;
        }
        String sourceUrl = content.sourceUrl() == null ? null : safeContentUrl(content.sourceUrl());
        return new NotificationHtmlContent(sourceUrl, content.html());
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
        String contentHtml = message.contentHtml() == null
                || (message.contentUrl() != null && contentUrl == null) ? null : "";
        if (Objects.equals(contentUrl, message.contentUrl())
                && Objects.equals(contentHtml, message.contentHtml())) {
            return message;
        }
        return new NotificationMessage(
                message.id(), message.category(), message.severity(), message.scenarioId(),
                message.title(), message.body(), contentUrl, contentHtml, message.actionUrl(),
                message.createdTime(), message.readTime());
    }
}
