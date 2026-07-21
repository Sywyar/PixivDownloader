package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.GlobalExceptionHandler;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.config.MultiModeSettings;
import top.sywyar.pixivdownload.download.ArtworkDownloadExecutor;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaReservation;
import top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaService;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;

import java.util.List;
import java.util.Locale;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DownloadTaskController 单元测试")
class DownloadTaskControllerTest {
    private static final AppMessages APP_MESSAGES = TestI18nBeans.appMessages();

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ArtworkDownloadExecutor artworkDownloadExecutor;
    @Mock
    private ApplicationModeProvider applicationModeProvider;
    @Mock
    private RequestOwnerIdentityResolver requestOwnerIdentityResolver;
    @Mock
    private VisitorDownloadQuotaService visitorDownloadQuotaService;
    @Mock
    private PixivDatabase pixivDatabase;

    @Mock
    private MultiModeSettings multiModeSettings;

    @BeforeEach
    void setUp() {
        lenient().when(requestOwnerIdentityResolver.resolve(any()))
                .thenReturn(RequestOwnerIdentity.adminScope());
        DownloadTaskController controller = new DownloadTaskController(
                artworkDownloadExecutor, applicationModeProvider, requestOwnerIdentityResolver,
                visitorDownloadQuotaService, multiModeSettings, pixivDatabase, APP_MESSAGES);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(APP_MESSAGES))
                .build();
    }

    // ========== POST /api/download/pixiv ==========

    @Nested
    @DisplayName("POST /api/download/pixiv")
    class DownloadPixivTests {

        @Test
        @DisplayName("合法请求应成功发起下载")
        void shouldStartDownload() throws Exception {
            when(applicationModeProvider.getMode()).thenReturn("solo");

            DownloadRequest request = new DownloadRequest();
            request.setArtworkId(12345L);
            request.setTitle("测试作品");
            request.setImageUrls(List.of("https://i.pximg.net/img/12345_p0.jpg"));

            mockMvc.perform(post("/api/download/pixiv")
                            .locale(Locale.SIMPLIFIED_CHINESE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("下载任务已开始处理"));
        }

        @Test
        @DisplayName("非法 URL（非 pximg.net）应返回 400")
        void shouldReturn400ForInvalidUrl() throws Exception {
            DownloadRequest request = new DownloadRequest();
            request.setArtworkId(12345L);
            request.setTitle("测试");
            request.setImageUrls(List.of("https://evil.com/malicious.jpg"));

            mockMvc.perform(post("/api/download/pixiv")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("多人模式配额超出时应返回 429")
        void shouldReturn429WhenQuotaExceeded() throws Exception {
            String ownerUuid = "12345678-1234-1234-1234-123456789abc";
            when(applicationModeProvider.getMode()).thenReturn("multi");
            when(requestOwnerIdentityResolver.resolve(any())).thenReturn(RequestOwnerIdentity.owner(ownerUuid));
            when(multiModeSettings.isQuotaEnabled()).thenReturn(true);
            when(multiModeSettings.getArchiveExpireMinutes()).thenReturn(60);
            when(multiModeSettings.getPostDownloadMode()).thenReturn("pack-and-delete");

            when(visitorDownloadQuotaService.checkAndReserve(anyString(), anyInt()))
                    .thenReturn(new VisitorDownloadQuotaReservation(false, 50, 50, 3600));
            when(visitorDownloadQuotaService.createArchive(anyString())).thenReturn("archive-token");

            DownloadRequest request = new DownloadRequest();
            request.setArtworkId(12345L);
            request.setTitle("测试");
            request.setImageUrls(List.of("https://i.pximg.net/img/12345_p0.jpg"));

            mockMvc.perform(post("/api/download/pixiv")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .header("X-User-UUID", ownerUuid))
                    .andExpect(status().is(429))
                    .andExpect(jsonPath("$.quotaExceeded").value(true))
                    .andExpect(jsonPath("$.archiveToken").value("archive-token"));
        }

        @Test
        @DisplayName("多人模式非管理员传入 collectionId 时应被清空")
        void shouldStripCollectionIdForNonAdminInMultiMode() throws Exception {
            when(applicationModeProvider.getMode()).thenReturn("multi");
            when(requestOwnerIdentityResolver.resolve(any()))
                    .thenReturn(RequestOwnerIdentity.owner("owner-a"));
            when(multiModeSettings.isQuotaEnabled()).thenReturn(false);

            DownloadRequest request = new DownloadRequest();
            request.setArtworkId(12345L);
            request.setTitle("测试");
            request.setImageUrls(List.of("https://i.pximg.net/img/12345_p0.jpg"));
            request.getOther().setCollectionId(42L);

            mockMvc.perform(post("/api/download/pixiv")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(artworkDownloadExecutor).downloadImages(
                    eq(12345L),
                    eq("测试"),
                    eq(List.of("https://i.pximg.net/img/12345_p0.jpg")),
                    eq("https://www.pixiv.net/"),
                    argThat(other -> other != null && other.getCollectionId() == null),
                    any(),
                    notNull()
            );
        }

        @Test
        @DisplayName("多人模式下已登录管理员应跳过配额检查")
        void shouldSkipQuotaCheckForAdminInMultiMode() throws Exception {
            when(applicationModeProvider.getMode()).thenReturn("multi");
            when(requestOwnerIdentityResolver.resolve(any())).thenReturn(RequestOwnerIdentity.adminScope());

            DownloadRequest request = new DownloadRequest();
            request.setArtworkId(12345L);
            request.setTitle("测试");
            request.setImageUrls(List.of("https://i.pximg.net/img/12345_p0.jpg"));
            request.getOther().setCollectionId(88L);

            mockMvc.perform(post("/api/download/pixiv")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(visitorDownloadQuotaService, never()).checkAndReserve(anyString(), anyInt());
            verify(artworkDownloadExecutor).downloadImages(
                    eq(12345L),
                    eq("测试"),
                    eq(List.of("https://i.pximg.net/img/12345_p0.jpg")),
                    eq("https://www.pixiv.net/"),
                    argThat(other -> other != null && Long.valueOf(88L).equals(other.getCollectionId())),
                    any(),
                    isNull()
            );
        }
    }
}
