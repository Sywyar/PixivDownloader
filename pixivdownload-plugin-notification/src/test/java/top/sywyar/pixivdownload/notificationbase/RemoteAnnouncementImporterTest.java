package top.sywyar.pixivdownload.notificationbase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("远程公告导入")
class RemoteAnnouncementImporterTest extends RemoteAnnouncementImporterTestSupport {

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
            assertThat(message.createdTime())
                    .isEqualTo(Instant.parse("2026-08-12T00:00:00Z").toEpochMilli());
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
    @DisplayName("公告严重程度变更会刷新同一消息并保留已读状态")
    void refreshesSeverityWithoutResettingReadState() {
        Harness harness = harness(Locale.US);
        byte[] initial = bytes(indexWithSequenceAndLocales(
                1, "[\"en-US\",\"zh-CN\"]", item("stable", PUBLISHED, "info")));
        byte[] refreshed = bytes(indexWithSequenceAndLocales(
                2, "[\"en-US\",\"zh-CN\"]", item("stable", PUBLISHED, "critical")));

        assertThat(harness.importIndex(initial)).isEqualTo(1);
        Long readTime = harness.inbox.markRead("remote-announcement:stable").readTime();

        assertThat(harness.importIndex(refreshed)).isEqualTo(1);
        assertThat(harness.inbox.find("remote-announcement:stable")).satisfies(message -> {
            assertThat(message.severity()).isEqualTo("ERROR");
            assertThat(message.createdTime()).isEqualTo(Instant.parse(PUBLISHED).toEpochMilli());
            assertThat(message.readTime()).isEqualTo(readTime);
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
        assertThat(harness.inbox.htmlContent("remote-announcement:legacy").html())
                .isEqualTo(htmlBody());
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
}
