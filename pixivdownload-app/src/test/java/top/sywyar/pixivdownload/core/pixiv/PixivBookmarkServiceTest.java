package top.sywyar.pixivdownload.core.pixiv;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.*;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.config.RestTemplateConfig;
import top.sywyar.pixivdownload.core.work.WorkActionResult;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
@DisplayName("PixivBookmarkService 单元测试")
class PixivBookmarkServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private PixivBookmarkService service;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.US);
        service = new PixivBookmarkService(restTemplate, new ObjectMapper(), TestI18nBeans.appMessages());
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    // ========== cookie 校验 ==========

    @Nested
    @DisplayName("bookmarkArtwork - cookie 校验")
    class CookieValidationTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("cookie 为空/null/空白时应直接返回，不调用 RestTemplate")
        void shouldSkipWhenCookieIsBlank(String cookie) {
            WorkActionResult result = service.bookmarkArtwork(12345L, cookie);

            assertThat(result.getStatus()).isEqualTo(WorkActionResult.SKIPPED);
            assertThat(result.getMessage()).isEqualTo("Cookie was not provided");
            verifyNoInteractions(restTemplate);
        }
    }

    // ========== CSRF token 提取 ==========

    @Nested
    @DisplayName("bookmarkArtwork - CSRF token 提取")
    class CsrfTokenTests {

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @DisplayName("插画与小说收藏保留各自端点和字段，并使用相同请求头")
        void shouldExtractCsrfAndPostBookmark(boolean novel) throws Exception {
            String bookmarkUrl = novel ? "https://www.pixiv.net/ajax/novels/bookmarks/add"
                    : "https://www.pixiv.net/ajax/illusts/bookmarks/add";
            String pageHtml = "<html><script>pixiv.context = {\"token\":\"abc123def456\"};</script></html>";
            String bookmarkResponse = "{\"error\":false,\"body\":{\"last_bookmark_id\":\"999\"}}";

            when(restTemplate.exchange(eq("https://www.pixiv.net/"), eq(HttpMethod.GET), any(), eq(byte[].class)))
                    .thenReturn(ResponseEntity.ok(pageHtml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            when(restTemplate.exchange(eq(bookmarkUrl), eq(HttpMethod.POST), any(), eq(byte[].class)))
                    .thenReturn(ResponseEntity.ok(bookmarkResponse.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            WorkActionResult result = novel ? service.bookmarkNovel(12345L, "PHPSESSID=test")
                    : service.bookmarkArtwork(12345L, "PHPSESSID=test");

            assertThat(result.getStatus()).isEqualTo(WorkActionResult.SUCCESS);

            ArgumentCaptor<HttpEntity<String>> postCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(
                    eq(bookmarkUrl),
                    eq(HttpMethod.POST),
                    postCaptor.capture(),
                    eq(byte[].class));
            HttpHeaders postHeaders = postCaptor.getValue().getHeaders();
            assertThat(postHeaders.getFirst("x-csrf-token")).isEqualTo("abc123def456");
            assertThat(postHeaders.getFirst(HttpHeaders.USER_AGENT)).isEqualTo(PixivRequestHeaders.USER_AGENT);
            assertThat(postHeaders.getFirst(HttpHeaders.ACCEPT)).contains("application/json");
            assertThat(postHeaders.getFirst(HttpHeaders.ORIGIN)).isEqualTo(PixivRequestHeaders.PIXIV_ORIGIN);
            assertThat(postHeaders.getFirst(HttpHeaders.COOKIE)).isEqualTo("PHPSESSID=test");
            assertThat(postHeaders.getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            String expectedBody = novel
                    ? "{\"novel_id\":\"12345\",\"restrict\":0,\"comment\":\"\",\"tags\":[]}"
                    : "{\"illust_id\":\"12345\",\"restrict\":0,\"comment\":\"\",\"tags\":[]}";
            assertThat(new ObjectMapper().readTree(postCaptor.getValue().getBody()))
                    .isEqualTo(new ObjectMapper().readTree(expectedBody));
        }

        @Test
        @DisplayName("Next.js 转义格式 token\\\":\\\"value\\\" 应能正常提取")
        void shouldExtractCsrfFromEscapedFormat() {
            // 新版 Pixiv Next.js 页面：token 嵌入 JS 字符串，引号被转义
            String pageHtml = "<script>JSON.parse(\"{\\\"token\\\":\\\"xyz789\\\",\\\"isLoggedIn\\\":true}\")</script>";
            when(restTemplate.exchange(eq("https://www.pixiv.net/"), eq(HttpMethod.GET), any(), eq(byte[].class)))
                    .thenReturn(ResponseEntity.ok(pageHtml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            when(restTemplate.exchange(eq("https://www.pixiv.net/ajax/illusts/bookmarks/add"), eq(HttpMethod.POST), any(), eq(byte[].class)))
                    .thenReturn(ResponseEntity.ok("{\"error\":false,\"body\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            ArgumentCaptor<HttpEntity<String>> postCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            assertThatCode(() -> service.bookmarkArtwork(12345L, "PHPSESSID=test")).doesNotThrowAnyException();
            verify(restTemplate).exchange(eq("https://www.pixiv.net/ajax/illusts/bookmarks/add"),
                    eq(HttpMethod.POST), postCaptor.capture(), eq(byte[].class));
            assertThat(postCaptor.getValue().getHeaders().getFirst("x-csrf-token")).isEqualTo("xyz789");
        }

        @Test
        @DisplayName("页面中不含 token 时 bookmarkArtwork 应吞掉异常")
        void shouldSwallowExceptionWhenTokenMissing() {
            when(restTemplate.exchange(eq("https://www.pixiv.net/"), eq(HttpMethod.GET), any(), eq(byte[].class)))
                    .thenReturn(ResponseEntity.ok("<html>no token here</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            WorkActionResult result = service.bookmarkArtwork(12345L, "PHPSESSID=test");
            assertThat(result.getStatus()).isEqualTo(WorkActionResult.FAILED);
            verify(restTemplate, never()).exchange(
                    eq("https://www.pixiv.net/ajax/illusts/bookmarks/add"), any(), any(), eq(byte[].class));
        }

        @Test
        @DisplayName("主页响应体为 null 时 bookmarkArtwork 应吞掉异常")
        void shouldSwallowExceptionWhenPageBodyIsNull() {
            when(restTemplate.exchange(eq("https://www.pixiv.net/"), eq(HttpMethod.GET), any(), eq(byte[].class)))
                    .thenReturn(ResponseEntity.ok(null));

            assertThatCode(() -> service.bookmarkArtwork(12345L, "PHPSESSID=test")).doesNotThrowAnyException();
        }
    }

    // ========== 收藏 POST 响应 ==========

    @Nested
    @DisplayName("bookmarkArtwork - 收藏 POST 响应")
    class BookmarkPostTests {

        private void mockPageWithToken(String token) {
            String pageHtml = "{\"token\":\"" + token + "\"}";
            when(restTemplate.exchange(eq("https://www.pixiv.net/"), eq(HttpMethod.GET), any(), eq(byte[].class)))
                    .thenReturn(ResponseEntity.ok(pageHtml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        @Test
        @DisplayName("POST 返回 2xx 且 error=false 时应成功完成")
        void shouldSucceedOn2xxWithNoError() {
            mockPageWithToken("tok111");
            when(restTemplate.exchange(eq("https://www.pixiv.net/ajax/illusts/bookmarks/add"), eq(HttpMethod.POST), any(), eq(byte[].class)))
                    .thenReturn(ResponseEntity.ok("{\"error\":false,\"body\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            WorkActionResult result = service.bookmarkArtwork(99L, "cookie=val");

            assertThat(result.getStatus()).isEqualTo(WorkActionResult.SUCCESS);
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @DisplayName("插画与小说收藏 API 返回错误时均返回受控失败结果")
        void shouldSwallowExceptionWhenApiReturnsError(boolean novel) {
            mockPageWithToken("tok222");
            String bookmarkUrl = novel ? "https://www.pixiv.net/ajax/novels/bookmarks/add"
                    : "https://www.pixiv.net/ajax/illusts/bookmarks/add";
            when(restTemplate.exchange(eq(bookmarkUrl), eq(HttpMethod.POST), any(), eq(byte[].class)))
                    .thenReturn(ResponseEntity.ok("{\"error\":true,\"message\":\"Not logged in\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            WorkActionResult result = novel ? service.bookmarkNovel(99L, "cookie=val")
                    : service.bookmarkArtwork(99L, "cookie=val");

            assertThat(result.getStatus()).isEqualTo(WorkActionResult.FAILED);
            assertThat(result.getMessage()).isEqualTo(TestI18nBeans.appMessages().get("bookmark.result.failed"));
            assertThat(result.getMessage()).doesNotContain("Not logged in");
        }

        @Test
        @DisplayName("POST 返回非 2xx 时 bookmarkArtwork 应吞掉异常")
        void shouldSwallowExceptionOnNon2xxResponse() {
            mockPageWithToken("tok333");
            when(restTemplate.exchange(eq("https://www.pixiv.net/ajax/illusts/bookmarks/add"), eq(HttpMethod.POST), any(), eq(byte[].class)))
                    .thenReturn(ResponseEntity.status(HttpStatus.FORBIDDEN).body("{\"error\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            assertThatCode(() -> service.bookmarkArtwork(99L, "cookie=val")).doesNotThrowAnyException();
        }
    }

    // ========== 异常隔离 ==========

    @Nested
    @DisplayName("生产凭证客户端的 HTTP 失败隔离")
    class HttpFailureTests {

        private RestTemplate credentialClient;
        private MockRestServiceServer server;

        @BeforeEach
        void setUpClient() {
            credentialClient = new RestTemplateConfig(new ProxyConfig()).pixivCredentialRestTemplate();
            server = MockRestServiceServer.bindTo(credentialClient).build();
            service = new PixivBookmarkService(credentialClient, new ObjectMapper(), TestI18nBeans.appMessages());
        }

        @AfterEach
        void closeClient() throws Exception {
            try {
                server.verify();
            } finally {
                ((DisposableBean) credentialClient).destroy();
            }
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @DisplayName("生产客户端仍可取得 CSRF token 并完成插画或小说收藏")
        void shouldPreserveSuccessfulBookmark(boolean novel) {
            server.expect(requestTo("https://www.pixiv.net/")).andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"token\":\"csrf-token\"}", MediaType.TEXT_HTML));
            String target = novel ? "https://www.pixiv.net/ajax/novels/bookmarks/add"
                    : "https://www.pixiv.net/ajax/illusts/bookmarks/add";
            server.expect(requestTo(target)).andExpect(method(HttpMethod.POST))
                    .andExpect(request -> assertThat(request.getHeaders().getFirst("x-csrf-token"))
                            .isEqualTo("csrf-token"))
                    .andRespond(withSuccess("{\"error\":false,\"body\":{}}", MediaType.APPLICATION_JSON));

            WorkActionResult result = novel ? service.bookmarkNovel(99L, "PHPSESSID=test")
                    : service.bookmarkArtwork(99L, "PHPSESSID=test");

            assertThat(result.getStatus()).isEqualTo(WorkActionResult.SUCCESS);
        }

        @ParameterizedTest
        @CsvSource({"403,false,false", "503,false,true", "403,true,false", "503,true,true"})
        @DisplayName("主页或收藏 POST 失败均不读取错误正文，也不继续提交收藏")
        void shouldSkipErrorBodyAndReturnFailure(int status, boolean post, boolean novel) {
            String target = "https://www.pixiv.net/";
            if (post) {
                server.expect(requestTo(target)).andExpect(method(HttpMethod.GET))
                        .andRespond(withSuccess("{\"token\":\"csrf-token\"}", MediaType.TEXT_HTML));
                target = novel ? "https://www.pixiv.net/ajax/novels/bookmarks/add"
                        : "https://www.pixiv.net/ajax/illusts/bookmarks/add";
            }
            byte[] bytes = "{\"token\":\"sensitive-upstream-token\",\"error\":false}"
                    .getBytes(StandardCharsets.UTF_8);
            AtomicInteger bytesRead = new AtomicInteger();
            AtomicBoolean closed = new AtomicBoolean();
            InputStream body = new InputStream() {
                @Override
                public int read() {
                    int offset = bytesRead.get();
                    if (offset == bytes.length) {
                        return -1;
                    }
                    bytesRead.incrementAndGet();
                    return bytes[offset] & 0xff;
                }

                @Override
                public void close() {
                    closed.set(true);
                }
            };
            server.expect(requestTo(target)).andExpect(method(post ? HttpMethod.POST : HttpMethod.GET))
                    .andRespond(request -> new MockClientHttpResponse(body, HttpStatusCode.valueOf(status)));

            WorkActionResult result = novel ? service.bookmarkNovel(99L, "PHPSESSID=test")
                    : service.bookmarkArtwork(99L, "PHPSESSID=test");

            assertThat(result.getStatus()).isEqualTo(WorkActionResult.FAILED);
            assertThat(result.getMessage()).isEqualTo(TestI18nBeans.appMessages().get("bookmark.result.failed"));
            assertThat(bytesRead.get()).isZero();
            assertThat(closed.get()).isTrue();
        }
    }

    @Nested
    @DisplayName("bookmarkArtwork - 异常隔离")
    class ExceptionIsolationTests {

        @Test
        @DisplayName("RestTemplate 抛出 RuntimeException 时 bookmarkArtwork 不应向上传播")
        void shouldIsolateRestTemplateException() {
            when(restTemplate.exchange(eq("https://www.pixiv.net/"), eq(HttpMethod.GET), any(), eq(byte[].class)))
                    .thenThrow(new RuntimeException("连接超时"));

            assertThatCode(() -> service.bookmarkArtwork(12345L, "PHPSESSID=abc")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("两步请求均失败时 bookmarkArtwork 不应抛出")
        void shouldIsolateBothStepsFailure() {
            when(restTemplate.exchange(anyString(), any(), any(), eq(byte[].class)))
                    .thenThrow(new RuntimeException("网络不可达"));

            assertThatCode(() -> service.bookmarkArtwork(99L, "cookie=xyz")).doesNotThrowAnyException();
        }
    }
}
