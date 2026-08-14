package top.sywyar.pixivdownload.notificationbase;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import top.sywyar.pixivdownload.notification.NotificationSeverity;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpStreamResponse;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpTransportException;
import top.sywyar.pixivdownload.plugin.signature.ManifestVerificationRequest;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.VerificationPolicy;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;
import java.util.regex.Pattern;

/** 从固定 GitHub Pages 索引拉取并幂等保存管理员公告。 */
public final class RemoteAnnouncementImporter {

    static final URI INDEX_URI = URI.create(
            "https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/index.json");
    static final URI SIGNATURE_URI = URI.create(INDEX_URI + ".sig");
    static final long POLL_DELAY_MILLIS = 6L * 60 * 60 * 1_000;
    static final long POLL_TICK_MILLIS = 60_000;
    static final long INITIAL_JITTER_MILLIS = 30L * 60 * 1_000;
    static final int POLL_JITTER_PERCENT = 15;
    static final long[] RETRY_DELAYS_MILLIS = {
            5L * 60 * 1_000,
            15L * 60 * 1_000,
            60L * 60 * 1_000,
            POLL_DELAY_MILLIS
    };
    static final long MAX_RETRY_AFTER_MILLIS = 24L * 60 * 60 * 1_000;
    static final int MAX_INDEX_BYTES = 1_024 * 1_024;
    static final int MAX_SIGNATURE_BYTES = 16 * 1_024;
    static final int MAX_ANNOUNCEMENTS = 100;
    static final long MAX_PUBLISHED_AT_FUTURE_MILLIS = 10L * 60 * 1_000;
    static final long MAX_MANIFEST_VALIDITY_MILLIS = 31L * 24 * 60 * 60 * 1_000;
    private static final long MIN_PUBLISHED_AT_MILLIS = 1_577_836_800_000L; // 2020-01-01T00:00:00Z
    static final String REPOSITORY_ID = "pixivdownloader-remote-announcements";

    private static final Logger LOG = LoggerFactory.getLogger(RemoteAnnouncementImporter.class);
    private static final String PUBLIC_ANNOUNCEMENT_BASE =
            "https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/";
    private static final Pattern LOCALE_TAG = Pattern.compile(
            "[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ETAG = Pattern.compile("(?:W/)?\"[!#-~]*\"");
    private static final Set<String> ROOT_FIELDS =
            Set.of("schemaVersion", "sequence", "generatedAt", "expiresAt",
                    "requiredLocales", "announcements");
    private static final Set<String> ANNOUNCEMENT_FIELDS =
            Set.of("id", "publishedAt", "severity", "locales");
    private static final Set<String> TRANSLATION_FIELDS =
            Set.of("title", "summary", "contentUrl", "contentSha256");
    private static final Set<String> SIGNATURE_FIELDS =
            Set.of("formatVersion", "algorithm", "keyId", "value");

    private final OutboundHttpClient client;
    private final ObjectMapper objectMapper;
    private final NotificationInboxService inbox;
    private final PluginSupplyChainVerifier verifier;
    private final Clock clock;
    private final LongUnaryOperator randomLong;

    private long nextPollTime;
    private int consecutiveFailures;

    RemoteAnnouncementImporter(OutboundHttpClient client,
                               ObjectMapper objectMapper,
                               NotificationInboxService inbox) {
        this(client, objectMapper, inbox, new PluginSupplyChainVerifier(), Clock.systemUTC(),
                bound -> ThreadLocalRandom.current().nextLong(bound));
    }

    RemoteAnnouncementImporter(OutboundHttpClient client,
                               ObjectMapper objectMapper,
                               NotificationInboxService inbox,
                               PluginSupplyChainVerifier verifier,
                               Clock clock) {
        this(client, objectMapper, inbox, verifier, clock,
                bound -> ThreadLocalRandom.current().nextLong(bound));
    }

    RemoteAnnouncementImporter(OutboundHttpClient client,
                               ObjectMapper objectMapper,
                               NotificationInboxService inbox,
                               PluginSupplyChainVerifier verifier,
                               Clock clock,
                               LongUnaryOperator randomLong) {
        this.client = Objects.requireNonNull(client, "remote announcement HTTP client");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.inbox = Objects.requireNonNull(inbox, "notification inbox");
        this.verifier = Objects.requireNonNull(verifier, "remote announcement verifier");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.randomLong = Objects.requireNonNull(randomLong, "random delay source");
        nextPollTime = addDelay(clock.millis(), randomDelay(INITIAL_JITTER_MILLIS + 1));
    }

