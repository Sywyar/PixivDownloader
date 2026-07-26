package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessDecision;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessOutcome;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessPolicy;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.download.testsupport.WorkbenchTestMessages;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;

import java.net.URI;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PixivProxyController 单元测试")
class PixivProxyControllerTest {
    private static final MessageResolver MESSAGES = WorkbenchTestMessages.messages();
    private static final WorkVisibilityScope VISIBILITY_SCOPE = WorkVisibilityScope.unrestricted();

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private RestTemplate restTemplate;
    @Mock
    private PixivAjaxClient pixivAjaxClient;
    @Mock
    private PixivProxyAccessPolicy pixivProxyAccessPolicy;
    @Mock
    private RequestOwnerIdentityResolver requestOwnerIdentityResolver;
    @Mock
    private WorkVisibilityService workVisibilityService;

    @BeforeEach
    void setUp() {
        // 默认按放行策略执行（等价 solo / 管理员场景）；多人模式判定由具体用例覆盖 stub。
        lenient().when(pixivProxyAccessPolicy.evaluate(any(), anyBoolean()))
                .thenReturn(allowedDecision());
        lenient().when(pixivProxyAccessPolicy.resolveSearchFillLimitPage(anyBoolean()))
                .thenReturn(0);
        PixivFetchService pixivFetchService =
                new PixivFetchService(pixivAjaxClient, objectMapper);
        PixivProxyController controller = new PixivProxyController(
                objectMapper, restTemplate, pixivFetchService, pixivProxyAccessPolicy,
                requestOwnerIdentityResolver, workVisibilityService, MESSAGES);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new FixedVisibilityScopeResolver())
                .build();
    }

    private static PixivProxyAccessDecision allowedDecision() {
        return new PixivProxyAccessDecision(PixivProxyAccessOutcome.ALLOWED, null, 0, 0);
    }

    // ========== GET /api/pixiv/search ==========

    @Nested
    @DisplayName("GET /api/pixiv/search")
    class SearchTests {

        private static final String PIXIV_SEARCH_RESPONSE = """
                {
                  "error": false,
                  "body": {
                    "illustManga": {
                      "data": [
                        {
                          "id": "123456",
                          "title": "Test Artwork",
                          "illustType": 0,
                          "xRestrict": 0,
                          "aiType": 2,
                          "url": "https://i.pximg.net/c/250x250_80_a2/img-master/img/2024/01/01/00/00/00/123456_p0_master1200.jpg",
                          "pageCount": 3,
                          "userId": "9999",
                          "userName": "TestArtist",
                          "tags": ["初音ミク", "VOCALOID"]
                        }
                      ],
                      "total": 12345
                    }
                  }
                }
                """;

        @Test
        @DisplayName("合法参数应返回搜索结果")
        void shouldReturnSearchResults() throws Exception {
            when(pixivAjaxClient.get(any(URI.class), nullable(String.class)))
                    .thenReturn(PIXIV_SEARCH_RESPONSE);

            mockMvc.perform(get("/api/pixiv/search")
                            .param("word", "初音ミク")
                            .param("order", "date_d")
                            .param("mode", "all")
                            .param("page", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(12345))
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].id").value("123456"))
                    .andExpect(jsonPath("$.items[0].title").value("Test Artwork"))
                    .andExpect(jsonPath("$.items[0].xRestrict").value(0))
                    .andExpect(jsonPath("$.items[0].aiType").value(2))
                    .andExpect(jsonPath("$.items[0].pageCount").value(3))
                    .andExpect(jsonPath("$.items[0].userId").value("9999"))
                    .andExpect(jsonPath("$.items[0].userName").value("TestArtist"))
                    .andExpect(jsonPath("$.items[0].tags", hasSize(2)))
                    .andExpect(jsonPath("$.items[0].tags[0]").value("初音ミク"))
                    .andExpect(jsonPath("$.items[0].tags[1]").value("VOCALOID"));
        }

        @Test
        @DisplayName("通用取得凭证应作为 Pixiv Cookie 转发")
        void shouldForwardGenericAcquisitionCredential() throws Exception {
            when(pixivAjaxClient.get(any(URI.class), nullable(String.class)))
                    .thenReturn(PIXIV_SEARCH_RESPONSE);

            mockMvc.perform(get("/api/pixiv/search")
                            .param("word", "miku")
                            .header(AcquisitionCredentialResolver.HEADER_NAME, " generic-cookie "))
                    .andExpect(status().isOk());

            verify(pixivAjaxClient).get(any(URI.class), eq("generic-cookie"));
        }

        @Test
        @DisplayName("旧 Pixiv 凭证头仍应作为 Cookie 转发")
        void shouldForwardLegacyPixivCredential() throws Exception {
            when(pixivAjaxClient.get(any(URI.class), nullable(String.class)))
                    .thenReturn(PIXIV_SEARCH_RESPONSE);

            mockMvc.perform(get("/api/pixiv/search")
                            .param("word", "miku")
                            .header("X-Pixiv-Cookie", " legacy-cookie "))
                    .andExpect(status().isOk());

            verify(pixivAjaxClient).get(any(URI.class), eq("legacy-cookie"));
        }

        @Test
        @DisplayName("通用与旧 Pixiv 凭证冲突时应返回 400")
        void shouldRejectConflictingPixivCredentials() throws Exception {
            mockMvc.perform(get("/api/pixiv/search")
                            .param("word", "miku")
                            .header(AcquisitionCredentialResolver.HEADER_NAME, "generic-cookie")
                            .header("X-Pixiv-Cookie", "legacy-cookie"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Conflicting acquisition credential headers"));

            verifyNoInteractions(pixivAjaxClient, restTemplate);
        }

        @Test
        @DisplayName("非法 order 参数应返回 400")
        void shouldRejectInvalidOrder() throws Exception {
            mockMvc.perform(get("/api/pixiv/search")
                            .param("word", "src/main/test")
                            .param("order", "invalid_order"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(containsString("order")));
        }

        @Test
        @DisplayName("非法 mode 参数应返回 400")
        void shouldRejectInvalidMode() throws Exception {
            mockMvc.perform(get("/api/pixiv/search")
                            .param("word", "src/main/test")
                            .param("mode", "adult"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(containsString("mode")));
        }

        @Test
        @DisplayName("非法 sMode 参数应返回 400")
        void shouldRejectInvalidSMode() throws Exception {
            mockMvc.perform(get("/api/pixiv/search")
                            .param("word", "src/main/test")
                            .param("sMode", "s_invalid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(containsString("sMode")));
        }

        @Test
        @DisplayName("Pixiv API 返回 error:true 时应转发 400")
        void shouldReturnBadRequestWhenPixivErrors() throws Exception {
            String errorResponse = """
                    {"error": true, "message": "Rate limit exceeded", "body": []}
                    """;
            when(pixivAjaxClient.get(any(URI.class), nullable(String.class)))
                    .thenReturn(errorResponse);

            mockMvc.perform(get("/api/pixiv/search").param("word", "src/main/test"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Rate limit exceeded"));
        }

        @Test
        @DisplayName("含空格 / % 的关键词只能被编码一次（防 UriComponentsBuilder 二次编码）")
        void shouldEncodeWordExactlyOnce() throws Exception {
            when(pixivAjaxClient.get(any(URI.class), nullable(String.class)))
                    .thenReturn(PIXIV_SEARCH_RESPONSE);

            // 空格单次编码为 %20、二次编码为 %2520；字面 % 单次为 %25、二次为 %2525。
            mockMvc.perform(get("/api/pixiv/search").param("word", "blue archive 100%"))
                    .andExpect(status().isOk());

            verify(pixivAjaxClient).get(
                    argThat((URI uri) -> {
                        String s = uri.toString();
                        return s.contains("%20") && !s.contains("%2520")     // 空格只编码一次
                                && s.contains("100%25") && !s.contains("100%2525"); // % 只编码一次
                    }),
                    nullable(String.class));
        }

        @Test
        @DisplayName("默认参数时应使用 date_d 排序和 all 模式")
        void shouldUseDefaultParameters() throws Exception {
            when(pixivAjaxClient.get(any(URI.class), nullable(String.class)))
                    .thenReturn(PIXIV_SEARCH_RESPONSE);

            mockMvc.perform(get("/api/pixiv/search").param("word", "miku"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(1));

            // Verify the URI sent to Pixiv contains default params
            verify(pixivAjaxClient).get(
                    argThat((URI uri) -> {
                        String s = uri.toString();
                        return s.contains("order=date_d") && s.contains("mode=all") && s.contains("p=1");
                    }),
                    nullable(String.class));
        }
    }

    // ========== GET /api/pixiv/thumbnail-proxy ==========

    @Nested
    @DisplayName("GET /api/pixiv/thumbnail-proxy")
    class ThumbnailProxyTests {

        private static final byte[] DUMMY_IMAGE = new byte[]{(byte) 0xFF, (byte) 0xD8, 0x01, 0x02};
        private static final String VALID_URL =
                "https://i.pximg.net/c/250x250_80_a2/img-master/img/2024/01/01/123456_p0_master1200.jpg";

        @Test
        @DisplayName("合法 pximg.net URL 应代理图片并返回 200")
        void shouldProxyValidPximgUrl() throws Exception {
            when(restTemplate.exchange(eq(VALID_URL), eq(HttpMethod.GET), any(), eq(byte[].class)))
                    .thenReturn(new ResponseEntity<>(DUMMY_IMAGE, HttpStatus.OK));

            mockMvc.perform(get("/api/pixiv/thumbnail-proxy").param("url", VALID_URL))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", containsString("max-age")));
        }

        @Test
        @DisplayName("非 pximg.net 域名应返回 400（SSRF 防护）")
        void shouldRejectNonPximgUrl() throws Exception {
            mockMvc.perform(get("/api/pixiv/thumbnail-proxy")
                            .param("url", "https://evil.com/malicious.jpg"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(containsString("pximg.net")));
        }

        @Test
        @DisplayName("格式错误的 URL 应返回 400")
        void shouldRejectMalformedUrl() throws Exception {
            mockMvc.perform(get("/api/pixiv/thumbnail-proxy")
                            .param("url", "not a valid url !!##"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("i.pximg.net 子域名应被允许")
        void shouldAllowPximgSubdomain() throws Exception {
            String subdomainUrl = "https://i.pximg.net/img-original/img/2024/01/01/123456_p0.jpg";
            when(restTemplate.exchange(eq(subdomainUrl), eq(HttpMethod.GET), any(), eq(byte[].class)))
                    .thenReturn(new ResponseEntity<>(DUMMY_IMAGE, HttpStatus.OK));

            mockMvc.perform(get("/api/pixiv/thumbnail-proxy").param("url", subdomainUrl))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("pixiv.net 域名（非 pximg.net）应返回 400")
        void shouldRejectPixivNetDomain() throws Exception {
            mockMvc.perform(get("/api/pixiv/thumbnail-proxy")
                            .param("url", "https://www.pixiv.net/some/image.jpg"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("http 协议的 pximg.net URL 应返回 400（仅允许 https）")
        void shouldRejectHttpScheme() throws Exception {
            mockMvc.perform(get("/api/pixiv/thumbnail-proxy")
                            .param("url", "http://i.pximg.net/img-original/img/2024/01/01/123456_p0.jpg"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("即便客户端传 X-Pixiv-Cookie，也不会把 Cookie 转发给 pximg.net")
        void shouldNotForwardCookieToCdn() throws Exception {
            when(restTemplate.exchange(eq(VALID_URL), eq(HttpMethod.GET), any(), eq(byte[].class)))
                    .thenReturn(new ResponseEntity<>(DUMMY_IMAGE, HttpStatus.OK));

            mockMvc.perform(get("/api/pixiv/thumbnail-proxy")
                            .param("url", VALID_URL)
                            .header("X-Pixiv-Cookie", "PHPSESSID=12345_secret; other=value"))
                    .andExpect(status().isOk());

            verify(restTemplate).exchange(
                    eq(VALID_URL),
                    eq(HttpMethod.GET),
                    argThat(entity -> entity != null
                            && entity.getHeaders() != null
                            && !entity.getHeaders().containsKey("Cookie")
                            && PixivRequestHeaders.USER_AGENT.equals(
                                    entity.getHeaders().getFirst(HttpHeaders.USER_AGENT))),
                    eq(byte[].class));
        }
    }

    // ========== 代理访问控制 ==========

    @Nested
    @DisplayName("代理访问策略")
    class ProxyAccessTests {

        @Test
        @DisplayName("策略要求现有 owner UUID 时保持 401 error 响应形状")
        void shouldReturnOwnerRequiredResponse() throws Exception {
            when(requestOwnerIdentityResolver.resolveExistingOwnerUuid(any())).thenReturn(Optional.empty());
            when(pixivProxyAccessPolicy.evaluate(isNull(), eq(false))).thenReturn(
                    new PixivProxyAccessDecision(
                            PixivProxyAccessOutcome.OWNER_REQUIRED, "缺少用户 UUID", 0, 0));

            mockMvc.perform(get("/api/pixiv/user/9999/artworks"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("缺少用户 UUID"))
                    .andExpect(jsonPath("$.maxRequests").doesNotExist());

            verify(pixivProxyAccessPolicy).evaluate(isNull(), eq(false));
            verify(requestOwnerIdentityResolver, never()).resolve(any());
            verifyNoInteractions(pixivAjaxClient);
        }

        @Test
        @DisplayName("策略判定配额耗尽时保持 429 限流详情响应形状")
        void shouldReturnRateLimitResponse() throws Exception {
            String uuid = "owner-1";
            when(requestOwnerIdentityResolver.resolveExistingOwnerUuid(any())).thenReturn(Optional.of(uuid));
            when(pixivProxyAccessPolicy.evaluate(uuid, false)).thenReturn(
                    new PixivProxyAccessDecision(
                            PixivProxyAccessOutcome.RATE_LIMITED, "请求次数已达上限", 20, 24));

            mockMvc.perform(get("/api/pixiv/user/9999/artworks"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error").value("请求次数已达上限"))
                    .andExpect(jsonPath("$.maxRequests").value(20))
                    .andExpect(jsonPath("$.windowHours").value(24));

            verify(pixivProxyAccessPolicy).evaluate(uuid, false);
            verifyNoInteractions(pixivAjaxClient);
        }

        @Test
        @DisplayName("已认证管理员由稳定策略放行且不生成 owner 身份")
        void shouldPassAuthenticatedAdminToAccessPolicy() throws Exception {
            when(requestOwnerIdentityResolver.isAdminAuthenticated(any())).thenReturn(true);
            when(pixivProxyAccessPolicy.evaluate(isNull(), eq(true))).thenReturn(allowedDecision());
            when(pixivAjaxClient.get(any(URI.class), nullable(String.class)))
                    .thenReturn("{\"error\":false,\"body\":{\"illusts\":{},\"manga\":{}}}");

            mockMvc.perform(get("/api/pixiv/user/9999/artworks"))
                    .andExpect(status().isOk());

            verify(pixivProxyAccessPolicy).evaluate(isNull(), eq(true));
            verify(requestOwnerIdentityResolver, never()).resolve(any());
        }

        @Test
        @DisplayName("现有 owner UUID 由稳定策略放行后继续代理请求")
        void shouldPassExistingOwnerToAccessPolicy() throws Exception {
            String uuid = "owner-1";
            when(requestOwnerIdentityResolver.resolveExistingOwnerUuid(any())).thenReturn(Optional.of(uuid));
            when(pixivProxyAccessPolicy.evaluate(uuid, false)).thenReturn(allowedDecision());
            when(pixivAjaxClient.get(any(URI.class), nullable(String.class)))
                    .thenReturn("{\"error\":false,\"body\":{\"illusts\":{},\"manga\":{}}}");

            mockMvc.perform(get("/api/pixiv/user/9999/artworks"))
                    .andExpect(status().isOk());

            verify(pixivProxyAccessPolicy).evaluate(uuid, false);
            verify(pixivAjaxClient).get(any(URI.class), nullable(String.class));
        }
    }

    // ========== GET /api/pixiv/search/range ==========

    @Nested
    @DisplayName("GET /api/pixiv/search/range")
    class SearchRangeTests {

        private static final String PIXIV_SEARCH_RESPONSE = """
                {
                  "error": false,
                  "body": {
                    "illustManga": {
                      "data": [
                        {
                          "id": "123456",
                          "title": "Test Artwork",
                          "illustType": 0,
                          "xRestrict": 0,
                          "aiType": 0,
                          "url": "https://i.pximg.net/x.jpg",
                          "pageCount": 1,
                          "userId": "9999",
                          "userName": "TestArtist",
                          "tags": ["TagA"]
                        }
                      ],
                      "total": 12345
                    }
                  }
                }
                """;

        @Test
        @DisplayName("稳定策略不设补页上限时按页码范围抓取并跨页去重")
        void shouldFetchRangeAndDedupe() throws Exception {
            when(pixivAjaxClient.get(any(URI.class), nullable(String.class)))
                    .thenReturn(PIXIV_SEARCH_RESPONSE);

            mockMvc.perform(get("/api/pixiv/search/range")
                            .param("word", "初音ミク")
                            .param("startPage", "1")
                            .param("endPage", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(12345))
                    .andExpect(jsonPath("$.startPage").value(1))
                    .andExpect(jsonPath("$.endPage").value(2))
                    .andExpect(jsonPath("$.requestedPages").value(2))
                    .andExpect(jsonPath("$.fetchedPages").value(2))
                    .andExpect(jsonPath("$.limitPage").value(0))
                    // 两页相同 id 去重后只剩 1 个
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].id").value("123456"))
                    .andExpect(jsonPath("$.items[0].tags[0]").value("TagA"));

            verify(pixivProxyAccessPolicy).resolveSearchFillLimitPage(false);
        }

        @Test
        @DisplayName("已认证管理员按稳定策略不受补页上限限制")
        void shouldNotLimitRangePagesForAuthenticatedAdmin() throws Exception {
            when(requestOwnerIdentityResolver.isAdminAuthenticated(any())).thenReturn(true);
            when(pixivProxyAccessPolicy.resolveSearchFillLimitPage(true)).thenReturn(0);
            when(pixivAjaxClient.get(any(URI.class), nullable(String.class)))
                    .thenReturn(PIXIV_SEARCH_RESPONSE);

            mockMvc.perform(get("/api/pixiv/search/range")
                            .param("word", "初音ミク")
                            .param("startPage", "1")
                            .param("endPage", "3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestedPages").value(3))
                    .andExpect(jsonPath("$.acceptedPages").value(3))
                    .andExpect(jsonPath("$.fetchedPages").value(3))
                    .andExpect(jsonPath("$.limitPage").value(0));

            verify(pixivAjaxClient, times(3)).get(any(URI.class), nullable(String.class));
            verify(pixivProxyAccessPolicy).resolveSearchFillLimitPage(true);
            verify(requestOwnerIdentityResolver, never()).resolve(any());
        }

        @Test
        @DisplayName("游客补页范围受稳定策略返回的页数上限约束")
        void shouldCapRangePagesUsingAccessPolicy() throws Exception {
            when(pixivProxyAccessPolicy.resolveSearchFillLimitPage(false)).thenReturn(2);
            when(pixivAjaxClient.get(any(URI.class), nullable(String.class)))
                    .thenReturn(PIXIV_SEARCH_RESPONSE);

            mockMvc.perform(get("/api/pixiv/search/range")
                            .param("word", "初音ミク")
                            .param("startPage", "1")
                            .param("endPage", "3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestedPages").value(3))
                    .andExpect(jsonPath("$.acceptedPages").value(2))
                    .andExpect(jsonPath("$.fetchedPages").value(2))
                    .andExpect(jsonPath("$.limitPage").value(2));

            verify(pixivAjaxClient, times(2)).get(any(URI.class), nullable(String.class));
            verify(pixivProxyAccessPolicy).resolveSearchFillLimitPage(false);
        }

        @Test
        @DisplayName("startPage / endPage < 1 应返回 400")
        void shouldRejectInvalidRange() throws Exception {
            mockMvc.perform(get("/api/pixiv/search/range")
                            .param("word", "src/main/test")
                            .param("startPage", "0")
                            .param("endPage", "0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").isNotEmpty());

            verifyNoInteractions(pixivAjaxClient, restTemplate);
        }
    }

    // ========== GET /api/pixiv/artwork/{id}/meta ==========

    @Nested
    @DisplayName("GET /api/pixiv/artwork/{id}/meta")
    class ArtworkMetaTests {

        @Test
        @DisplayName("应返回 xRestrict / bookmarkCount / description / tags 等扩展字段")
        void shouldReturnExtendedArtworkMetaFields() throws Exception {
            String body = """
                    {
                      "error": false,
                      "body": {
                        "illustType": 0,
                        "illustTitle": "Demo",
                        "xRestrict": 2,
                        "aiType": 2,
                        "bookmarkCount": 1234,
                        "userId": "55555",
                        "userName": "TestArtist",
                        "description": "Hello World",
                        "tags": {
                          "tags": [
                            {"tag": "Cat", "translation": {"en": "猫"}},
                            {"tag": "Original"}
                          ]
                        }
                      }
                    }
                    """;
            when(pixivAjaxClient.get(any(URI.class), nullable(String.class))).thenReturn(body);

            mockMvc.perform(get("/api/pixiv/artwork/12345/meta"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.illustTitle").value("Demo"))
                    .andExpect(jsonPath("$.xRestrict").value(2))
                    .andExpect(jsonPath("$.isAi").value(true))
                    .andExpect(jsonPath("$.bookmarkCount").value(1234))
                    .andExpect(jsonPath("$.authorId").value(55555))
                    .andExpect(jsonPath("$.authorName").value("TestArtist"))
                    .andExpect(jsonPath("$.description").value("Hello World"))
                    .andExpect(jsonPath("$.tags", hasSize(2)))
                    .andExpect(jsonPath("$.tags[0].name").value("Cat"))
                    .andExpect(jsonPath("$.tags[0].translatedName").value("猫"))
                    .andExpect(jsonPath("$.tags[1].name").value("Original"))
                    .andExpect(jsonPath("$.tags[1].translatedName").doesNotExist());

            verify(workVisibilityService).requireVisible(VISIBILITY_SCOPE, WorkType.ARTWORK, 12345L);
        }

        @Test
        @DisplayName("非法 userId 应输出 null authorId 而非异常")
        void shouldReturnNullAuthorIdWhenUserIdMissing() throws Exception {
            String body = """
                    {
                      "error": false,
                      "body": {
                        "illustTitle": "X",
                        "xRestrict": 0,
                        "aiType": 0,
                        "userId": "",
                        "tags": {"tags": []}
                      }
                    }
                    """;
            when(pixivAjaxClient.get(any(URI.class), nullable(String.class))).thenReturn(body);

            mockMvc.perform(get("/api/pixiv/artwork/12345/meta"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authorId").doesNotExist())
                    .andExpect(jsonPath("$.tags", hasSize(0)));
        }
    }

    private static final class FixedVisibilityScopeResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == WorkVisibilityScope.class;
        }

        @Override
        public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer mavContainer,
                NativeWebRequest webRequest,
                WebDataBinderFactory binderFactory) {
            return VISIBILITY_SCOPE;
        }
    }
}
