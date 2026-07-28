package top.sywyar.pixivdownload.douyin.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.douyin.parse.DouyinUrlParser;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@DisplayName("Douyin 重定向 RestTemplate 适配器")
class RestTemplateDouyinRedirectClientTest {

    @Test
    @DisplayName("注入的客户端保留重定向状态、响应头、正文与凭据请求头")
    void mapsRedirectResponseFromInjectedRestTemplate() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        URI request = URI.create("https://v.douyin.com/fixture/");
        URI location = URI.create("/video/7351234567890123456");
        server.expect(exchange -> {
                    assertThat(exchange.getURI()).isEqualTo(request);
                    assertThat(exchange.getMethod()).isEqualTo(HttpMethod.GET);
                    assertThat(exchange.getHeaders().getFirst(HttpHeaders.COOKIE))
                            .isEqualTo("sessionid=fixture");
                    assertThat(exchange.getHeaders().getFirst(HttpHeaders.USER_AGENT))
                            .isEqualTo(DouyinRequestHeaders.USER_AGENT);
                })
                .andRespond(withStatus(HttpStatus.FOUND)
                        .location(location)
                        .contentType(MediaType.TEXT_HTML)
                        .body("redirect-body"));

        DouyinRedirectResponse response =
                new RestTemplateDouyinRedirectClient(restTemplate)
                        .get(request, "sessionid=fixture");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.location()).isEqualTo(location);
        assertThat(response.contentType()).isEqualTo(MediaType.TEXT_HTML.toString());
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .isEqualTo("redirect-body");
        server.verify();
    }

    @Test
    @DisplayName("403 与 429 异常响应转换为领域响应而不从适配器抛出")
    void mapsHttpStatusExceptionsToDomainResponses() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        URI forbidden = URI.create("https://v.douyin.com/forbidden/");
        URI limited = URI.create("https://v.douyin.com/limited/");
        server.expect(requestTo(forbidden))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.TEXT_HTML)
                        .body("forbidden-body"));
        server.expect(requestTo(limited))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status_code\":429}"));
        RestTemplateDouyinRedirectClient client =
                new RestTemplateDouyinRedirectClient(restTemplate);

        DouyinRedirectResponse forbiddenResponse = client.get(forbidden);
        DouyinRedirectResponse limitedResponse = client.get(limited);

        assertThat(forbiddenResponse.statusCode()).isEqualTo(403);
        assertThat(forbiddenResponse.contentType()).isEqualTo(MediaType.TEXT_HTML.toString());
        assertThat(new String(forbiddenResponse.body(), StandardCharsets.UTF_8))
                .isEqualTo("forbidden-body");
        assertThat(limitedResponse.statusCode()).isEqualTo(429);
        assertThat(limitedResponse.contentType()).isEqualTo(MediaType.APPLICATION_JSON.toString());
        assertThat(new String(limitedResponse.body(), StandardCharsets.UTF_8))
                .isEqualTo("{\"status_code\":429}");
        server.verify();
    }

    @Test
    @DisplayName("真实 RestTemplate 错误流仍由短链解析器分类为 403 与 429")
    void resolverClassifiesMappedForbiddenAndRateLimitedResponses() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        URI forbidden = URI.create("https://v.douyin.com/forbidden/");
        URI limited = URI.create("https://v.douyin.com/limited/");
        server.expect(requestTo(forbidden))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("forbidden"));
        server.expect(requestTo(limited))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("limited"));
        DefaultDouyinShortLinkResolver resolver = new DefaultDouyinShortLinkResolver(
                new DouyinUrlParser(),
                new RestTemplateDouyinRedirectClient(restTemplate));

        assertCode(() -> resolver.resolve(forbidden.toString(), null),
                DouyinClientErrorCode.HTTP_FORBIDDEN);
        assertCode(() -> resolver.resolve(limited.toString(), null),
                DouyinClientErrorCode.HTTP_RATE_LIMITED);
        server.verify();
    }

    private static void assertCode(ThrowingRunnable action, DouyinClientErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(expected);
    }

    private static RequestMatcher requestTo(URI expected) {
        return request -> assertThat(request.getURI()).isEqualTo(expected);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
