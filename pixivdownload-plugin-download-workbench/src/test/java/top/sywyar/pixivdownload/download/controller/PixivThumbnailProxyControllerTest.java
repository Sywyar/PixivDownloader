package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessPolicy;
import top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFetchException;
import top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFailure;
import top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFetcher;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.download.testsupport.WorkbenchTestMessages;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;

import java.net.URI;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Pixiv 缩略图代理控制器")
class PixivThumbnailProxyControllerTest {

    private static final MessageResolver MESSAGES = WorkbenchTestMessages.messages();
    private static final byte[] DUMMY_IMAGE = new byte[]{(byte) 0xFF, (byte) 0xD8, 0x01, 0x02};
    private static final String VALID_URL =
            "https://i.pximg.net/c/250x250_80_a2/img-master/img/2024/01/01/123456_p0_master1200.jpg";

    private MockMvc mockMvc;

    @Mock
    private PixivThumbnailFetcher pixivThumbnailFetcher;
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
        ObjectMapper objectMapper = new ObjectMapper();
        PixivFetchService pixivFetchService = new PixivFetchService(pixivAjaxClient, objectMapper);
        PixivProxyController controller = new PixivProxyController(
                objectMapper,
                pixivThumbnailFetcher,
                pixivFetchService,
                pixivProxyAccessPolicy,
                requestOwnerIdentityResolver,
                workVisibilityService,
                MESSAGES
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("合法 pximg.net URL 应代理图片并返回 200")
    void shouldProxyValidPximgUrl() throws Exception {
        when(pixivThumbnailFetcher.fetch(URI.create(VALID_URL))).thenReturn(DUMMY_IMAGE);

        mockMvc.perform(get("/api/pixiv/thumbnail-proxy").param("url", VALID_URL))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=3600, public"))
                .andExpect(content().bytes(DUMMY_IMAGE));
    }

    @Test
    @DisplayName("非 pximg.net 域名应返回 400（SSRF 防护）")
    void shouldRejectNonPximgUrl() throws Exception {
        rejectTarget("https://evil.com/malicious.jpg");

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

        verifyNoInteractions(pixivThumbnailFetcher);
    }

    @Test
    @DisplayName("i.pximg.net 子域名应被允许")
    void shouldAllowPximgSubdomain() throws Exception {
        String source = "https://i.pximg.net/img-original/img/2024/01/01/123456_p0.jpg";
        when(pixivThumbnailFetcher.fetch(URI.create(source))).thenReturn(DUMMY_IMAGE);

        mockMvc.perform(get("/api/pixiv/thumbnail-proxy").param("url", source))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("embed.pixiv.net 珍藏集封面域应被允许")
    void shouldAllowPixivEmbedHost() throws Exception {
        String source = "https://embed.pixiv.net/decorate.php?illust_id=123456";
        when(pixivThumbnailFetcher.fetch(URI.create(source))).thenReturn(DUMMY_IMAGE);

        mockMvc.perform(get("/api/pixiv/thumbnail-proxy").param("url", source))
                .andExpect(status().isOk())
                .andExpect(content().bytes(DUMMY_IMAGE));
    }

    @Test
    @DisplayName("pixiv.net 域名（非 pximg.net）应返回 400")
    void shouldRejectPixivNetDomain() throws Exception {
        String source = "https://www.pixiv.net/some/image.jpg";
        rejectTarget(source);

        mockMvc.perform(get("/api/pixiv/thumbnail-proxy").param("url", source))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("http 协议的 pximg.net URL 应返回 400（仅允许 https）")
    void shouldRejectHttpScheme() throws Exception {
        String source = "http://i.pximg.net/img-original/img/2024/01/01/123456_p0.jpg";
        rejectTarget(source);

        mockMvc.perform(get("/api/pixiv/thumbnail-proxy").param("url", source))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("客户端 Cookie 不进入缩略图传输端口")
    void shouldNotPassCookieToThumbnailPort() throws Exception {
        when(pixivThumbnailFetcher.fetch(URI.create(VALID_URL))).thenReturn(DUMMY_IMAGE);

        mockMvc.perform(get("/api/pixiv/thumbnail-proxy")
                        .param("url", VALID_URL)
                        .header("X-Pixiv-Cookie", "PHPSESSID=12345_secret; other=value"))
                .andExpect(status().isOk());

        verify(pixivThumbnailFetcher).fetch(URI.create(VALID_URL));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    @DisplayName("上游鉴权状态应保持 502 与 Cookie 失效提示")
    void shouldProjectUpstreamAuthenticationFailure(int upstreamStatus) throws Exception {
        failTarget(PixivThumbnailFailure.HTTP_STATUS, upstreamStatus);

        mockMvc.perform(get("/api/pixiv/thumbnail-proxy").param("url", VALID_URL))
                .andExpect(status().isBadGateway())
                .andExpect(header().doesNotExist("Cache-Control"))
                .andExpect(jsonPath("$.error").value(
                        MESSAGES.get("pixiv.proxy.thumbnail.upstream.unauthorized")));
    }

    @Test
    @DisplayName("其它上游 HTTP 状态应保持 502 并显示状态码")
    void shouldProjectOtherUpstreamHttpFailure() throws Exception {
        failTarget(PixivThumbnailFailure.HTTP_STATUS, 404);

        mockMvc.perform(get("/api/pixiv/thumbnail-proxy").param("url", VALID_URL))
                .andExpect(status().isBadGateway())
                .andExpect(header().doesNotExist("Cache-Control"))
                .andExpect(jsonPath("$.error").value(
                        MESSAGES.get("pixiv.proxy.thumbnail.upstream.failed", 404)));
    }

    @Test
    @DisplayName("传输失败应保持 500 且不暴露客户端实现细节")
    void shouldProjectTransportFailureWithoutDetails() throws Exception {
        failTarget(PixivThumbnailFailure.TRANSPORT, 0);

        mockMvc.perform(get("/api/pixiv/thumbnail-proxy").param("url", VALID_URL))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist("Cache-Control"))
                .andExpect(jsonPath("$.error").value(
                        MESSAGES.get("pixiv.proxy.thumbnail.transport.failed")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("https://i.pximg.net"))));
    }

    private void rejectTarget(String source) {
        when(pixivThumbnailFetcher.fetch(URI.create(source)))
                .thenThrow(new PixivThumbnailFetchException(
                        PixivThumbnailFailure.INVALID_TARGET,
                        0
                ));
    }

    private void failTarget(PixivThumbnailFailure failure, int statusCode) {
        when(pixivThumbnailFetcher.fetch(URI.create(VALID_URL)))
                .thenThrow(new PixivThumbnailFetchException(failure, statusCode));
    }
}
