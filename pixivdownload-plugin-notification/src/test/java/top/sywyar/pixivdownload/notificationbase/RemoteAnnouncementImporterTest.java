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
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("远程公告导入")
class RemoteAnnouncementImporterTest {

    private static final String PUBLISHED = "2026-08-12T00:00:00Z";
    private static final LocaleBundlePolicy LOCALE_POLICY = new LocaleBundlePolicy() {
        @Override
        public List<Locale> supportedLocales() {
            return List.of(Locale.SIMPLIFIED_CHINESE, Locale.US);
        }

        @Override
        public Locale normalize(Locale requested) {
            return requested != null && requested.getLanguage().equals(Locale.CHINESE.getLanguage())
                    ? Locale.SIMPLIFIED_CHINESE
                    : Locale.US;
        }

        @Override
        public List<String> resourceSuffixChain(Locale requested) {
            return normalize(requested).equals(Locale.SIMPLIFIED_CHINESE)
                    ? List.of("", "en")
                    : List.of("en", "");
        }
    };

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
        assertThat(harness.client.lastContentRequest).satisfies(request -> {
            assertThat(request.uri().toString()).endsWith("/newer/en-US.html");
            assertThat(request.headers()).containsEntry("Accept", List.of("text/html"));
        });
    }

    @Test
    @DisplayName("重复轮询不改写首次内容，显式删除后也不重新拉取复活")
    void repeatedPollKeepsFirstLocalizedHistoryAndReadState() {
        AtomicReference<Locale> locale = new AtomicReference<>(Locale.SIMPLIFIED_CHINESE);
        Harness harness = harness(locale);
        byte[] index = bytes(index(item("stable", PUBLISHED, "info")));

        assertThat(harness.importer.importIndex(index)).isEqualTo(1);
        NotificationMessage first = harness.inbox.find("remote-announcement:stable");
        NotificationHtmlContent firstContent = harness.inbox.htmlContent(first.id());
        NotificationMessage read = harness.inbox.markRead(first.id());
        locale.set(Locale.US);
        harness.client.contentPlan = new ResponsePlan(
                200, htmlHeaders(), bytes("<!doctype html><p>Changed</p>"), null);

        assertThat(harness.importer.importIndex(index)).isZero();
        assertThat(harness.inbox.find(first.id())).satisfies(message -> {
            assertThat(message.title()).isEqualTo("中文 stable");
            assertThat(message.body()).isEqualTo("中文摘要 stable");
            assertThat(message.contentUrl()).endsWith("/stable/zh-CN.html");
            assertThat(message.readTime()).isEqualTo(read.readTime());
        });
        assertThat(harness.inbox.htmlContent(first.id())).isEqualTo(firstContent);
        assertThat(harness.client.contentRequests).hasValue(1);
        assertThat(harness.inbox.unreadCount()).isZero();

        assertThat(harness.inbox.delete(first.id())).isTrue();
        assertThat(harness.importer.importIndex(index)).isZero();
        assertThat(harness.inbox.find(first.id())).isNull();
        assertThat(harness.client.contentRequests).hasValue(1);
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

        assertThat(harness.importer.importIndex(bytes(index(item("legacy", PUBLISHED, "info")))))
                .isEqualTo(1);
        assertThat(harness.inbox.find("remote-announcement:legacy")).satisfies(message -> {
            assertThat(message.title()).isEqualTo("既有标题");
            assertThat(message.body()).isEqualTo("既有摘要");
            assertThat(message.readTime()).isEqualTo(publishedAt + 1);
            assertThat(message.hasHtmlContent()).isTrue();
        });
        assertThat(harness.inbox.htmlContent("remote-announcement:legacy").html()).isEqualTo(htmlBody());
        assertThat(harness.client.contentRequests).hasValue(1);
    }

    @Test
    @DisplayName("没有精确语言时先回退英文再回退中文")
    void localeFallbackPrefersEnglishThenChinese() {
        Harness englishFallback = harness(Locale.JAPAN);
        assertThat(englishFallback.importer.importIndex(bytes(index(item("fallback", PUBLISHED, "info")))))
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
                          "contentUrl": "%szh-only/zh-CN.html"
                        }
                        """.formatted(contentBase())));
        assertThat(chineseFallback.importer.importIndex(bytes(chineseOnly))).isEqualTo(1);
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

            assertThat(harness.importer.importIndex(bytes(index(item("invalid-html", PUBLISHED, "info")))))
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

        assertThat(harness.importer.importIndex(bytes(index(
                item("good", PUBLISHED, "info"), invalid)))).isEqualTo(1);

        assertThat(harness.inbox.find(existing.id())).isNotNull();
        assertThat(harness.inbox.find("remote-announcement:good")).isNotNull();
        assertThat(harness.inbox.find("remote-announcement:BAD")).isNull();
    }

    @Test
    @DisplayName("插件配置使用固定超时、禁止重定向和 Cookie 的单连接客户端")
    void configurationOwnsStrictHttpProfile() throws Exception {
        AtomicReference<OutboundHttpClientProfile> captured = new AtomicReference<>();
        StubClient client = new StubClient();
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
            assertThat(client.requests).hasValue(2);
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
        RecordingMapper mapper = new RecordingMapper();
        NotificationInboxService inbox = new NotificationInboxService(mapper);
        StubClient client = new StubClient();
        return new Harness(mapper, inbox, client,
                new RemoteAnnouncementImporter(client, new ObjectMapper(), inbox,
                        LOCALE_POLICY, locale::get));
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
        return indexWithLocales("[\"zh-CN\",\"en-US\"]", String.join(",", announcements));
    }

    private static String indexWithLocales(String locales, String announcements) {
        return """
                {
                  "schemaVersion": 1,
                  "requiredLocales": %s,
                  "announcements": [%s]
                }
                """.formatted(locales, announcements);
    }

    private static String item(String id, String publishedAt, String severity) {
        return itemWithTranslations(id, publishedAt, severity, """
                "zh-CN": {
                  "title": "中文 %s",
                  "summary": "中文摘要 %s",
                  "contentUrl": "%s%s/zh-CN.html"
                },
                "en-US": {
                  "title": "English %s",
                  "summary": "English summary %s",
                  "contentUrl": "%s%s/en-US.html"
                }
                """.formatted(id, id, contentBase(), id, id, id, contentBase(), id));
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

    private record Harness(
            RecordingMapper mapper,
            NotificationInboxService inbox,
            StubClient client,
            RemoteAnnouncementImporter importer) {
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

    private static final class StubClient implements OutboundHttpClient {
        private final CountDownLatch requested = new CountDownLatch(1);
        private final CountDownLatch contentRequested = new CountDownLatch(1);
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger contentRequests = new AtomicInteger();
        private volatile ResponsePlan plan = new ResponsePlan(
                200, jsonHeaders(), bytes(index(item("scheduled", PUBLISHED, "info"))), null);
        private volatile ResponsePlan contentPlan = new ResponsePlan(
                200, htmlHeaders(), bytes(htmlBody()), null);
        private volatile OutboundHttpRequest lastIndexRequest;
        private volatile OutboundHttpRequest lastContentRequest;
        private volatile boolean closed;

        private void respond(int status, Map<String, List<String>> headers, String body) {
            plan = new ResponsePlan(status, headers, bytes(body), null);
        }

        @Override
        public OutboundHttpStreamResponse exchangeStream(OutboundHttpRequest request) {
            requests.incrementAndGet();
            requested.countDown();
            boolean indexRequest = RemoteAnnouncementImporter.INDEX_URI.equals(request.uri());
            if (indexRequest) {
                lastIndexRequest = request;
            } else {
                lastContentRequest = request;
                contentRequests.incrementAndGet();
                contentRequested.countDown();
            }
            ResponsePlan current = indexRequest ? plan : contentPlan;
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
        public boolean needsRemoteAnnouncementImport(String id) {
            NotificationMessage message = findById(id);
            return !dismissedIds.contains(id)
                    && (message == null
                    || NotificationCategory.ANNOUNCEMENT.token().equals(message.category())
                    && message.contentHtml() == null);
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
        StubClient client() {
            return new StubClient();
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
        RemoteAnnouncementImporter importer(StubClient client, NotificationInboxService inbox) {
            return new RemoteAnnouncementImporter(
                    client, new ObjectMapper(), inbox, LOCALE_POLICY, () -> Locale.US);
        }
    }
}