    @Scheduled(
            initialDelay = 0,
            fixedDelay = POLL_TICK_MILLIS,
            scheduler = "notificationAnnouncementTaskScheduler")
    public void tick() {
        if (claimDuePoll()) {
            poll();
        }
    }

    void poll() {
        try {
            RemoteAnnouncementValidators validators =
                    inbox.remoteAnnouncementValidators(clock.millis());
            FetchedIndex fetched = fetchIndex(validators);
            if (fetched.notModified()) {
                scheduleSuccess();
                return;
            }
            ImportOutcome outcome = importIndexOutcome(fetched.bytes(), fetchSignature());
            if (outcome.complete()) {
                inbox.saveRemoteAnnouncementValidators(
                        outcome.manifestSha256(), fetched.etag(), fetched.lastModified());
            }
            if (outcome.imported() > 0) {
                LOG.info("Imported {} remote announcement(s)", outcome.imported());
            }
            if (outcome.transientFailure()) {
                scheduleFailure(outcome.retryAfterMillis());
            } else {
                scheduleSuccess();
            }
        } catch (RejectedIndex exception) {
            LOG.warn("Remote announcement index rejected [{}]", exception.code);
            if (exception.transientFailure) {
                scheduleFailure(exception.retryAfterMillis);
            } else {
                scheduleSuccess();
            }
        } catch (RuntimeException exception) {
            LOG.warn("Remote announcement poll failed [{}]", exception.getClass().getSimpleName());
            scheduleFailure(-1);
        }
    }

    int importIndex(byte[] bytes, byte[] signatureBytes) {
        return importIndexOutcome(bytes, signatureBytes).imported();
    }

    private ImportOutcome importIndexOutcome(byte[] bytes, byte[] signatureBytes) {
        VerificationResult verification = verifier.verifyManifest(new ManifestVerificationRequest(
                bytes, REPOSITORY_ID, signature(signatureBytes), VerificationPolicy.officialRepository()));
        if (!verification.accepted()) {
            throw rejected("signature-" + verification.diagnosticCode()
                    .toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        JsonNode root = parse(bytes);
        requireFields(root, ROOT_FIELDS, "root-fields");
        JsonNode schemaVersion = root.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.isIntegralNumber() || schemaVersion.intValue() != 1) {
            throw rejected("schema-version");
        }
        long sequence = positiveLong(root.get("sequence"), "sequence");
        long now = clock.millis();
        long generatedAt = timestamp(root.get("generatedAt"), "generated-at");
        long expiresAt = timestamp(root.get("expiresAt"), "expires-at");
        if (generatedAt > maximumNow(now)
                || expiresAt <= now
                || expiresAt <= generatedAt
                || validityTooLong(generatedAt, expiresAt)) {
            throw rejected("manifest-time");
        }
        Set<String> requiredLocales = requiredLocales(root.get("requiredLocales"));
        JsonNode announcements = root.get("announcements");
        if (announcements == null || !announcements.isArray()
                || announcements.size() > MAX_ANNOUNCEMENTS) {
            throw rejected("announcement-limit");
        }
        if (!inbox.acceptRemoteAnnouncementIndex(
                sequence, verification.sha256(), generatedAt, expiresAt)) {
            throw rejected("sequence-rollback");
        }

        int imported = 0;
        boolean complete = true;
        boolean transientFailure = false;
        long retryAfterMillis = -1;
        Set<String> seenIds = new HashSet<>();
        for (int position = 0; position < announcements.size(); position++) {
            try {
                Announcement announcement = announcement(
                        announcements.get(position), requiredLocales, seenIds);
                List<RemoteAnnouncementTranslation> metadata = announcement.translations().stream()
                        .map(Translation::metadata)
                        .toList();
                if (!inbox.needsRemoteAnnouncementImport(
                        announcement.id(), metadata)) {
                    continue;
                }
                List<RemoteAnnouncementTranslation> snapshots = announcement.translations().stream()
                        .map(translation -> translation.withHtml(
                                fetchHtml(translation.contentUrl(), translation.contentSha256()).html()))
                        .toList();
                if (inbox.storeRemoteAnnouncement(
                        announcement.id(), announcement.severity(),
                        snapshots, announcement.publishedAt())) {
                    imported++;
                }
            } catch (RejectedIndex | IllegalArgumentException exception) {
                String code = exception instanceof RejectedIndex rejected ? rejected.code : "invalid-field";
                LOG.warn("Remote announcement item {} rejected [{}]", position, code);
                complete = false;
                if (exception instanceof RejectedIndex rejected && rejected.transientFailure) {
                    transientFailure = true;
                    retryAfterMillis = Math.max(retryAfterMillis, rejected.retryAfterMillis);
                }
            }
        }
        return new ImportOutcome(
                imported, complete, transientFailure, retryAfterMillis, verification.sha256());
    }

    private FetchedIndex fetchIndex(RemoteAnnouncementValidators validators) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Accept", List.of("application/json"));
        if (validators != null) {
            if (validators.etag() != null) {
                headers.put("If-None-Match", List.of(validators.etag()));
            }
            if (validators.lastModified() != null) {
                headers.put("If-Modified-Since", List.of(validators.lastModified()));
            }
        }
        OutboundHttpRequest request = new OutboundHttpRequest(
                INDEX_URI,
                "GET",
                headers,
                new byte[0]);
        try (OutboundHttpStreamResponse response = client.exchangeStream(request)) {
            if (response.statusCode() == 304 && validators != null && headers.size() > 1) {
                return FetchedIndex.notModifiedResponse();
            }
            if (response.statusCode() != 200) {
                throw rejectedHttpStatus("http-status", response);
            }
            requireJsonContentType(response.headers().get("Content-Type"));
            rejectOversizeContentLength(response.headers().get("Content-Length"), MAX_INDEX_BYTES);
            byte[] bytes = response.body().readNBytes(MAX_INDEX_BYTES + 1);
            if (bytes.length > MAX_INDEX_BYTES) {
                throw rejected("response-size");
            }
            return new FetchedIndex(
                    bytes,
                    cacheEtag(response.headers().get("ETag")),
                    cacheLastModified(response.headers().get("Last-Modified")),
                    false);
        } catch (IOException exception) {
            throw new OutboundHttpTransportException(
                    "Failed to read remote announcement index", exception);
        }
    }

