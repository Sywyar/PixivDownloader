package top.sywyar.pixivdownload.notificationbase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.notification.NotificationSeverity;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpTransportException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("远程公告轮询")
class RemoteAnnouncementPollingTest extends RemoteAnnouncementImporterTestSupport {

    @Test
    @DisplayName("超时、非成功、重定向、错误媒体类型与超大响应均保留既有消息")
    void transportAndResponseFailuresPreserveExistingMessages() {
        List<ResponsePlan> failures = new ArrayList<>();
        failures.add(ResponsePlan.failure(new OutboundHttpTransportException("timeout")));
        failures.add(new ResponsePlan(
                503,
                jsonHeaders(),
                bytes(index(item("new", PUBLISHED, "info"))),
                null));
        failures.add(new ResponsePlan(
                302,
                Map.of(
                        "Content-Type", List.of("application/json"),
                        "Location", List.of("https://example.test/index.json")),
                bytes(index(item("new", PUBLISHED, "info"))),
                null));
        failures.add(new ResponsePlan(
                200,
                Map.of("Content-Type", List.of("text/html")),
                bytes(index(item("new", PUBLISHED, "info"))),
                null));
        failures.add(new ResponsePlan(
                200,
                jsonHeaders(),
                new byte[RemoteAnnouncementImporter.MAX_INDEX_BYTES + 1],
                null));

        for (ResponsePlan failure : failures) {
            Harness harness = harness(Locale.US);
            NotificationMessage existing = harness.inbox.publish(
                    NotificationCategory.SYSTEM,
                    NotificationSeverity.INFO,
                    null,
                    "Existing",
                    "Existing body",
                    null);
            harness.client.plan = failure;

            harness.importer.poll();

            assertThat(harness.inbox.find(existing.id()).title()).isEqualTo("Existing");
            assertThat(harness.inbox.unreadCount(NotificationCategory.ANNOUNCEMENT)).isZero();
        }
    }

    @Test
    @DisplayName("仅在可信索引有效期内发送条件请求并接受未修改响应")
    void reusesTrustedIndexValidators() {
        Harness harness = harness(Locale.US);
        String body = index(item("cached", PUBLISHED, "info"));
        Map<String, List<String>> headers = Map.of(
                "Content-Type", List.of("application/json; charset=utf-8"),
                "ETag", List.of("\"announcement-v1\""),
                "Last-Modified", List.of("Wed, 12 Aug 2026 09:22:58 GMT"));
        harness.client.respond(200, headers, body);

        harness.importer.poll();
        harness.client.plan = new ResponsePlan(304, Map.of(), new byte[0], null);
        harness.importer.poll();

        harness.client.respond(200, Map.of(
                "Content-Type", List.of("application/json; charset=utf-8"),
                "ETag", List.of("\"announcement-v2\"")), body);
        harness.importer.poll();
        harness.client.plan = new ResponsePlan(304, Map.of(), new byte[0], null);
        harness.importer.poll();

        assertThat(harness.mapper.findRemoteAnnouncementValidators()).satisfies(validators -> {
            assertThat(validators.etag()).isEqualTo("\"announcement-v2\"");
            assertThat(validators.lastModified()).isNull();
        });
        assertThat(harness.client.lastIndexRequest.headers())
                .containsEntry("If-None-Match", List.of("\"announcement-v2\""))
                .doesNotContainKey("If-Modified-Since");
        assertThat(harness.client.requests).hasValue(8);
        assertThat(harness.client.contentRequests).hasValue(2);
    }

    @Test
    @DisplayName("过期可信索引不再发送缓存验证头")
    void doesNotReuseExpiredValidators() {
        MutableClock clock = new MutableClock(CLOCK.instant());
        Harness harness = harness(Locale.US, clock, ignored -> 0);
        String body = indexWithMetadata(
                1,
                GENERATED,
                "2026-08-14T00:01:00Z",
                "[\"zh-CN\",\"en-US\"]",
                item("expiring", PUBLISHED, "info"));
        harness.client.respond(200, Map.of(
                "Content-Type", List.of("application/json; charset=utf-8"),
                "ETag", List.of("\"expiring\"")), body);

        harness.importer.poll();
        clock.advance(Duration.ofMinutes(1));
        harness.importer.poll();

        assertThat(harness.client.lastIndexRequest.headers())
                .doesNotContainKeys("If-None-Match", "If-Modified-Since");
    }

