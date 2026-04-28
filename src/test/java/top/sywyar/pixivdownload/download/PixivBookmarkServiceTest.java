package top.sywyar.pixivdownload.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PixivBookmarkService 单元测试")
class PixivBookmarkServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private PixivBookmarkService service;

    @BeforeEach
    void setUp() {
        service = new PixivBookmarkService(restTemplate, new ObjectMapper(), TestI18nBeans.appMessages());
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
            DownloadActionResult result = service.bookmarkArtwork(12345L, cookie);

            assertThat(result.getStatus()).isEqualTo(DownloadActionResult.SKIPPED);
            assertThat(result.getMessage()).isEqualTo("未提供 Cookie");
            verifyNoInteractions(restTemplate);
        }
    }

    // ========== CSRF token 提取 ==========

    @Nested
    @DisplayName("bookmarkArtwork - CSRF token 提取")
    class CsrfTokenTests {

        @Test
        @DisplayName("正常提取 CSRF token 后应发送收藏 POST 请求")
        void shouldExtractCsrfAndPostBookmark() throws Exception {
            String pageHtml = "<html><script>pixiv.context = {\"token\":\"abc123def456\"};</script></html>";
            String bookmarkResponse = "{\"error\":false,\"body\":{\"last_bookmark_id\":\"999\"}}";

            when(restTemplate.exchange(eq("https://www.pixiv.net/"), eq(HttpMethod.GET), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok(pageHtml));
            when(restTemplate.exchange(eq("https://www.pixiv.net/ajax/illusts/bookmarks/add"), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok(bookmarkResponse));

            DownloadActionResult result = service.bookmarkArtwork(12345L, "PHPSESSID=test");

            assertThat(result.getStatus()).isEqualTo(DownloadActionResult.SUCCESS);

            ArgumentCaptor<HttpEntity<String>> postCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(
                    eq("https://www.pixiv.net/ajax/illusts/bookmarks/add"),
                    eq(HttpMethod.POST),
                    postCaptor.capture(),
                    eq(String.class));
            HttpHeaders postHeaders = postCaptor.getValue().getHeaders();
            assertThat(postHeaders.getFirst("x-csrf-token")).isEqualTo("abc123def456");
        }

        @Test
        @DisplayName("Next.js 转义格式 token\\\":\\\"value\\\" 应能正常提取")
        void shouldExtractCsrfFromEscapedFormat() {
            // 新版 Pixiv Next.js 页面：token 嵌入 JS 字符串，引号被转义
            String pageHtml = "<script>JSON.parse(\"{\\\"token\\\":\\\"xyz789\\\",\\\"isLoggedIn\\\":true}\")</script>";
            when(restTemplate.exchange(eq("https://www.pixiv.net/"), eq(HttpMethod.GET), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok(pageHtml));
            when(restTemplate.exchange(eq("https://www.pixiv.net/ajax/illusts/bookmarks/add"), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok("{\"error\":false,\"body\":{}}"));

            ArgumentCaptor<HttpEntity<String>> postCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            assertThatCode(() -> service.bookmarkArtwork(12345L, "PHPSESSID=test")).doesNotThrowAnyException();
            verify(restTemplate).exchange(eq("https://www.pixiv.net/ajax/illusts/bookmarks/add"),
                    eq(HttpMethod.POST), postCaptor.capture(), eq(String.class));
            assertThat(postCaptor.getValue().getHeaders().getFirst("x-csrf-token")).isEqualTo("xyz789");
        }

        @Test
        @DisplayName("页面中不含 token 时 bookmarkArtwork 应吞掉异常")
        void shouldSwallowExceptionWhenTokenMissing() {
            when(restTemplate.exchange(eq("https://www.pixiv.net/"), eq(HttpMethod.GET), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok("<html>no token here</html>"));

            DownloadActionResult result = service.bookmarkArtwork(12345L, "PHPSESSID=test");
            assertThat(result.getStatus()).isEqualTo(DownloadActionResult.FAILED);
            verify(restTemplate, never()).exchange(
                    eq("https://www.pixiv.net/ajax/illusts/bookmarks/add"), any(), any(), eq(String.class));
        }

        @Test
        @DisplayName("主页响应体为 null 时 bookmarkArtwork 应吞掉异常")
        void shouldSwallowExceptionWhenPageBodyIsNull() {
            when(restTemplate.exchange(eq("https://www.pixiv.net/"), eq(HttpMethod.GET), any(), eq(String.class)))
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
            when(restTemplate.exchange(eq("https://www.pixiv.net/"), eq(HttpMethod.GET), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok(pageHtml));
        }

        @Test
        @DisplayName("POST 返回 2xx 且 error=false 时应成功完成")
        void shouldSucceedOn2xxWithNoError() {
            mockPageWithToken("tok111");
            when(restTemplate.exchange(eq("https://www.pixiv.net/ajax/illusts/bookmarks/add"), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok("{\"error\":false,\"body\":{}}"));

            DownloadActionResult result = service.bookmarkArtwork(99L, "cookie=val");

            assertThat(result.getStatus()).isEqualTo(DownloadActionResult.SUCCESS);
        }

        @Test
        @DisplayName("POST 返回 error=true 时 bookmarkArtwork 应吞掉异常")
        void shouldSwallowExceptionWhenApiReturnsError() {
            mockPageWithToken("tok222");
            when(restTemplate.exchange(eq("https://www.pixiv.net/ajax/illusts/bookmarks/add"), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok("{\"error\":true,\"message\":\"Not logged in\"}"));

            DownloadActionResult result = service.bookmarkArtwork(99L, "cookie=val");

            assertThat(result.getStatus()).isEqualTo(DownloadActionResult.FAILED);
            assertThat(result.getMessage()).isEqualTo(TestI18nBeans.appMessages().get("bookmark.result.failed"));
            assertThat(result.getMessage()).doesNotContain("Not logged in");
        }

        @Test
        @DisplayName("POST 返回非 2xx 时 bookmarkArtwork 应吞掉异常")
        void shouldSwallowExceptionOnNon2xxResponse() {
            mockPageWithToken("tok333");
            when(restTemplate.exchange(eq("https://www.pixiv.net/ajax/illusts/bookmarks/add"), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.status(HttpStatus.FORBIDDEN).body("{\"error\":true}"));

            assertThatCode(() -> service.bookmarkArtwork(99L, "cookie=val")).doesNotThrowAnyException();
        }
    }

    // ========== 异常隔离 ==========

    @Nested
    @DisplayName("bookmarkArtwork - 异常隔离")
    class ExceptionIsolationTests {

        @Test
        @DisplayName("RestTemplate 抛出 RuntimeException 时 bookmarkArtwork 不应向上传播")
        void shouldIsolateRestTemplateException() {
            when(restTemplate.exchange(eq("https://www.pixiv.net/"), eq(HttpMethod.GET), any(), eq(String.class)))
                    .thenThrow(new RuntimeException("连接超时"));

            assertThatCode(() -> service.bookmarkArtwork(12345L, "PHPSESSID=abc")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("两步请求均失败时 bookmarkArtwork 不应抛出")
        void shouldIsolateBothStepsFailure() {
            when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                    .thenThrow(new RuntimeException("网络不可达"));

            assertThatCode(() -> service.bookmarkArtwork(99L, "cookie=xyz")).doesNotThrowAnyException();
        }
    }
}
