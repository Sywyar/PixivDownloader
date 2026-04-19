package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import top.sywyar.pixivdownload.quota.MultiModeConfig;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.setup.SetupService;

import java.net.URI;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PixivProxyController 单元测试")
class PixivProxyControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private RestTemplate restTemplate;
    @Mock
    private SetupService setupService;
    @Mock
    private UserQuotaService userQuotaService;

    private MultiModeConfig multiModeConfig;

    @BeforeEach
    void setUp() {
        multiModeConfig = new MultiModeConfig();
        PixivProxyController controller = new PixivProxyController(
                objectMapper, restTemplate, setupService, userQuotaService, multiModeConfig);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
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
                          "userName": "TestArtist"
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
                    .andExpect(jsonPath("$.items[0].userName").value("TestArtist"));
        }

        @Test
        @DisplayName("非法 order 参数应返回 400")
        void shouldRejectInvalidOrder() throws Exception {
            mockMvc.perform(get("/api/pixiv/search")
                            .param("word", "test")
                            .param("order", "invalid_order"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(containsString("order")));
        }

        @Test
        @DisplayName("非法 mode 参数应返回 400")
        void shouldRejectInvalidMode() throws Exception {
            mockMvc.perform(get("/api/pixiv/search")
                            .param("word", "test")
                            .param("mode", "adult"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(containsString("mode")));
        }

        @Test
        @DisplayName("非法 sMode 参数应返回 400")
        void shouldRejectInvalidSMode() throws Exception {
            mockMvc.perform(get("/api/pixiv/search")
                            .param("word", "test")
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

            mockMvc.perform(get("/api/pixiv/search").param("word", "test"))
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
}
