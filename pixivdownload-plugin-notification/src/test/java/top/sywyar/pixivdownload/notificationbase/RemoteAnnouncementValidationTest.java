package top.sywyar.pixivdownload.notificationbase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.notification.NotificationSeverity;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpTransportException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("远程公告输入校验")
class RemoteAnnouncementValidationTest extends RemoteAnnouncementImporterTestSupport {

    @Test
    @DisplayName("非法 schema、数量、字段、ID、级别、时间与语言均不影响既有消息")
    void rejectsInvalidSchemaAndBoundedFieldsWithoutHarmingExistingMessages() {
        String tooMany = IntStream.range(0, RemoteAnnouncementImporter.MAX_ANNOUNCEMENTS + 1)
                .mapToObj(index -> item("notice-" + index, PUBLISHED, "info"))
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
        List<String> invalidIndexes = List.of(
                index(item("valid", PUBLISHED, "info"))
                        .replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
                index(item("valid", PUBLISHED, "info"))
                        .replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"extra\": true"),
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
                NotificationCategory.SYSTEM,
                NotificationSeverity.INFO,
                null,
                "Existing",
                "Existing body",
                null);
        String invalid = item("BAD", PUBLISHED, "info");

        assertThat(harness.importIndex(bytes(index(item("good", PUBLISHED, "info"), invalid))))
                .isEqualTo(1);

        assertThat(harness.inbox.find(existing.id())).isNotNull();
        assertThat(harness.inbox.find("remote-announcement:good")).isNotNull();
        assertThat(harness.inbox.find("remote-announcement:BAD")).isNull();
    }

    private static void assertRejectedIndexPreservesExisting(String index) {
        Harness harness = harness(Locale.US);
        NotificationMessage existing = harness.inbox.publish(
                NotificationCategory.SYSTEM,
                NotificationSeverity.INFO,
                null,
                "Existing",
                "Existing body",
                null);
        harness.client.respond(200, jsonHeaders(), index);

        harness.importer.poll();

        assertThat(harness.inbox.find(existing.id()).title()).isEqualTo("Existing");
        assertThat(harness.inbox.unreadCount(NotificationCategory.ANNOUNCEMENT)).isZero();
    }
}