    private byte[] fetchSignature() {
        OutboundHttpRequest request = new OutboundHttpRequest(
                SIGNATURE_URI,
                "GET",
                Map.of("Accept", List.of("application/json")),
                new byte[0]);
        try (OutboundHttpStreamResponse response = client.exchangeStream(request)) {
            if (response.statusCode() != 200) {
                throw rejectedHttpStatus("signature-http-status", response);
            }
            rejectOversizeContentLength(response.headers().get("Content-Length"), MAX_SIGNATURE_BYTES);
            byte[] bytes = response.body().readNBytes(MAX_SIGNATURE_BYTES + 1);
            if (bytes.length > MAX_SIGNATURE_BYTES) {
                throw rejected("signature-response-size");
            }
            return bytes;
        } catch (IOException exception) {
            throw new OutboundHttpTransportException(
                    "Failed to read remote announcement signature", exception);
        }
    }

    private NotificationHtmlContent fetchHtml(String contentUrl, String expectedSha256) {
        OutboundHttpRequest request = new OutboundHttpRequest(
                URI.create(contentUrl),
                "GET",
                Map.of("Accept", List.of("text/html")),
                new byte[0]);
        try (OutboundHttpStreamResponse response = client.exchangeStream(request)) {
            if (response.statusCode() != 200) {
                throw rejectedHttpStatus("content-http-status", response);
            }
            requireHtmlContentType(response.headers().get("Content-Type"));
            rejectOversizeContentLength(
                    response.headers().get("Content-Length"), NotificationHtmlContent.MAX_HTML_BYTES);
            byte[] bytes = response.body().readNBytes(NotificationHtmlContent.MAX_HTML_BYTES + 1);
            if (bytes.length > NotificationHtmlContent.MAX_HTML_BYTES) {
                throw rejected("content-response-size");
            }
            if (!sha256Hex(bytes).equals(expectedSha256)) {
                throw rejected("content-hash");
            }
            return new NotificationHtmlContent(contentUrl, decodeUtf8(bytes, "invalid-html"));
        } catch (IOException | OutboundHttpTransportException exception) {
            throw transientRejected("content-transport", -1);
        }
    }

