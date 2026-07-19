package top.sywyar.pixivdownload.novel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import top.sywyar.pixivdownload.GlobalExceptionHandler;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessDecision;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessOutcome;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessPolicy;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;

import java.net.URI;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("NovelPixivProxyController 单元测试")
class NovelPixivProxyControllerTest {

    private static final MessageResolver APP_MESSAGES = TestI18nBeans.messageResolver();
    private static final WorkVisibilityScope VISIBILITY_SCOPE = WorkVisibilityScope.unrestricted();

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private PixivAjaxClient pixivAjaxClient;
    @Mock
    private PixivProxyAccessPolicy accessPolicy;
    @Mock
    private RequestOwnerIdentityResolver requestOwnerIdentityResolver;
    @Mock
    private WorkVisibilityService workVisibilityService;

    @BeforeEach
    void setUp() {
        lenient().when(requestOwnerIdentityResolver.resolveExistingOwnerUuid(any())).thenReturn(Optional.empty());
        lenient().when(accessPolicy.evaluate(any(), anyBoolean())).thenReturn(
                new PixivProxyAccessDecision(PixivProxyAccessOutcome.ALLOWED, null, 0, 0));
        NovelPixivProxyController controller = new NovelPixivProxyController(
                objectMapper, pixivAjaxClient, accessPolicy, requestOwnerIdentityResolver,
                workVisibilityService, APP_MESSAGES);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new FixedVisibilityScopeResolver())
                .setControllerAdvice(new GlobalExceptionHandler(TestI18nBeans.appMessages()))
                .build();
    }

    @Nested
    @DisplayName("GET /api/pixiv/novel-search")
    class NovelSearchTests {

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
            when(pixivAjaxClient.get(any(URI.class), any()))
                    .thenReturn(PIXIV_NOVEL_SEARCH_RESPONSE);

            mockMvc.perform(get("/api/pixiv/novel-search")
                            .param("word", "初音ミク"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(123))
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].id").value("789012"))
                    .andExpect(jsonPath("$.items[0].title").value("Test Novel"))
                    .andExpect(jsonPath("$.items[0].xRestrict").value(1))
                    .andExpect(jsonPath("$.items[0].xrestrict").doesNotExist())
                    .andExpect(jsonPath("$.items[0].bookmarkCount").value(987))
                    .andExpect(jsonPath("$.items[0].wordCount").value(1200))
                    .andExpect(jsonPath("$.items[0].textLength").value(3600))
                    .andExpect(jsonPath("$.items[0].isOriginal").value(true));
        }

        @Test
        @DisplayName("通用取得凭证应作为 Pixiv Cookie 转发")
        void shouldForwardGenericAcquisitionCredential() throws Exception {
            when(pixivAjaxClient.get(any(URI.class), any()))
                    .thenReturn(PIXIV_NOVEL_SEARCH_RESPONSE);

            mockMvc.perform(get("/api/pixiv/novel-search")
                            .param("word", "miku")
                            .header(AcquisitionCredentialResolver.HEADER_NAME, " generic-cookie "))
                    .andExpect(status().isOk());

            verify(pixivAjaxClient).get(any(URI.class), eq("generic-cookie"));
        }

        @Test
        @DisplayName("旧 Pixiv 凭证头仍应作为 Cookie 转发")
        void shouldForwardLegacyPixivCredential() throws Exception {
            when(pixivAjaxClient.get(any(URI.class), any()))
                    .thenReturn(PIXIV_NOVEL_SEARCH_RESPONSE);

            mockMvc.perform(get("/api/pixiv/novel-search")
                            .param("word", "miku")
                            .header("X-Pixiv-Cookie", " legacy-cookie "))
                    .andExpect(status().isOk());

            verify(pixivAjaxClient).get(any(URI.class), eq("legacy-cookie"));
        }

        @Test
        @DisplayName("通用与旧 Pixiv 凭证冲突时应返回 400")
        void shouldRejectConflictingPixivCredentials() throws Exception {
            mockMvc.perform(get("/api/pixiv/novel-search")
                            .param("word", "miku")
                            .header(AcquisitionCredentialResolver.HEADER_NAME, "generic-cookie")
                            .header("X-Pixiv-Cookie", "legacy-cookie"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Conflicting acquisition credential headers"));

            verifyNoInteractions(pixivAjaxClient);
        }
    }

    @Nested
    @DisplayName("GET /api/pixiv/novel/{id}/meta")
    class NovelMetaTests {

        @Test
        @DisplayName("小说详情应透传并规范输出年龄分级")
        void shouldReturnCanonicalNovelAgeRating() throws Exception {
            String body = """
                    {
                      "error": false,
                      "body": {
                        "title": "R18G Novel",
                        "xRestrict": 2,
                        "aiType": 2,
                        "isOriginal": true,
                        "content": "body"
                      }
                    }
                    """;
            when(pixivAjaxClient.get(any(URI.class), any())).thenReturn(body);

            mockMvc.perform(get("/api/pixiv/novel/789012/meta"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.xRestrict").value(2))
                    .andExpect(jsonPath("$.xrestrict").doesNotExist())
                    .andExpect(jsonPath("$.isAi").value(true))
                    .andExpect(jsonPath("$.ai").doesNotExist())
                    .andExpect(jsonPath("$.isOriginal").value(true))
                    .andExpect(jsonPath("$.original").doesNotExist());

            verify(workVisibilityService).requireVisible(VISIBILITY_SCOPE, WorkType.NOVEL, 789012L);
        }
    }

    @Nested
    @DisplayName("GET /api/pixiv/novel/{id}/bookmark-count")
    class NovelBookmarkCountTests {

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
            when(pixivAjaxClient.get(any(URI.class), any())).thenReturn(body);

            mockMvc.perform(get("/api/pixiv/novel/789012/bookmark-count"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bookmarkCount").value(4567))
                    .andExpect(jsonPath("$.content").doesNotExist());
        }
    }

    @Nested
    @DisplayName("代理访问判定")
    class ProxyAccessTests {

        @Test
        @DisplayName("缺少现有 owner UUID 时保持 401 error 响应形状")
        void shouldReturnOwnerRequiredResponse() throws Exception {
            when(accessPolicy.evaluate(any(), anyBoolean())).thenReturn(
                    new PixivProxyAccessDecision(
                            PixivProxyAccessOutcome.OWNER_REQUIRED, "缺少用户 UUID", 0, 0));

            mockMvc.perform(get("/api/pixiv/novel-search").param("word", "miku"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("缺少用户 UUID"))
                    .andExpect(jsonPath("$.maxRequests").doesNotExist());

            verifyNoInteractions(pixivAjaxClient);
        }

        @Test
        @DisplayName("配额耗尽时保持 429 限流详情响应形状")
        void shouldReturnRateLimitResponse() throws Exception {
            when(accessPolicy.evaluate(any(), anyBoolean())).thenReturn(
                    new PixivProxyAccessDecision(
                            PixivProxyAccessOutcome.RATE_LIMITED, "请求次数已达上限", 12, 6));

            mockMvc.perform(get("/api/pixiv/novel-search").param("word", "miku"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error").value("请求次数已达上限"))
                    .andExpect(jsonPath("$.maxRequests").value(12))
                    .andExpect(jsonPath("$.windowHours").value(6));

            verifyNoInteractions(pixivAjaxClient);
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
