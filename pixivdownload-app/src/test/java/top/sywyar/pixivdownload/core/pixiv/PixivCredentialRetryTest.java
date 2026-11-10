package top.sywyar.pixivdownload.core.pixiv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentials;

import java.net.URI;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Pixiv 有序凭据重试")
class PixivCredentialRetryTest {
    private static final URI PUBLIC = URI.create("https://www.pixiv.net/ajax/illust/42");
    private static final String A = "PHPSESSID=1_a";
    private static final String B = "PHPSESSID=2_b";
    private final AcquisitionCredentials scope = new AcquisitionCredentials(List.of(A, B));

    @Test @DisplayName("真实传输按候选顺序发送且底层不额外重试或跟随跳转")
    void realTransportDoesNotReplayOrRedirect() throws Exception {
        var attempts = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var redirected = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/read", exchange -> {
            String credential = exchange.getRequestHeaders().getFirst("Cookie");
            attempts.add(credential);
            int status = A.equals(credential) ? 401 : B.equals(credential) ? 429 : 200;
            exchange.getResponseHeaders().add("Retry-After", "120");
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.start();
        var proxy = new top.sywyar.pixivdownload.config.ProxyConfig();
        proxy.setEnabled(false);
        var client = new top.sywyar.pixivdownload.config.RestTemplateConfig(proxy).pixivImageRestTemplate();
        var retry = new PixivCredentialRetry();
        try {
            String address = "http://127.0.0.1:" + server.getAddress().getPort();
            String third = "PHPSESSID=3_third";
            var candidates = new AcquisitionCredentials(List.of(A, B, third));
            var result = candidates.call(() -> retry.execute(PUBLIC, A, cookie -> {
                var headers = new org.springframework.http.HttpHeaders();
                headers.add("Cookie", cookie);
                var response = client.exchange(address + "/read", org.springframework.http.HttpMethod.GET,
                        new org.springframework.http.HttpEntity<>(headers), byte[].class);
                int status = response.getStatusCode().value();
                if (status != 200) throw new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, status, 120_000);
                return status;
            }));
            assertThat(result).isEqualTo(200);
            assertThat(attempts).containsExactly(A, B, third);
            assertThat(client.getForEntity(address + "/redirect", byte[].class).getStatusCode().value()).isEqualTo(302);
            assertThat(redirected).hasValue(0);
        } finally {
            ((org.springframework.beans.factory.DisposableBean) client).destroy();
            retry.destroy(); server.stop(0);
        }
    }

    @Test @DisplayName("失效后按顺序切换且不改首选，冷却跨请求共享")
    void fallback() throws Exception {
        var retry = new PixivCredentialRetry();
        var attempts = new ArrayList<String>();
        String result = scope.call(() -> retry.execute(PUBLIC, A, cookie -> {
            attempts.add(cookie);
            if (cookie.equals(A)) throw new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 401);
            return "ok";
        }));
        assertThat(result).isEqualTo("ok");
        assertThat(attempts).containsExactly(A, B);
        attempts.clear();
        scope.call(() -> retry.execute(PUBLIC, A, cookie -> {attempts.add(cookie); return "ok";}));
        assertThat(attempts).containsExactly(B);
        assertThat(scope.primary()).isEqualTo(A);
        assertThat(AcquisitionCredentials.current()).isNull();
    }
    @ParameterizedTest @ValueSource(ints = {403, 404, 500, 503})
    @DisplayName("权限不足、作品不可见和服务错误不冒充凭据失效")
    void notAuthentication(int status) {
        var attempts = new ArrayList<String>();
        assertThatThrownBy(() -> scope.call(() -> new PixivCredentialRetry().execute(PUBLIC, A, cookie -> {
            attempts.add(cookie);
            throw new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, status);
        }))).isInstanceOf(PixivAjaxException.class);
        assertThat(attempts).containsExactly(A);
    }
    @Test @DisplayName("个人来源固定账号，全部耗尽和取消不产生额外请求")
    void accountAndCancellation() {
        var attempts = new ArrayList<String>();
        var retry = new PixivCredentialRetry();
        URI personal = URI.create("https://www.pixiv.net/ajax/user/1/illusts/bookmarks");
        assertThatThrownBy(() -> scope.call(() -> retry.execute(personal, A, cookie -> {
            attempts.add(cookie);
            throw new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 429, 100_000);
        }))).isInstanceOf(top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentialsUnavailableException.class);
        assertThat(attempts).containsExactly(A);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> scope.call(() -> retry.execute(PUBLIC, A, cookie -> {
                attempts.add(cookie); return "unexpected";
            }))).isInstanceOf(CancellationException.class);
        } finally { Thread.interrupted(); }
        assertThat(attempts).containsExactly(A);
    }
    @Test @DisplayName("解析秒数和 HTTP 日期冷却，超大秒数不会溢出成立即重试")
    void retryAfter() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"), ZoneOffset.UTC);
        assertThat(PixivCredentialRetry.retryAfter("120", clock)).isEqualTo(120_000);
        assertThat(PixivCredentialRetry.retryAfter("Mon, 9 Nov 2026 00:01:00 GMT", clock)).isEqualTo(60_000);
        assertThat(PixivCredentialRetry.retryAfter("9223372036854775807", clock)).isEqualTo(Long.MAX_VALUE);
        assertThat(PixivCredentialRetry.retryAfter("invalid", clock)).isZero();
    }

    @Test @DisplayName("429 冷却按账号共享，401 仅隔离失效版本")
    void accountCooldownAndCredentialVersion() throws Exception {
        var retry = new PixivCredentialRetry();
        scope.call(() -> retry.execute(PUBLIC, A, cookie -> {
            if (cookie.equals(A)) throw new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 429, 100_000);
            return "ok";
        }));
        var changed = new AcquisitionCredentials(List.of("PHPSESSID=1_new", B));
        var attempts = new ArrayList<String>();
        changed.call(() -> retry.execute(PUBLIC, changed.primary(), cookie -> { attempts.add(cookie); return "ok"; }));
        assertThat(attempts).containsExactly(B);
        retry.destroy();
        var invalid = new PixivCredentialRetry();
        scope.call(() -> invalid.execute(PUBLIC, A, cookie -> {
            if (cookie.equals(A)) throw new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 401);
            return "ok";
        }));
        attempts.clear();
        changed.call(() -> invalid.execute(PUBLIC, changed.primary(), cookie -> { attempts.add(cookie); return "ok"; }));
        assertThat(attempts).containsExactly("PHPSESSID=1_new");
        invalid.destroy();
    }

    @Test @DisplayName("格式无效候选不发请求，取消优先于后续备用请求")
    void malformedAndCancelledBetweenAttempts() throws Exception {
        var invalid = new AcquisitionCredentials(List.of("other=value", B));
        var retry = new PixivCredentialRetry();
        var attempts = new ArrayList<String>();
        invalid.call(() -> retry.execute(PUBLIC, invalid.primary(), cookie -> { attempts.add(cookie); return "ok"; }));
        assertThat(attempts).containsExactly(B);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var cancellable = scope.withCancellation(cancelled::get);
        attempts.clear();
        assertThatThrownBy(() -> cancellable.call(() -> retry.execute(PUBLIC, A, cookie -> {
            attempts.add(cookie); cancelled.set(true);
            throw new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 401);
        }))).isInstanceOf(CancellationException.class);
        assertThat(attempts).containsExactly(A);
        retry.destroy();
    }

    @Test @DisplayName("候选遍历共用截止时间，不给备用请求重新分配预算")
    void sharedDeadline() {
        var now = new java.util.concurrent.atomic.AtomicLong(1_000);
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        var retry = new PixivCredentialRetry(clock);
        var attempts = new ArrayList<String>();
        assertThatThrownBy(() -> scope.call(() -> retry.execute(PUBLIC, A, cookie -> {
            attempts.add(cookie); now.addAndGet(60_000);
            throw new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 401);
        }))).isInstanceOf(top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentialsUnavailableException.class);
        assertThat(attempts).containsExactly(A);
        retry.destroy();
    }
}
