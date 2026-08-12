package top.sywyar.pixivdownload.notificationbase;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.notification.NotificationSeverity;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpStreamResponse;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpTransportException;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** 从固定 GitHub Pages 索引拉取并幂等保存管理员公告。 */
public final class RemoteAnnouncementImporter {

    static final URI INDEX_URI = URI.create(
            "https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/index.json");
    static final long POLL_DELAY_MILLIS = 6L * 60 * 60 * 1_000;
    static final int MAX_INDEX_BYTES = 1_024 * 1_024;
    static final int MAX_ANNOUNCEMENTS = 100;

    private static final Logger LOG = LoggerFactory.getLogger(RemoteAnnouncementImporter.class);
    private static final String PUBLIC_ANNOUNCEMENT_BASE =
            "https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/";
    private static final Pattern LOCALE_TAG = Pattern.compile(
            "[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})+");
    private static final Set<String> ROOT_FIELDS =
            Set.of("schemaVersion", "requiredLocales", "announcements");
    private static final Set<String> ANNOUNCEMENT_FIELDS =
            Set.of("id", "publishedAt", "severity", "locales");
    private static final Set<String> TRANSLATION_FIELDS =
            Set.of("title", "summary", "contentUrl");

    private final OutboundHttpClient client;
    private final ObjectMapper objectMapper;
    private final NotificationInboxService inbox;
    private final LocaleBundlePolicy localePolicy;
    private final Supplier<Locale> currentLocale;

    RemoteAnnouncementImporter(OutboundHttpClient client,
                               ObjectMapper objectMapper,
                               NotificationInboxService inbox,
                               LocaleBundlePolicy localePolicy,
                               Supplier<Locale> currentLocale) {
        this.client = Objects.requireNonNull(client, "remote announcement HTTP client");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.inbox = Objects.requireNonNull(inbox, "notification inbox");
        this.localePolicy = Objects.requireNonNull(localePolicy, "locale policy");
        this.currentLocale = Objects.requireNonNull(currentLocale, "current locale supplier");
    }

    @Scheduled(
            initialDelay = 0,
            fixedDelay = POLL_DELAY_MILLIS,
            scheduler = "notificationAnnouncementTaskScheduler")
    public void poll() {
        try {
            int imported = importIndex(fetchIndex());
            if (imported > 0) {
                LOG.info("Imported {} remote announcement(s)", imported);
            }
        } catch (RejectedIndex exception) {
            LOG.warn("Remote announcement index rejected [{}]", exception.code);
        } catch (RuntimeException exception) {
            LOG.warn("Remote announcement poll failed [{}]", exception.getClass().getSimpleName());
        }
    }

    int importIndex(byte[] bytes) {
        JsonNode root = parse(bytes);
        requireFields(root, ROOT_FIELDS, "root-fields");
        JsonNode schemaVersion = root.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.isIntegralNumber() || schemaVersion.intValue() != 1) {
            throw rejected("schema-version");
        }
        Set<String> requiredLocales = requiredLocales(root.get("requiredLocales"));
        JsonNode announcements = root.get("announcements");
        if (announcements == null || !announcements.isArray()
                || announcements.size() > MAX_ANNOUNCEMENTS) {
            throw rejected("announcement-limit");
        }