    private synchronized boolean claimDuePoll() {
        if (clock.millis() < nextPollTime) {
            return false;
        }
        nextPollTime = Long.MAX_VALUE;
        return true;
    }

    private synchronized void scheduleSuccess() {
        consecutiveFailures = 0;
        long spread = POLL_DELAY_MILLIS * POLL_JITTER_PERCENT / 100;
        nextPollTime = addDelay(
                clock.millis(), POLL_DELAY_MILLIS - spread + randomDelay(spread * 2 + 1));
    }

    private synchronized void scheduleFailure(long retryAfterMillis) {
        long delay = RETRY_DELAYS_MILLIS[
                Math.min(consecutiveFailures, RETRY_DELAYS_MILLIS.length - 1)];
        consecutiveFailures++;
        nextPollTime = addDelay(clock.millis(), Math.max(delay, retryAfterMillis));
    }

    private long randomDelay(long bound) {
        long value = randomLong.applyAsLong(bound);
        if (value < 0 || value >= bound) {
            throw new IllegalStateException("Random delay source returned an out-of-range value");
        }
        return value;
    }

    private static long addDelay(long now, long delay) {
        try {
            return Math.addExact(now, delay);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private RejectedIndex rejectedHttpStatus(
            String code, OutboundHttpStreamResponse response) {
        int status = response.statusCode();
        if (status == 429) {
            return transientRejected(
                    code, retryAfterMillis(response.headers().get("Retry-After"), clock.millis()));
        }
        return status >= 500 && status <= 599
                ? transientRejected(code, -1)
                : rejected(code);
    }

    private static long retryAfterMillis(List<String> values, long now) {
        if (values == null || values.size() != 1) {
            return -1;
        }
        String value = values.get(0).trim();
        try {
            long seconds = Long.parseLong(value);
            if (seconds < 0) {
                return -1;
            }
            return seconds >= MAX_RETRY_AFTER_MILLIS / 1_000
                    ? MAX_RETRY_AFTER_MILLIS
                    : seconds * 1_000;
        } catch (NumberFormatException ignored) {
            try {
                long delay = Math.subtractExact(
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toInstant().toEpochMilli(),
                        now);
                return Math.min(Math.max(0, delay), MAX_RETRY_AFTER_MILLIS);
            } catch (ArithmeticException | DateTimeParseException exception) {
                return -1;
            }
        }
    }

    private static String cacheEtag(List<String> values) {
        if (values == null || values.size() != 1) {
            return null;
        }
        String value = values.get(0);
        return value.length() <= 256 && ETAG.matcher(value).matches() ? value : null;
    }

    private static String cacheLastModified(List<String> values) {
        if (values == null || values.size() != 1) {
            return null;
        }
        String value = values.get(0);
        if (value.length() > 128) {
            return null;
        }
        try {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            return value;
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private SignatureMetadata signature(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_SIGNATURE_BYTES) {
            throw rejected("signature-response-size");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(decodeUtf8(bytes, "invalid-signature"));
        } catch (JsonProcessingException exception) {
            throw rejected("invalid-signature");
        }
        requireFields(node, SIGNATURE_FIELDS, "signature-fields");
        JsonNode formatVersion = node.get("formatVersion");
        if (formatVersion == null || !formatVersion.isIntegralNumber()
                || !formatVersion.canConvertToInt()) {
            throw rejected("signature-format");
        }
        return new SignatureMetadata(
                formatVersion.intValue(),
                requiredText(node.get("algorithm"), 16, "signature-algorithm"),
                requiredText(node.get("keyId"), 80, "signature-key"),
                requiredText(node.get("value"), 256, "signature-value"));
    }

    private JsonNode parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_INDEX_BYTES) {
            throw rejected("response-size");
        }
        try {
            String json = decodeUtf8(bytes, "invalid-json");
            JsonNode root = objectMapper.readTree(json);
            if (root == null) {
                throw rejected("invalid-json");
            }
            return root;
        } catch (JsonProcessingException exception) {
            throw rejected("invalid-json");
        }
    }

    private static String decodeUtf8(byte[] bytes, String code) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw rejected(code);
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

        List<Translation> translations = new ArrayList<>();
        for (String locale : requiredLocales) {
            JsonNode translation = locales.get(locale);
            requireFields(translation, TRANSLATION_FIELDS, "translation-fields");
            String title = requiredText(translation.get("title"), 160, "title");
            String summary = requiredText(translation.get("summary"), 500, "summary");
            String contentUrl = requiredText(translation.get("contentUrl"), 2_048, "content-url");
            String contentSha256 = requiredText(
                    translation.get("contentSha256"), 64, "content-sha256");
            if (!SHA256.matcher(contentSha256).matches()) {
                throw rejected("content-sha256");
            }
            String expectedUrl = PUBLIC_ANNOUNCEMENT_BASE + id + "/" + locale + ".html";
            if (!NotificationInboxService.safeContentUrl(contentUrl).equals(expectedUrl)) {
                throw rejected("content-url");
            }
            translations.add(new Translation(
                    locale, title, summary, contentUrl, contentSha256));
        }
        return new Announcement(id, publishedAt, severity, List.copyOf(translations));
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

    private long publishedAt(String value) {
        long epochMillis = timestamp(value, "published-at");
        if (epochMillis < MIN_PUBLISHED_AT_MILLIS || epochMillis > maximumNow(clock.millis())) {
            throw rejected("published-at");
        }
        return epochMillis;
    }

    private static long timestamp(JsonNode node, String code) {
        return timestamp(requiredText(node, 32, code), code);
    }

    private static long timestamp(String value, String code) {
        if (!value.endsWith("Z")) {
            throw rejected(code);
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeException | ArithmeticException exception) {
            throw rejected(code);
        }
    }

    private static long positiveLong(JsonNode node, String code) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()
                || node.longValue() <= 0) {
            throw rejected(code);
        }
        return node.longValue();
    }

    private static long maximumNow(long now) {
        try {
            return Math.addExact(now, MAX_PUBLISHED_AT_FUTURE_MILLIS);
        } catch (ArithmeticException exception) {
            throw rejected("clock");
        }
    }

    private static boolean validityTooLong(long generatedAt, long expiresAt) {
        try {
            return Math.subtractExact(expiresAt, generatedAt) > MAX_MANIFEST_VALIDITY_MILLIS;
        } catch (ArithmeticException exception) {
            return true;
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
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

    private static void requireHtmlContentType(List<String> values) {
        if (values == null || values.size() != 1) {
            throw rejected("content-type");
        }
        String mediaType = values.get(0).split(";", 2)[0].trim();
        if (!"text/html".equalsIgnoreCase(mediaType)) {
            throw rejected("content-type");
        }
    }

    private static void rejectOversizeContentLength(List<String> values, int maximumBytes) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            try {
                long length = Long.parseLong(value.trim());
                if (length < 0 || length > maximumBytes) {
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

    private static RejectedIndex transientRejected(String code, long retryAfterMillis) {
        return new RejectedIndex(code, true, retryAfterMillis);
    }

    private record FetchedIndex(
            byte[] bytes,
            String etag,
            String lastModified,
            boolean notModified) {

        private static FetchedIndex notModifiedResponse() {
            return new FetchedIndex(new byte[0], null, null, true);
        }
    }

    private record ImportOutcome(
            int imported,
            boolean complete,
            boolean transientFailure,
            long retryAfterMillis,
            String manifestSha256) {
    }

    private record Announcement(
            String id,
            long publishedAt,
            NotificationSeverity severity,
            List<Translation> translations) {
    }

    private record Translation(
            String locale,
            String title,
            String summary,
            String contentUrl,
            String contentSha256) {

        private RemoteAnnouncementTranslation metadata() {
            return new RemoteAnnouncementTranslation(
                    locale, title, summary, contentUrl, contentSha256, null);
        }

        private RemoteAnnouncementTranslation withHtml(String html) {
            return new RemoteAnnouncementTranslation(
                    locale, title, summary, contentUrl, contentSha256, html);
        }
    }

    private static final class RejectedIndex extends RuntimeException {
        private final String code;
        private final boolean transientFailure;
        private final long retryAfterMillis;

        private RejectedIndex(String code) {
            this(code, false, -1);
        }

        private RejectedIndex(String code, boolean transientFailure, long retryAfterMillis) {
            super(code);
            this.code = code;
            this.transientFailure = transientFailure;
            this.retryAfterMillis = retryAfterMillis;
        }
    }
}
