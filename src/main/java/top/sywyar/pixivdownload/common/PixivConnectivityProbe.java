package top.sywyar.pixivdownload.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.config.ProxyConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * 探测后端到 Pixiv 首页的网络连通性。
 *
 * <p>固定只访问 {@code https://www.pixiv.net/}，不接受调用方传入 URL、不携带或转发任何 Pixiv Cookie、
 * 丢弃上游响应正文，仅返回是否可达 + 往返耗时（连通性体检用）。走当前 {@link ProxyConfig} 配置的 HTTP 代理。
 * 供 GUI 状态页与新用户引导的网络检测复用。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PixivConnectivityProbe {

    private static final URI PIXIV_URI = URI.create("https://www.pixiv.net/");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final ProxyConfig proxyConfig;

    /**
     * 探测结果。
     *
     * @param reachable  是否可达（上游返回 2xx~4xx 视为网络可达）
     * @param statusCode 上游 HTTP 状态码（不可达时为 0）
     * @param latencyMs  往返耗时（毫秒）
     * @param errorType  失败类型：{@code timeout} / {@code interrupted} / {@code network} / {@code unknown}；成功为 null
     */
    public record Result(boolean reachable, int statusCode, long latencyMs, String errorType) {
    }

    public Result probe() {
        long started = System.nanoTime();
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(PIXIV_URI)
                    .timeout(REQUEST_TIMEOUT)
                    .GET();
            PixivRequestHeaders.applyDocument(requestBuilder, null);
            HttpRequest request = requestBuilder.build();
            HttpResponse<Void> response = buildClient()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            long latencyMs = elapsedMillis(started);
            int status = response.statusCode();
            return new Result(status >= 200 && status < 500, status, latencyMs, null);
        } catch (HttpTimeoutException e) {
            return new Result(false, 0, elapsedMillis(started), "timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, 0, elapsedMillis(started), "interrupted");
        } catch (IOException e) {
            log.debug("Pixiv connectivity probe failed: {}", e.getMessage());
            return new Result(false, 0, elapsedMillis(started), "network");
        } catch (RuntimeException e) {
            log.debug("Pixiv connectivity probe failed: {}", e.getMessage(), e);
            return new Result(false, 0, elapsedMillis(started), "unknown");
        }
    }

    private HttpClient buildClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxyConfig.isEnabled()) {
            String host = proxyConfig.getHost();
            int port = proxyConfig.getPort();
            if (host != null && !host.isBlank() && port > 0) {
                builder.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
            }
        }
        return builder.build();
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
    }
}