        int imported = 0;
        Set<String> seenIds = new HashSet<>();
        for (int position = 0; position < announcements.size(); position++) {
            try {
                Announcement announcement = announcement(
                        announcements.get(position), requiredLocales, seenIds);
                if (inbox.insertRemoteAnnouncementIfAbsent(
                        announcement.id(), announcement.severity(),
                        announcement.translation().title(), announcement.translation().summary(),
                        announcement.translation().contentUrl(), announcement.publishedAt())) {
                    imported++;
                }
            } catch (RejectedIndex | IllegalArgumentException exception) {
                String code = exception instanceof RejectedIndex rejected ? rejected.code : "invalid-field";
                LOG.warn("Remote announcement item {} rejected [{}]", position, code);
            }
        }
        return imported;
    }

    private byte[] fetchIndex() {
        OutboundHttpRequest request = new OutboundHttpRequest(
                INDEX_URI,
                "GET",
                Map.of("Accept", List.of("application/json")),
                new byte[0]);
        try (OutboundHttpStreamResponse response = client.exchangeStream(request)) {
            if (response.statusCode() != 200) {
                throw rejected("http-status");
            }
            requireJsonContentType(response.headers().get("Content-Type"));
            rejectOversizeContentLength(response.headers().get("Content-Length"));
            byte[] bytes = response.body().readNBytes(MAX_INDEX_BYTES + 1);
            if (bytes.length > MAX_INDEX_BYTES) {
                throw rejected("response-size");
            }
            return bytes;
        } catch (IOException exception) {
            throw new OutboundHttpTransportException(
                    "Failed to read remote announcement index", exception);
        }
    }

    private JsonNode parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_INDEX_BYTES) {
            throw rejected("response-size");
        }
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            JsonNode root = objectMapper.readTree(json);
            if (root == null) {
                throw rejected("invalid-json");
            }
            return root;
        } catch (CharacterCodingException | JsonProcessingException exception) {
            throw rejected("invalid-json");
        }
    }

    private Announcement announcement(JsonNode node,
                                      Set<String> requiredLocales,
                                      Set<String> seenIds) {
        requireFields(node, ANNOUNCEMENT_FIELDS, "announcement-fields");
        String id = requiredText(node.get("id"), 80, "id");
        if (!seenIds.add(id)) {
            throw rejected("duplicate-id");
        }
        long publishedAt = publishedAt(requiredText(node.get("publishedAt"), 32, "published-at"));
        NotificationSeverity severity = severity(requiredText(node.get("severity"), 8, "severity"));
        JsonNode locales = node.get("locales");
        if (locales == null || !locales.isObject() || !fieldNames(locales).equals(requiredLocales)) {
            throw rejected("locales");
        }

        String selectedLocale = selectLocale(requiredLocales);
        Translation selected = null;
        for (String locale : requiredLocales) {
            JsonNode translation = locales.get(locale);
            requireFields(translation, TRANSLATION_FIELDS, "translation-fields");
            String title = requiredText(translation.get("title"), 160, "title");
            String summary = requiredText(translation.get("summary"), 500, "summary");
            String contentUrl = requiredText(translation.get("contentUrl"), 2_048, "content-url");
            String expectedUrl = PUBLIC_ANNOUNCEMENT_BASE + id + "/" + locale + ".html";
            if (!NotificationInboxService.safeContentUrl(contentUrl).equals(expectedUrl)) {
                throw rejected("content-url");
            }
            if (locale.equals(selectedLocale)) {
                selected = new Translation(title, summary, contentUrl);
            }
        }
        if (selected == null) {
            throw rejected("locale-fallback");
        }
        return new Announcement(id, publishedAt, severity, selected);
    }

    private String selectLocale(Set<String> locales) {
        Locale target = localePolicy.normalize(currentLocale.get());
        String exact = target.toLanguageTag();
        if (locales.contains(exact)) {
            return exact;
        }
        for (String suffix : localePolicy.resourceSuffixChain(target)) {
            for (Locale supported : localePolicy.supportedLocales()) {
                List<String> supportedChain = localePolicy.resourceSuffixChain(supported);
                if (!supportedChain.isEmpty() && suffix.equals(supportedChain.get(0))) {
                    String tag = supported.toLanguageTag();
                    if (locales.contains(tag)) {
                        return tag;
                    }
                }
            }
        }
        throw rejected("locale-fallback");
    }

    private static Set<String> requiredLocales(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > 16) {
            throw rejected("required-locales");
        }
        Set<String> locales = new LinkedHashSet<>();
        for (JsonNode value : node) {
            String tag = requiredText(value, 35, "locale");
            if (!LOCALE_TAG.matcher(tag).matches()
                    || !Locale.forLanguageTag(tag).toLanguageTag().equals(tag)
                    || !locales.add(tag)) {
                throw rejected("required-locales");
            }
        }
        return locales;
    }

    private static NotificationSeverity severity(String value) {
        return switch (value) {
            case "info" -> NotificationSeverity.INFO;
            case "warning" -> NotificationSeverity.WARNING;
            case "critical" -> NotificationSeverity.ERROR;
            default -> throw rejected("severity");
        };
    }

    private static long publishedAt(String value) {
        if (!value.endsWith("Z")) {
            throw rejected("published-at");
        }
        try {
            long epochMillis = Instant.parse(value).toEpochMilli();
            if (epochMillis < 0) {
                throw rejected("published-at");
            }
            return epochMillis;
        } catch (DateTimeException | ArithmeticException exception) {
            throw rejected("published-at");
        }
    }

    private static String requiredText(JsonNode node, int maximumCodePoints, String code) {
        if (node == null || !node.isTextual()) {
            throw rejected(code);
        }
        String value = node.textValue();
        if (value.isBlank() || !value.strip().equals(value)
                || value.codePointCount(0, value.length()) > maximumCodePoints) {
            throw rejected(code);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++index))) {
                    throw rejected(code);
                }
            } else if (Character.isLowSurrogate(character) || isForbiddenControl(character)) {
                throw rejected(code);
            }
        }
        return value;
    }

    private static boolean isForbiddenControl(char character) {
        return character <= '\u0008'
                || character == '\u000B'
                || character == '\u000C'
                || (character >= '\u000E' && character <= '\u001F')
                || character == '\u007F';
    }

    private static void requireFields(JsonNode node, Set<String> expected, String code) {
        if (node == null || !node.isObject() || !fieldNames(node).equals(expected)) {
            throw rejected(code);
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static void requireJsonContentType(List<String> values) {
        if (values == null || values.size() != 1) {
            throw rejected("content-type");
        }
        String mediaType = values.get(0).split(";", 2)[0].trim();
        if (!"application/json".equalsIgnoreCase(mediaType)) {
            throw rejected("content-type");
        }
    }

    private static void rejectOversizeContentLength(List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            try {
                long length = Long.parseLong(value.trim());
                if (length < 0 || length > MAX_INDEX_BYTES) {
                    throw rejected("response-size");
                }
            } catch (NumberFormatException exception) {
                throw rejected("content-length");
            }
        }
    }

    private static RejectedIndex rejected(String code) {
        return new RejectedIndex(code);
    }

    private record Translation(String title, String summary, String contentUrl) {
    }

    private record Announcement(
            String id,
            long publishedAt,
            NotificationSeverity severity,
            Translation translation) {
    }

    private static final class RejectedIndex extends RuntimeException {
        private final String code;

        private RejectedIndex(String code) {
            super(code);
            this.code = code;
        }
    }
}
