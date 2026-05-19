package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.GlobalExceptionHandler;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.quota.MultiModeConfig;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.setup.SetupService;

import java.net.URI;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PixivProxyController 单元测试")
class PixivProxyControllerTest {
    private static final AppMessages APP_MESSAGES = TestI18nBeans.appMessages();

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private RestTemplate restTemplate;
    @Mock
    private SetupService setupService;
    @Mock
    private UserQuotaService userQuotaService;
    @Mock
    private top.sywyar.pixivdownload.setup.guest.GuestAccessGuard guestAccessGuard;

    private MultiModeConfig multiModeConfig;

    @BeforeEach
    void setUp() {
        multiModeConfig = new MultiModeConfig();
        PixivProxyController controller = new PixivProxyController(
                objectMapper, restTemplate, setupService, userQuotaService, multiModeConfig,
                guestAccessGuard, APP_MESSAGES);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(APP_MESSAGES))
                .build();
    }

    // ========== GET /api/pixiv/search ==========

    @Nested
    @DisplayName("GET /api/pixiv/search")
    class SearchTests {

        @BeforeEach
        void setUpSoloMode() {
            // search endpoint calls checkMultiModeAccess which calls setupService.getMode()
            when(setupService.getMode()).thenReturn("solo");
        }

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
            when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok(PIXIV_SEARCH_RESPONSE));

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
            when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok(errorResponse));

            mockMvc.perform(get("/api/pixiv/search").param("word", "src/main/test"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Rate limit exceeded"));
        }

        @Test
        @DisplayName("默认参数时应使用 date_d 排序和 all 模式")
        void shouldUseDefaultParameters() throws Exception {
            when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok(PIXIV_SEARCH_RESPONSE));

            mockMvc.perform(get("/api/pixiv/search").param("word", "miku"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(1));

            // Verify the URI sent to Pixiv contains default params
            verify(restTemplate).exchange(
                    argThat((URI uri) -> {
                        String s = uri.toString();
                        return s.contains("order=date_d") && s.contains("mode=all") && s.contains("p=1");
                    }),
                    eq(HttpMethod.GET), any(), eq(String.class));
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
    }

    // ========== 多人模式访问控制 ==========

    @Nested
    @DisplayName("多人模式访问控制 (checkMultiModeAccess)")
    class MultiModeAccessTests {

        @BeforeEach
        void setUpMultiMode() {
            when(setupService.getMode()).thenReturn("multi");
        }

        @Test
        @DisplayName("缺少 UUID（cookie/header 都无）应返回 401")
        void shouldReturn401WhenUuidMissing() throws Exception {
            mockMvc.perform(get("/api/pixiv/user/9999/artworks"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value(containsString("UUID")));

            verify(userQuotaService, never()).checkAndReserveProxy(any());
        }

        @Test
        @DisplayName("UUID 已存在但代理请求超额应返回 429 + 提示")
        void shouldReturn429WhenProxyQuotaExceeded() throws Exception {
            String uuid = UUID.randomUUID().toString();
            multiModeConfig.getQuota().setMaxProxyRequests(20);
            multiModeConfig.getQuota().setResetPeriodHours(24);
            when(userQuotaService.checkAndReserveProxy(uuid)).thenReturn(false);

            mockMvc.perform(get("/api/pixiv/user/9999/artworks")
                            .cookie(new Cookie("pixiv_user_id", uuid)))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.maxRequests").value(20))
                    .andExpect(jsonPath("$.windowHours").value(24));
        }

        @Test
        @DisplayName("多人模式下管理员应跳过代理请求限流")
        void shouldBypassProxyLimitForAdmin() throws Exception {
            when(setupService.isAdminLoggedIn(any())).thenReturn(true);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok("{\"error\":false,\"body\":{\"illusts\":{},\"manga\":{}}}"));

            mockMvc.perform(get("/api/pixiv/user/9999/artworks"))
                    .andExpect(status().isOk());

            verify(userQuotaService, never()).checkAndReserveProxy(any());
        }

        @Test
        @DisplayName("UUID 合法且未超额应放行并消费一次代理配额")
        void shouldReserveProxyQuotaAndPassThrough() throws Exception {
            String uuid = UUID.randomUUID().toString();
            when(userQuotaService.checkAndReserveProxy(uuid)).thenReturn(true);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok("{\"error\":false,\"body\":{\"illusts\":{},\"manga\":{}}}"));

            mockMvc.perform(get("/api/pixiv/user/9999/artworks")
                            .cookie(new Cookie("pixiv_user_id", uuid)))
                    .andExpect(status().isOk());

            verify(userQuotaService).checkAndReserveProxy(uuid);
        }
    }

    // ========== GET /api/pixiv/search/range ==========

    @Nested
    @DisplayName("GET /api/pixiv/search/range")
    class SearchRangeTests {

        @BeforeEach
        void setUpSoloMode() {
            when(setupService.getMode()).thenReturn("solo");
        }

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
        @DisplayName("按页码范围抓取并跨页去重，solo 模式不限页数")
        void shouldFetchRangeAndDedupe() throws Exception {
            when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok(PIXIV_SEARCH_RESPONSE));

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

            verifyNoInteractions(restTemplate);
        }
    }

    // ========== GET /api/pixiv/artwork/{id}/meta ==========

    @Nested
    @DisplayName("GET /api/pixiv/artwork/{id}/meta")
    class ArtworkMetaTests {

        @BeforeEach
        void setUpSoloMode() {
            when(setupService.getMode()).thenReturn("solo");
        }

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
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok(body));

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
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok(body));

            mockMvc.perform(get("/api/pixiv/artwork/12345/meta"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authorId").doesNotExist())
                    .andExpect(jsonPath("$.tags", hasSize(0)));
        }
    }
}
