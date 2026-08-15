package top.sywyar.pixivdownload.setup;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import top.sywyar.pixivdownload.common.NetworkUtils;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("可信反向代理请求规范化")
class TrustedForwardedRequestFilterTest {

    @Test
    @DisplayName("Nginx 代理头规范化为外部来源和真实客户端")
    void normalizesNginxForwardedHeaders() throws Exception {
        TrustedForwardedRequestFilter filter = new TrustedForwardedRequestFilter("10.0.0.0/8");
        MockHttpServletRequest request = proxyRequest("10.0.0.8");
        request.addHeader("X-Forwarded-For", "198.51.100.20");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "gallery.example:8443");
        request.addHeader("X-Forwarded-Prefix", "/ignored");

        HttpServletRequest normalized = capture(filter, request);

        assertThat(normalized.getRemoteAddr()).isEqualTo("198.51.100.20");
        assertThat(normalized.getScheme()).isEqualTo("https");
        assertThat(normalized.getServerName()).isEqualTo("gallery.example");
        assertThat(normalized.getServerPort()).isEqualTo(8443);
        assertThat(normalized.getHeader("Host")).isEqualTo("gallery.example:8443");
        assertThat(normalized.getHeader("X-Forwarded-For")).isNull();
        assertThat(normalized.getHeader("X-Forwarded-Prefix")).isNull();
        assertThat(NetworkUtils.isTrustedLocalRequest(normalized)).isFalse();
    }

    @Test
    @DisplayName("Caddy Forwarded 头规范化默认 HTTPS 端口")
    void normalizesCaddyForwardedHeader() throws Exception {
        TrustedForwardedRequestFilter filter = new TrustedForwardedRequestFilter("127.0.0.1/32");
        MockHttpServletRequest request = proxyRequest("127.0.0.1");
        request.addHeader("Forwarded", "for=203.0.113.9;proto=https;host=gallery.example");

        HttpServletRequest normalized = capture(filter, request);

        assertThat(normalized.getRemoteAddr()).isEqualTo("203.0.113.9");
        assertThat(normalized.getScheme()).isEqualTo("https");
        assertThat(normalized.getServerName()).isEqualTo("gallery.example");
        assertThat(normalized.getServerPort()).isEqualTo(443);
        assertThat(normalized.isSecure()).isTrue();
    }

    @Test
    @DisplayName("Docker 多级代理链从右向左跳过可信代理并忽略伪造前缀")
    void resolvesDockerProxyChainFromTrustedBoundary() throws Exception {
        TrustedForwardedRequestFilter filter = new TrustedForwardedRequestFilter("172.16.0.0/12");
        MockHttpServletRequest request = proxyRequest("172.18.0.3");
        request.addHeader("X-Forwarded-For", "192.0.2.44, 198.51.100.20, 172.18.0.2");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "gallery.example");

        HttpServletRequest normalized = capture(filter, request);

        assertThat(normalized.getRemoteAddr()).isEqualTo("198.51.100.20");
        assertThat(normalized.getHeader("Forwarded")).isNull();
        assertThat(normalized.getHeader("X-Real-IP")).isNull();
    }

    @Test
    @DisplayName("未受信来源携带代理头时直接拒绝")
    void rejectsForwardingHeadersFromUntrustedPeer() throws Exception {
        TrustedForwardedRequestFilter filter = new TrustedForwardedRequestFilter("10.0.0.0/8");
        MockHttpServletRequest request = proxyRequest("198.51.100.20");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        request.addHeader("X-Forwarded-Proto", "http");
        request.addHeader("X-Forwarded-Host", "localhost:6999");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilterInternal(request, response, (req, res) -> called.set(true));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(called).isFalse();
    }

    @Test
    @DisplayName("未受信来源携带其它 X-Forwarded 头时同样拒绝")
    void rejectsAnyXForwardedHeaderFromUntrustedPeer() throws Exception {
        TrustedForwardedRequestFilter filter = new TrustedForwardedRequestFilter("");
        MockHttpServletRequest request = proxyRequest("198.51.100.20");
        request.addHeader("X-Forwarded-Prefix", "/spoofed");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilterInternal(request, response, (req, res) -> called.set(true));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(called).isFalse();
    }

    @Test
    @DisplayName("可信代理缺少转发元数据时不会退化成本机请求")
    void rejectsTrustedProxyWithoutForwardingHeaders() throws Exception {
        TrustedForwardedRequestFilter filter = new TrustedForwardedRequestFilter("127.0.0.1/32");
        MockHttpServletRequest request = proxyRequest("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilterInternal(request, response, (req, res) -> called.set(true));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(called).isFalse();
    }

    @Test
    @DisplayName("代理链没有非受信客户端地址时不会退化成本机请求")
    void rejectsForwardedChainWithoutClientAddress() throws Exception {
        TrustedForwardedRequestFilter filter = new TrustedForwardedRequestFilter("127.0.0.1/32");
        MockHttpServletRequest request = proxyRequest("127.0.0.1");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        request.addHeader("X-Forwarded-Proto", "http");
        request.addHeader("X-Forwarded-Host", "localhost:6999");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilterInternal(request, response, (req, res) -> called.set(true));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(called).isFalse();
    }

    @Test
    @DisplayName("未配置代理且没有代理头时保持直接请求")
    void preservesDirectRequestWithoutForwardingHeaders() throws Exception {
        TrustedForwardedRequestFilter filter = new TrustedForwardedRequestFilter("");
        MockHttpServletRequest request = proxyRequest("127.0.0.1");

        assertThat(capture(filter, request)).isSameAs(request);
    }

    @Test
    @DisplayName("非法可信代理 CIDR 在启动时拒绝")
    void rejectsInvalidTrustedProxyCidr() {
        assertThatThrownBy(() -> new TrustedForwardedRequestFilter("10.0.0.0/99"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trusted proxy CIDR");
    }

    private static HttpServletRequest capture(TrustedForwardedRequestFilter filter,
                                              MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> captured = new AtomicReference<>();
        filter.doFilterInternal(request, response,
                (normalized, ignored) -> captured.set((HttpServletRequest) normalized));
        assertThat(response.getStatus()).isEqualTo(200);
        return captured.get();
    }

    private static MockHttpServletRequest proxyRequest(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/collections/7/icon");
        request.setRequestURI("/api/collections/7/icon");
        request.setRemoteAddr(remoteAddr);
        request.setScheme("http");
        request.setServerName("backend");
        request.setServerPort(6999);
        return request;
    }
}