    @Test
    @DisplayName("首次与成功轮询使用有界随机抖动")
    void jittersInitialAndSuccessfulPolls() {
        MutableClock clock = new MutableClock(CLOCK.instant());
        Harness harness = harness(Locale.US, clock, bound -> bound - 1);

        harness.importer.tick();
        clock.advance(Duration.ofMinutes(30).minusMillis(1));
        harness.importer.tick();
        assertThat(harness.client.requests).hasValue(0);

        clock.advance(Duration.ofMillis(1));
        harness.importer.tick();
        assertThat(harness.client.requests).hasValue(4);

        clock.advance(Duration.ofHours(6).plusMinutes(54).minusMillis(1));
        harness.importer.tick();
        assertThat(harness.client.requests).hasValue(4);

        clock.advance(Duration.ofMillis(1));
        harness.importer.tick();
        assertThat(harness.client.requests).hasValue(6);
    }

    @Test
    @DisplayName("限流响应按 Retry-After 延后轮询")
    void honorsRetryAfterForRateLimits() {
        MutableClock clock = new MutableClock(CLOCK.instant());
        Harness harness = harness(Locale.US, clock, ignored -> 0);
        harness.client.plan = new ResponsePlan(
                429, Map.of("Retry-After", List.of("3600")), new byte[0], null);

        harness.importer.tick();
        harness.client.respond(200, jsonHeaders(), index(item("after-limit", PUBLISHED, "info")));
        clock.advance(Duration.ofHours(1).minusMillis(1));
        harness.importer.tick();
        assertThat(harness.client.requests).hasValue(1);

        clock.advance(Duration.ofMillis(1));
        harness.importer.tick();
        assertThat(harness.client.requests).hasValue(5);

        clock.advance(Duration.ofHours(5).plusMinutes(6).minusMillis(1));
        harness.importer.tick();
        assertThat(harness.client.requests).hasValue(5);

        clock.advance(Duration.ofMillis(1));
        harness.importer.tick();
        assertThat(harness.client.requests).hasValue(7);
    }

    @Test
    @DisplayName("连续传输与服务端故障逐级延长重试间隔")
    void backsOffRepeatedTransientFailures() {
        MutableClock clock = new MutableClock(CLOCK.instant());
        Harness harness = harness(Locale.US, clock, ignored -> 0);
        harness.client.plan = ResponsePlan.failure(
                new OutboundHttpTransportException("network unavailable"));

        harness.importer.tick();
        harness.client.plan = new ResponsePlan(503, jsonHeaders(), new byte[0], null);
        clock.advance(Duration.ofMinutes(5).minusMillis(1));
        harness.importer.tick();
        assertThat(harness.client.requests).hasValue(1);

        clock.advance(Duration.ofMillis(1));
        harness.importer.tick();
        assertThat(harness.client.requests).hasValue(2);

        clock.advance(Duration.ofMinutes(15).minusMillis(1));
        harness.importer.tick();
        assertThat(harness.client.requests).hasValue(2);

        clock.advance(Duration.ofMillis(1));
        harness.importer.tick();
        assertThat(harness.client.requests).hasValue(3);
    }

    @Test
    @DisplayName("客户端错误按正常周期检查而不高频重试")
    void doesNotRapidlyRetryClientErrors() {
        MutableClock clock = new MutableClock(CLOCK.instant());
        Harness harness = harness(Locale.US, clock, ignored -> 0);
        harness.client.plan = new ResponsePlan(404, jsonHeaders(), new byte[0], null);

        harness.importer.tick();
        clock.advance(Duration.ofHours(5).plusMinutes(6).minusMillis(1));
        harness.importer.tick();
        assertThat(harness.client.requests).hasValue(1);

        clock.advance(Duration.ofMillis(1));
        harness.importer.tick();
        assertThat(harness.client.requests).hasValue(2);
    }
}
