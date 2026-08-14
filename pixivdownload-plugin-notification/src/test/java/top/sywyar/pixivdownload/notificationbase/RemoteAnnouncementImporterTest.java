package top.sywyar.pixivdownload.notificationbase;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import top.sywyar.pixivdownload.notification.NotificationSeverity;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientFactory;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientProfile;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpCookiePolicy;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRedirectPolicy;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoute;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpStreamResponse;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpTransportException;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("远程公告导入")
class RemoteAnnouncementImporterTest {

    private static final String PUBLISHED = "2026-08-12T00:00:00Z";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);
    private static final String GENERATED = "2026-08-13T00:00:00Z";
    private static final String EXPIRES = "2026-09-12T00:00:00Z";
    @Test
    @DisplayName("按发布时间进入公告分类并映射严重程度")
    void importsInPublishedOrderAndMapsFields() {
        Harness harness = harness(Locale.US);
        String older = item("older", "2026-08-10T00:00:00Z", "warning");
        String newer = item("newer", "2026-08-12T00:00:00Z", "critical");
        harness.client.respond(200, jsonHeaders(), index(older, newer));

        harness.importer.poll();

        assertThat(harness.inbox.latest(NotificationCategory.ANNOUNCEMENT, false, 10))
                .extracting(NotificationMessage::id)
                .containsExactly("remote-announcement:newer", "remote-announcement:older");
        assertThat(harness.inbox.find("remote-announcement:newer")).satisfies(message -> {
            assertThat(message.category()).isEqualTo("announcement");
            assertThat(message.severity()).isEqualTo("ERROR");
            assertThat(message.title()).isEqualTo("English newer");
            assertThat(message.body()).isEqualTo("English summary newer");
            assertThat(message.hasHtmlContent()).isTrue();
            assertThat(message.actionUrl()).isNull();
            assertThat(message.readTime()).isNull();
            assertThat(message.createdTime()).isEqualTo(Instant.parse("2026-08-12T00:00:00Z").toEpochMilli());
        });
        assertThat(harness.inbox.find("remote-announcement:older").severity()).isEqualTo("WARNING");
        assertThat(harness.inbox.htmlContent("remote-announcement:newer")).satisfies(content -> {
            assertThat(content.sourceUrl()).endsWith("/newer/en-US.html");
            assertThat(content.html()).isEqualTo(htmlBody());
        });
        assertThat(harness.client.lastIndexRequest).satisfies(request -> {
            assertThat(request.uri()).isEqualTo(RemoteAnnouncementImporter.INDEX_URI);
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.headers()).containsEntry("Accept", List.of("application/json"));
        });
        assertThat(harness.client.lastSignatureRequest.uri())
                .isEqualTo(RemoteAnnouncementImporter.SIGNATURE_URI);
        assertThat(harness.client.lastContentRequest).satisfies(request -> {
            assertThat(request.uri().toString()).endsWith("/newer/en-US.html");
            assertThat(request.headers()).containsEntry("Accept", List.of("text/html"));
        });
    }

    @Test
    @DisplayName("切换语言复用同一公告并保留已读与删除状态")
    void localeSwitchKeepsLogicalHistoryAndReadState() {
        AtomicReference<Locale> locale = new AtomicReference<>(Locale.SIMPLIFIED_CHINESE);
        Harness harness = harness(locale);
        byte[] index = bytes(index(item("stable", PUBLISHED, "info")));

        assertThat(harness.importIndex(index)).isEqualTo(1);
        NotificationMessage first = harness.inbox.find("remote-announcement:stable");
        NotificationHtmlContent firstContent = harness.inbox.htmlContent(first.id());
        NotificationMessage read = harness.inbox.markRead(first.id());
        locale.set(Locale.US);
        harness.client.contentPlan = new ResponsePlan(
                200, htmlHeaders(), bytes("<!doctype html><p>Changed</p>"), null);

        assertThat(harness.importIndex(index)).isZero();
        assertThat(harness.inbox.find(first.id())).satisfies(message -> {
            assertThat(message.title()).isEqualTo("English stable");
            assertThat(message.body()).isEqualTo("English summary stable");
            assertThat(message.contentUrl()).endsWith("/stable/en-US.html");
            assertThat(message.readTime()).isEqualTo(read.readTime());
        });
        assertThat(harness.inbox.htmlContent(first.id())).satisfies(content -> {
            assertThat(content.sourceUrl()).endsWith("/stable/en-US.html");
            assertThat(content.html()).isEqualTo(firstContent.html());
        });
        assertThat(harness.client.contentRequests).hasValue(2);
        assertThat(harness.inbox.unreadCount()).isZero();

        locale.set(Locale.JAPAN);
        harness.client.contentPlan = new ResponsePlan(
                200, htmlHeaders(), bytes(htmlBody()), null);
        String expanded = indexWithSequenceAndLocales(
                2,
                "[\"zh-CN\",\"en-US\",\"ja-JP\"]",
                itemWithTranslations("stable", PUBLISHED, "info", """
                        "zh-CN": {
                          "title": "中文 stable",
                          "summary": "中文摘要 stable",
                          "contentUrl": "%sstable/zh-CN.html",
                          "contentSha256": "%s"
                        },
                        "en-US": {
                          "title": "English stable",
                          "summary": "English summary stable",
                          "contentUrl": "%sstable/en-US.html",
                          "contentSha256": "%s"
                        },
                        "ja-JP": {
                          "title": "日本語 stable",
                          "summary": "日本語要約 stable",
                          "contentUrl": "%sstable/ja-JP.html",
                          "contentSha256": "%s"
                        }
                        """.formatted(
                        contentBase(), htmlSha256(), contentBase(), htmlSha256(),
                        contentBase(), htmlSha256())));
        assertThat(harness.importIndex(bytes(expanded))).isEqualTo(1);
        assertThat(harness.inbox.latest(NotificationCategory.ANNOUNCEMENT, false, 10))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.id()).isEqualTo(first.id());
                    assertThat(message.title()).isEqualTo("日本語 stable");
                    assertThat(message.readTime()).isEqualTo(read.readTime());
                });
        assertThat(harness.inbox.htmlContent(first.id()).sourceUrl()).endsWith("/stable/ja-JP.html");
        assertThat(harness.client.contentRequests).hasValue(5);

        assertThat(harness.inbox.delete(first.id())).isTrue();
        assertThat(harness.importIndex(bytes(expanded))).isZero();
        assertThat(harness.inbox.find(first.id())).isNull();
        assertThat(harness.client.contentRequests).hasValue(5);
    }

    @Test
    @DisplayName("旧记录缺少本地正文时补拉 HTML 并保留历史状态")
    void backfillsHtmlForExistingAnnouncementWithoutLocalContent() {
        Harness harness = harness(Locale.SIMPLIFIED_CHINESE);
        long publishedAt = Instant.parse(PUBLISHED).toEpochMilli();
        harness.mapper.insert(new NotificationMessage(
                "remote-announcement:legacy", "announcement", "INFO", null,
                "既有标题", "既有摘要", contentBase() + "legacy/zh-CN.html", null, null,
                publishedAt, publishedAt + 1));

        assertThat(harness.importIndex(bytes(index(item("legacy", PUBLISHED, "info")))))
                .isEqualTo(1);
        assertThat(harness.inbox.find("remote-announcement:legacy")).satisfies(message -> {
            assertThat(message.title()).isEqualTo("中文 legacy");
            assertThat(message.body()).isEqualTo("中文摘要 legacy");
            assertThat(message.readTime()).isEqualTo(publishedAt + 1);
            assertThat(message.hasHtmlContent()).isTrue();
        });
        assertThat(harness.inbox.htmlContent("remote-announcement:legacy").html()).isEqualTo(htmlBody());
        assertThat(harness.client.contentRequests).hasValue(2);
    }

    @Test
    @DisplayName("没有精确语言时先回退英文再回退中文")
    void localeFallbackPrefersEnglishThenChinese() {
        Harness englishFallback = harness(Locale.JAPAN);
        assertThat(englishFallback.importIndex(bytes(index(item("fallback", PUBLISHED, "info")))))
                .isEqualTo(1);
        assertThat(englishFallback.inbox.find("remote-announcement:fallback").title())
                .isEqualTo("English fallback");

        Harness chineseFallback = harness(Locale.JAPAN);
        String chineseOnly = indexWithLocales(
                "[\"zh-CN\"]",
                itemWithTranslations("zh-only", PUBLISHED, "info", """
                        "zh-CN": {
                          "title": "中文 zh-only",
                          "summary": "中文摘要 zh-only",
                          "contentUrl": "%szh-only/zh-CN.html",
                          "contentSha256": "%s"
                        }
                        """.formatted(contentBase(), htmlSha256())));
        assertThat(chineseFallback.importIndex(bytes(chineseOnly))).isEqualTo(1);
        assertThat(chineseFallback.inbox.find("remote-announcement:zh-only").title())
                .isEqualTo("中文 zh-only");
    }

    @Test
    @DisplayName("非法 schema、数量、字段、ID、级别、时间与语言均不影响既有消息")
    void rejectsInvalidSchemaAndBoundedFieldsWithoutHarmingExistingMessages() {
        String tooMany = IntStream.range(0, RemoteAnnouncementImporter.MAX_ANNOUNCEMENTS + 1)
                .mapToObj(index -> item("notice-" + index, PUBLISHED, "info"))
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
        List<String> invalidIndexes = List.of(
                index(item("valid", PUBLISHED, "info")).replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
                index(item("valid", PUBLISHED, "info")).replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"extra\": true"),
                index(tooMany),
                index(item("INVALID", PUBLISHED, "info")),
                index(item("bad-severity", PUBLISHED, "error")),
                index(item("bad-date", "2026-08-12T00:00:00+00:00", "info")),
                index(item("too-old", "2019-12-31T23:59:59Z", "info")),
                index(item("too-far-future", "2099-01-01T00:00:00Z", "info")),
                index(item("long-title", PUBLISHED, "info"))
                        .replace("English long-title", "x".repeat(161)),
                index(item("long-summary", PUBLISHED, "info"))
                        .replace("English summary long-summary", "x".repeat(501)),
                indexWithLocales("[\"en_us\"]", "{}"),
                """
                        {"schemaVersion":1,"schemaVersion":1,"requiredLocales":["zh-CN"],"announcements":[]}
                        """,
                index(item("valid", PUBLISHED, "info")) + "{}",
                index(item("bad-unicode", PUBLISHED, "info"))
                        .replace("English bad-unicode", "\\uD800"));

        for (String invalid : invalidIndexes) {
            assertRejectedIndexPreservesExisting(invalid);
        }
    }

    @Test
    @DisplayName("签名篡改、未知密钥、过期清单与序列回退均保留可信公告")
    void rejectsUntrustedExpiredAndRollbackIndexes() {
        Harness harness = harness(Locale.US);
        byte[] trusted = bytes(indexWithSequenceAndLocales(
                2, "[\"zh-CN\",\"en-US\"]", item("trusted", PUBLISHED, "info")));
        assertThat(harness.importIndex(trusted)).isEqualTo(1);
        NotificationMessage existing = harness.inbox.find("remote-announcement:trusted");

        byte[] lower = bytes(index(item("lower", PUBLISHED, "info")));
        byte[] conflicting = bytes(indexWithSequenceAndLocales(
                2, "[\"zh-CN\",\"en-US\"]", item("conflicting", PUBLISHED, "info")));
        byte[] expired = bytes(indexWithMetadata(
                3, "2026-07-01T00:00:00Z", "2026-08-13T00:00:00Z",
                "[\"zh-CN\",\"en-US\"]", item("expired", PUBLISHED, "info")));
        byte[] overflowingValidity = bytes(indexWithMetadata(
                3, "-292000000-01-01T00:00:00Z", "+292000000-01-01T00:00:00Z",
                "[\"zh-CN\",\"en-US\"]", item("overflow", PUBLISHED, "info")));
        byte[] next = bytes(indexWithSequenceAndLocales(
                3, "[\"zh-CN\",\"en-US\"]", item("next", PUBLISHED, "info")));
        byte[] tampered = bytes(new String(next, StandardCharsets.UTF_8)
                .replace("English next", "English tampered"));
        SigningFixture unknown = SigningFixture.create();

        assertThatThrownBy(() -> harness.importIndex(lower)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> harness.importIndex(conflicting)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> harness.importIndex(expired)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> harness.importIndex(overflowingValidity))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> harness.importer.importIndex(
                tampered, harness.signing.signatureBytes(next))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> harness.importer.importIndex(
                next, unknown.signatureBytes(next))).isInstanceOf(RuntimeException.class);

        assertThat(harness.inbox.find(existing.id())).isEqualTo(existing);
        assertThat(harness.inbox.find("remote-announcement:lower")).isNull();
        assertThat(harness.inbox.find("remote-announcement:conflicting")).isNull();
        assertThat(harness.inbox.find("remote-announcement:expired")).isNull();
        assertThat(harness.inbox.find("remote-announcement:overflow")).isNull();
        assertThat(harness.inbox.find("remote-announcement:next")).isNull();
    }

    @Test
    @DisplayName("正文摘要不匹配时拒绝快照并保留其它消息")
    void rejectsContentHashMismatch() {
        Harness harness = harness(Locale.US);
        String index = index(item("bad-hash", PUBLISHED, "info"))
                .replace(htmlSha256(), "f".repeat(64));

        assertThat(harness.importIndex(bytes(index))).isZero();
        assertThat(harness.inbox.find("remote-announcement:bad-hash")).isNull();
    }

    @Test
    @DisplayName("凭据、端口、查询、片段与路径穿越正文地址均被拒绝")
    void rejectsUnsafeContentUrls() {
        String validUrl = contentBase() + "unsafe/en-US.html";
        for (String invalidUrl : List.of(
                "https://user@sywyar.github.io/PixivDownloader-Remote-Content/announcements/unsafe/en-US.html",
                "https://sywyar.github.io:443/PixivDownloader-Remote-Content/announcements/unsafe/en-US.html",
                validUrl + "?next=1",
                validUrl + "#section",
                contentBase() + "unsafe/../en-US.html")) {
            assertRejectedIndexPreservesExisting(
                    index(item("unsafe", PUBLISHED, "info")).replace(validUrl, invalidUrl));
        }
    }

    @Test
    @DisplayName("超时、非成功、重定向、错误媒体类型与超大响应均保留既有消息")
    void transportAndResponseFailuresPreserveExistingMessages() {
        List<ResponsePlan> failures = new ArrayList<>();
        failures.add(ResponsePlan.failure(new OutboundHttpTransportException("timeout")));
        failures.add(new ResponsePlan(503, jsonHeaders(), bytes(index(item("new", PUBLISHED, "info"))), null));
        failures.add(new ResponsePlan(302, Map.of(
                "Content-Type", List.of("application/json"),
                "Location", List.of("https://example.test/index.json")),
                bytes(index(item("new", PUBLISHED, "info"))), null));
        failures.add(new ResponsePlan(200, Map.of("Content-Type", List.of("text/html")),
                bytes(index(item("new", PUBLISHED, "info"))), null));
        failures.add(new ResponsePlan(200, jsonHeaders(),
                new byte[RemoteAnnouncementImporter.MAX_INDEX_BYTES + 1], null));

        for (ResponsePlan failure : failures) {
            Harness harness = harness(Locale.US);
            NotificationMessage existing = harness.inbox.publish(
                    NotificationCategory.SYSTEM, NotificationSeverity.INFO, null,
                    "Existing", "Existing body", null);
            harness.client.plan = failure;

            harness.importer.poll();

            assertThat(harness.inbox.find(existing.id()).title()).isEqualTo("Existing");
            assertThat(harness.inbox.unreadCount(NotificationCategory.ANNOUNCEMENT)).isZero();
        }
    }

    @Test
    @DisplayName("正文超时、错误媒体类型、非法 UTF-8 与超大响应只拒绝对应公告")
    void rejectsInvalidHtmlSnapshots() {
        List<ResponsePlan> failures = List.of(
                ResponsePlan.failure(new OutboundHttpTransportException("timeout")),
                new ResponsePlan(200, jsonHeaders(), bytes(htmlBody()), null),
                new ResponsePlan(200, htmlHeaders(), new byte[]{(byte) 0xC3, (byte) 0x28}, null),
                new ResponsePlan(200, htmlHeaders(),
                        new byte[NotificationHtmlContent.MAX_HTML_BYTES + 1], null));

        for (ResponsePlan failure : failures) {
            Harness harness = harness(Locale.US);
            harness.client.contentPlan = failure;

            assertThat(harness.importIndex(bytes(index(item("invalid-html", PUBLISHED, "info")))))
                    .isZero();
            assertThat(harness.inbox.find("remote-announcement:invalid-html")).isNull();
        }
    }

    @Test
    @DisplayName("单条坏公告不会阻止同一索引内的合法公告")
    void partialInvalidIndexImportsOnlyValidItems() {
        Harness harness = harness(Locale.US);
        NotificationMessage existing = harness.inbox.publish(
                NotificationCategory.SYSTEM, NotificationSeverity.INFO, null,
                "Existing", "Existing body", null);
        String invalid = item("BAD", PUBLISHED, "info");

        assertThat(harness.importIndex(bytes(index(
                item("good", PUBLISHED, "info"), invalid)))).isEqualTo(1);

        assertThat(harness.inbox.find(existing.id())).isNotNull();
        assertThat(harness.inbox.find("remote-announcement:good")).isNotNull();
        assertThat(harness.inbox.find("remote-announcement:BAD")).isNull();
    }

    @Test
    @DisplayName("插件配置使用固定超时、禁止重定向和 Cookie 的单连接客户端")
    void configurationOwnsStrictHttpProfile() throws Exception {
        AtomicReference<OutboundHttpClientProfile> captured = new AtomicReference<>();
        StubClient client = new StubClient(SigningFixture.create());
        OutboundHttpClientFactory factory = profile -> {
            captured.set(profile);
            return client;
        };

        OutboundHttpClient opened = new NotificationPluginConfiguration()
                .notificationAnnouncementHttpClient(factory);

        assertThat(opened).isSameAs(client);
        assertThat(captured.get()).satisfies(profile -> {
            assertThat(profile.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(profile.readTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(profile.route()).isEqualTo(OutboundHttpRoute.inherit());
            assertThat(profile.redirectPolicy()).isEqualTo(OutboundHttpRedirectPolicy.NEVER);
            assertThat(profile.cookiePolicy()).isEqualTo(OutboundHttpCookiePolicy.DISABLED);
            assertThat(profile.maxConnections()).isEqualTo(1);
            assertThat(profile.maxConnectionsPerRoute()).isEqualTo(1);
        });
        Scheduled scheduled = RemoteAnnouncementImporter.class.getMethod("poll").getAnnotation(Scheduled.class);
        assertThat(scheduled.initialDelay()).isZero();
        assertThat(scheduled.fixedDelay()).isEqualTo(RemoteAnnouncementImporter.POLL_DELAY_MILLIS);
        assertThat(scheduled.scheduler()).isEqualTo("notificationAnnouncementTaskScheduler");
        opened.close();
        assertThat(client.closed).isTrue();
    }

    @Test
    @DisplayName("子上下文关闭时停止公告调度并关闭 HTTP 客户端")
    void childContextCloseStopsPollingAndClosesClient() throws Exception {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(LifecycleConfiguration.class);
        context.refresh();
        StubClient client = context.getBean(StubClient.class);
        ThreadPoolTaskScheduler scheduler = context.getBean(
                "notificationAnnouncementTaskScheduler", ThreadPoolTaskScheduler.class);

        try {
            assertThat(client.requested.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(client.contentRequested.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(client.requests).hasValue(4);
        } finally {
            context.close();
        }

        assertThat(client.closed).isTrue();
        assertThat(scheduler.getScheduledThreadPoolExecutor().isShutdown()).isTrue();
    }

    private static void assertRejectedIndexPreservesExisting(String index) {
        Harness harness = harness(Locale.US);
        NotificationMessage existing = harness.inbox.publish(
                NotificationCategory.SYSTEM, NotificationSeverity.INFO, null,
                "Existing", "Existing body", null);
        harness.client.respond(200, jsonHeaders(), index);

        harness.importer.poll();

        assertThat(harness.inbox.find(existing.id()).title()).isEqualTo("Existing");
        assertThat(harness.inbox.unreadCount(NotificationCategory.ANNOUNCEMENT)).isZero();
    }

    private static Harness harness(Locale locale) {
        return harness(new AtomicReference<>(locale));
    }

    private static Harness harness(AtomicReference<Locale> locale) {
        SigningFixture signing = SigningFixture.create();
        RecordingMapper mapper = new RecordingMapper();
        NotificationInboxService inbox = new NotificationInboxService(
                mapper, () -> 500, () -> 90, List::of,
                (namespace, requested, key) -> java.util.Optional.empty(), ignored -> locale.get());
        StubClient client = new StubClient(signing);
        return new Harness(mapper, inbox, client,
                new RemoteAnnouncementImporter(
                        client, new ObjectMapper(), inbox, signing.verifier(), CLOCK),
                signing);
    }

    private static Map<String, List<String>> jsonHeaders() {
        return Map.of("Content-Type", List.of("application/json; charset=utf-8"));
    }

    private static Map<String, List<String>> htmlHeaders() {
        return Map.of("Content-Type", List.of("text/html; charset=utf-8"));
    }

    private static String htmlBody() {
        return "<!doctype html><html><body><p>Stored locally</p></body></html>";
    }

    private static String index(String... announcements) {
        return indexWithSequenceAndLocales(
                1, "[\"zh-CN\",\"en-US\"]", String.join(",", announcements));
    }

    private static String indexWithLocales(String locales, String announcements) {
        return indexWithSequenceAndLocales(1, locales, announcements);
    }

    private static String indexWithSequenceAndLocales(
            long sequence, String locales, String announcements) {
        return indexWithMetadata(sequence, GENERATED, EXPIRES, locales, announcements);
    }

    private static String indexWithMetadata(
            long sequence, String generatedAt, String expiresAt,
            String locales, String announcements) {
        return """
                {
                  "schemaVersion": 1,
                  "sequence": %d,
                  "generatedAt": "%s",
                  "expiresAt": "%s",
                  "requiredLocales": %s,
                  "announcements": [%s]
                }
                """.formatted(sequence, generatedAt, expiresAt, locales, announcements);
    }

    private static String item(String id, String publishedAt, String severity) {
        return itemWithTranslations(id, publishedAt, severity, """
                "zh-CN": {
                  "title": "中文 %s",
                  "summary": "中文摘要 %s",
                  "contentUrl": "%s%s/zh-CN.html",
                  "contentSha256": "%s"
                },
                "en-US": {
                  "title": "English %s",
                  "summary": "English summary %s",
                  "contentUrl": "%s%s/en-US.html",
                  "contentSha256": "%s"
                }
                """.formatted(
                id, id, contentBase(), id, htmlSha256(),
                id, id, contentBase(), id, htmlSha256()));
    }

    private static String itemWithTranslations(
            String id, String publishedAt, String severity, String translations) {
        return """
                {
                  "id": "%s",
                  "publishedAt": "%s",
                  "severity": "%s",
                  "locales": {%s}
                }
                """.formatted(id, publishedAt, severity, translations);
    }

    private static String contentBase() {
        return "https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/";
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String htmlSha256() {
        return sha256Hex(bytes(htmlBody()));
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Harness(
            RecordingMapper mapper,
            NotificationInboxService inbox,
            StubClient client,
            RemoteAnnouncementImporter importer,
            SigningFixture signing) {

        private int importIndex(byte[] bytes) {
            return importer.importIndex(bytes, signing.signatureBytes(bytes));
        }
    }

    private record ResponsePlan(
            int status,
            Map<String, List<String>> headers,
            byte[] body,
            RuntimeException failure) {

        private static ResponsePlan failure(RuntimeException failure) {
            return new ResponsePlan(200, jsonHeaders(), new byte[0], failure);
        }
    }

    private record SigningFixture(KeyPair keyPair, PluginSupplyChainVerifier verifier, String keyId) {

        private static SigningFixture create() {
            try {
                KeyPair pair = KeyPairGenerator.getInstance(SignatureMetadata.ED25519).generateKeyPair();
                String keyId = "remote-announcement-test-key";
                TrustedPluginKey key = new TrustedPluginKey(
                        keyId,
                        SignatureMetadata.ED25519,
                        Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                        TrustedPluginKey.State.ACTIVE,
                        "Test Publisher",
                        "Test Root",
                        true);
                return new SigningFixture(
                        pair, new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(key))), keyId);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        private byte[] signatureBytes(byte[] manifest) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(manifest);
                byte[] envelope = EnvelopeV1Codec.manifestMessage(
                        RemoteAnnouncementImporter.REPOSITORY_ID, manifest.length, digest);
                Signature signer = Signature.getInstance(SignatureMetadata.ED25519);
                signer.initSign(keyPair.getPrivate());
                signer.update(envelope);
                String value = Base64.getEncoder().encodeToString(signer.sign());
                return bytes("""
                        {"formatVersion":1,"algorithm":"Ed25519","keyId":"%s","value":"%s"}
                        """.formatted(keyId, value));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private static final class StubClient implements OutboundHttpClient {
        private final CountDownLatch requested = new CountDownLatch(1);
        private final CountDownLatch contentRequested = new CountDownLatch(2);
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger contentRequests = new AtomicInteger();
        private final SigningFixture signing;
        private volatile ResponsePlan plan;
        private volatile ResponsePlan signaturePlan;
        private volatile ResponsePlan contentPlan = new ResponsePlan(
                200, htmlHeaders(), bytes(htmlBody()), null);
        private volatile OutboundHttpRequest lastIndexRequest;
        private volatile OutboundHttpRequest lastSignatureRequest;
        private volatile OutboundHttpRequest lastContentRequest;
        private volatile boolean closed;

        private StubClient(SigningFixture signing) {
            this.signing = signing;
            respond(200, jsonHeaders(), index(item("scheduled", PUBLISHED, "info")));
        }

        private void respond(int status, Map<String, List<String>> headers, String body) {
            plan = new ResponsePlan(status, headers, bytes(body), null);
            signaturePlan = new ResponsePlan(
                    200, jsonHeaders(), signing.signatureBytes(bytes(body)), null);
        }

        @Override
        public OutboundHttpStreamResponse exchangeStream(OutboundHttpRequest request) {
            requests.incrementAndGet();
            requested.countDown();
            boolean indexRequest = RemoteAnnouncementImporter.INDEX_URI.equals(request.uri());
            boolean signatureRequest = RemoteAnnouncementImporter.SIGNATURE_URI.equals(request.uri());
            if (indexRequest) {
                lastIndexRequest = request;
            } else if (signatureRequest) {
                lastSignatureRequest = request;
            } else {
                lastContentRequest = request;
                contentRequests.incrementAndGet();
                contentRequested.countDown();
            }
            ResponsePlan current = indexRequest ? plan : signatureRequest ? signaturePlan : contentPlan;
            if (current.failure() != null) {
                throw current.failure();
            }
            return new OutboundHttpStreamResponse(
                    current.status(), "", current.headers(),
                    new ByteArrayInputStream(current.body()));
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class RecordingMapper implements NotificationInboxMapper {
        private final List<NotificationMessage> messages = new CopyOnWriteArrayList<>();
        private final java.util.Set<String> dismissedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final Map<String, List<RemoteAnnouncementTranslation>> remoteTranslations =
                new ConcurrentHashMap<>();
        private long acceptedSequence;
        private String acceptedDigest;

        @Override
        public int insert(NotificationMessage message) {
            if (dismissedIds.contains(message.id()) || findById(message.id()) != null) {
                return 0;
            }
            messages.add(message);
            return 1;
        }

        @Override
        public List<NotificationMessage> findLatest(String category, boolean unreadOnly, int limit) {
            return messages.stream()
                    .filter(message -> category == null || category.equals(message.category()))
                    .filter(message -> !unreadOnly || message.readTime() == null)
                    .sorted(Comparator.comparingLong(NotificationMessage::createdTime).reversed()
                            .thenComparing(NotificationMessage::id, Comparator.reverseOrder()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public NotificationMessage findById(String id) {
            return messages.stream()
                    .filter(message -> message.id().equals(id))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public NotificationHtmlContent findHtmlContent(String id) {
            NotificationMessage message = findById(id);
            return message == null || message.contentHtml() == null
                    ? null
                    : new NotificationHtmlContent(message.contentUrl(), message.contentHtml());
        }

        @Override
        public boolean blocksRemoteAnnouncementImport(String id) {
            NotificationMessage message = messages.stream()
                    .filter(candidate -> candidate.id().equals(id))
                    .findFirst()
                    .orElse(null);
            return dismissedIds.contains(id)
                    || message != null && !NotificationCategory.ANNOUNCEMENT.token().equals(message.category());
        }

        @Override
        public int restoreRemoteAnnouncementHtml(String id, String contentUrl, String contentHtml) {
            synchronized (messages) {
                NotificationMessage current = findById(id);
                if (current == null || current.contentHtml() != null
                        || !NotificationCategory.ANNOUNCEMENT.token().equals(current.category())) {
                    return 0;
                }
                messages.remove(current);
                messages.add(new NotificationMessage(
                        current.id(), current.category(), current.severity(), current.scenarioId(),
                        current.title(), current.body(), contentUrl, contentHtml, current.actionUrl(),
                        current.createdTime(), current.readTime()));
                return 1;
            }
        }

        @Override
        public List<RemoteAnnouncementTranslation> findRemoteAnnouncementTranslations(String announcementId) {
            if (findById(announcementId) == null) {
                return List.of();
            }
            return remoteTranslations.getOrDefault(announcementId, List.of()).stream()
                    .map(translation -> new RemoteAnnouncementTranslation(
                            translation.locale(), translation.title(), translation.summary(),
                            translation.contentUrl(), translation.contentSha256(), ""))
                    .sorted(Comparator.comparing(RemoteAnnouncementTranslation::locale))
                    .toList();
        }

        @Override
        public NotificationHtmlContent findRemoteAnnouncementHtml(String announcementId, String locale) {
            if (findById(announcementId) == null) {
                return null;
            }
            return remoteTranslations.getOrDefault(announcementId, List.of()).stream()
                    .filter(translation -> translation.locale().equals(locale))
                    .findFirst()
                    .map(translation -> new NotificationHtmlContent(
                            translation.contentUrl(), translation.contentHtml()))
                    .orElse(null);
        }

        @Override
        public int upsertRemoteAnnouncementTranslation(
                String announcementId,
                RemoteAnnouncementTranslation translation) {
            remoteTranslations.compute(announcementId, (ignored, existing) -> {
                List<RemoteAnnouncementTranslation> updated = new ArrayList<>(
                        existing == null ? List.of() : existing);
                updated.removeIf(current -> current.locale().equals(translation.locale()));
                updated.add(translation);
                return List.copyOf(updated);
            });
            return 1;
        }

        @Override
        public int deleteStaleRemoteAnnouncementTranslations(String announcementId, List<String> locales) {
            AtomicInteger removed = new AtomicInteger();
            remoteTranslations.computeIfPresent(announcementId, (ignored, existing) -> {
                List<RemoteAnnouncementTranslation> updated = new ArrayList<>(existing);
                int before = updated.size();
                updated.removeIf(translation -> !locales.contains(translation.locale()));
                removed.set(before - updated.size());
                return List.copyOf(updated);
            });
            return removed.get();
        }

        @Override
        public int deleteRemoteAnnouncementTranslations(String announcementId) {
            List<RemoteAnnouncementTranslation> removed = remoteTranslations.remove(announcementId);
            return removed == null ? 0 : removed.size();
        }

        @Override
        public synchronized int acceptRemoteAnnouncementIndex(
                long sequence, String manifestSha256, long generatedTime, long expiresTime) {
            if (sequence < acceptedSequence
                    || sequence == acceptedSequence && !Objects.equals(manifestSha256, acceptedDigest)) {
                return 0;
            }
            acceptedSequence = sequence;
            acceptedDigest = manifestSha256;
            return 1;
        }

        @Override
        public long countUnread(String category) {
            return messages.stream()
                    .filter(message -> category == null || category.equals(message.category()))
                    .filter(message -> message.readTime() == null)
                    .count();
        }

        @Override
        public int markRead(String id, long readTime) {
            synchronized (messages) {
                NotificationMessage current = findById(id);
                if (current == null || current.readTime() != null) {
                    return 0;
                }
                messages.remove(current);
                messages.add(new NotificationMessage(
                        current.id(), current.category(), current.severity(), current.scenarioId(),
                        current.title(), current.body(), current.contentUrl(), current.contentHtml(),
                        current.actionUrl(),
                        current.createdTime(), Math.max(current.createdTime(), readTime)));
                return 1;
            }
        }

        @Override
        public int markAllRead(String category, long readTime) {
            int updated = 0;
            for (NotificationMessage message : List.copyOf(messages)) {
                if (category == null || category.equals(message.category())) {
                    updated += markRead(message.id(), readTime);
                }
            }
            return updated;
        }

        @Override
        public int dismissAnnouncement(String id, long deletedTime) {
            NotificationMessage message = findById(id);
            if (message == null || !NotificationCategory.ANNOUNCEMENT.token().equals(message.category())) {
                return 0;
            }
            messages.remove(message);
            dismissedIds.add(id);
            return 1;
        }

        @Override
        public int dismissPersistentSurvey(String id, long deletedTime) {
            return 0;
        }

        @Override
        public int setActivePersistentSurveys(List<String> activeIds) {
            return 0;
        }

        @Override
        public int deleteNonAnnouncement(String id) {
            NotificationMessage message = findById(id);
            if (message == null || NotificationCategory.ANNOUNCEMENT.token().equals(message.category())) {
                return 0;
            }
            return messages.remove(message) ? 1 : 0;
        }

        @Override
        public int pruneRetentionPool(long cutoffTime, int maxMessages) {
            return 0;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class LifecycleConfiguration {

        @Bean(name = "notificationAnnouncementTaskScheduler", destroyMethod = "shutdown")
        ThreadPoolTaskScheduler scheduler() {
            return new NotificationPluginConfiguration().notificationAnnouncementTaskScheduler();
        }

        @Bean(destroyMethod = "close")
        StubClient client(SigningFixture signing) {
            return new StubClient(signing);
        }

        @Bean
        SigningFixture signing() {
            return SigningFixture.create();
        }

        @Bean
        RecordingMapper mapper() {
            return new RecordingMapper();
        }

        @Bean
        NotificationInboxService inbox(RecordingMapper mapper) {
            return new NotificationInboxService(mapper);
        }

        @Bean
        RemoteAnnouncementImporter importer(
                StubClient client, NotificationInboxService inbox, SigningFixture signing) {
            return new RemoteAnnouncementImporter(
                    client, new ObjectMapper(), inbox, signing.verifier(), CLOCK);
        }
    }
}
