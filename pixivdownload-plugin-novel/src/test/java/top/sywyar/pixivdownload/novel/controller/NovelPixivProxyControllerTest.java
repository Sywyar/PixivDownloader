package top.sywyar.pixivdownload.novel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.GlobalExceptionHandler;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxProxyClient;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessGuard;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.setup.SetupService;

import java.net.URI;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("NovelPixivProxyController 单元测试")
class NovelPixivProxyControllerTest {

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

    @BeforeEach
    void setUp() {
        MultiModeConfig multiModeConfig = new MultiModeConfig();
        PixivProxyAccessGuard accessGuard = new PixivProxyAccessGuard(
                setupService, userQuotaService, multiModeConfig, APP_MESSAGES);
        NovelPixivProxyController controller = new NovelPixivProxyController(
                objectMapper, new PixivAjaxProxyClient(restTemplate), accessGuard,
                guestAccessGuard, APP_MESSAGES);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(APP_MESSAGES))
                .build();
    }

    @Nested
    @DisplayName("GET /api/pixiv/novel-search")
    class NovelSearchTests {

        @BeforeEach
        void setUpSoloMode() {
            when(setupService.getMode()).thenReturn("solo");
        }

        private static final String PIXIV_NOVEL_SEARCH_RESPONSE = """
                {
                  "error": false,
                  "body": {
                    "novel": {
                      "data": [
                        {
                          "id": "789012",
                          "title": "Test Novel",
                          "xRestrict": 1,
                          "aiType": 0,
                          "bookmarkCount": 987,
                          "wordCount": 1200,
                          "characterCount": 3600,
                          "userId": "8888",
                          "userName": "TestWriter",
                          "url": "https://i.pximg.net/c/250x250_80_a2/novel-cover-master/img/2024/01/01/789012.jpg",
                          "isOriginal": true,
                          "tags": ["小説", "テスト"]
                        }
                      ],
                      "total": 123
                    }
                  }
                }
                """;

        @Test
        @DisplayName("小说搜索结果应透传 bookmarkCount")
        void shouldReturnNovelSearchBookmarkCount() throws Exception {
            when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(byte[].class)))
                    .thenReturn(ResponseEntity.ok(PIXIV_NOVEL_SEARCH_RESPONSE.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            mockMvc.perform(get("/api/pixiv/novel-search")
                            .param("word", "初音ミク"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(123))
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].id").value("789012"))
                    .andExpect(jsonPath("$.items[0].title").value("Test Novel"))
                    .andExpect(jsonPath("$.items[0].bookmarkCount").value(987))
                    .andExpect(jsonPath("$.items[0].wordCount").value(1200))
                    .andExpect(jsonPath("$.items[0].textLength").value(3600))
                    .andExpect(jsonPath("$.items[0].isOriginal").value(true));
        }
    }

    @Nested
    @DisplayName("GET /api/pixiv/novel/{id}/bookmark-count")
    class NovelBookmarkCountTests {

        @BeforeEach
        void setUpSoloMode() {
            when(setupService.getMode()).thenReturn("solo");
        }

        @Test
        @DisplayName("应只返回小说收藏数")
        void shouldReturnNovelBookmarkCountOnly() throws Exception {
            String body = """
                    {
                      "error": false,
                      "body": {
                        "title": "Novel",
                        "bookmarkCount": 4567,
                        "content": "large novel body"
                      }
                    }
                    """;
            when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(byte[].class)))
                    .thenReturn(ResponseEntity.ok(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            mockMvc.perform(get("/api/pixiv/novel/789012/bookmark-count"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bookmarkCount").value(4567))
                    .andExpect(jsonPath("$.content").doesNotExist());
        }
    }
}
