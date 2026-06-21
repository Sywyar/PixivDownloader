package top.sywyar.pixivdownload.download.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.GlobalExceptionHandler;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.download.ArtworkDownloadExecutor;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService;
import top.sywyar.pixivdownload.setup.SetupService;

import java.util.Locale;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DownloadQueueController 单元测试")
class DownloadQueueControllerTest {
    private static final AppMessages APP_MESSAGES = TestI18nBeans.appMessages();

    private MockMvc mockMvc;

    @Mock
    private ArtworkDownloadExecutor artworkDownloadExecutor;
    @Mock
    private SetupService setupService;
    @Mock
    private NovelDownloadService novelDownloadService;

    @BeforeEach
    void setUp() {
        DownloadQueueController controller = new DownloadQueueController(
                artworkDownloadExecutor, setupService, novelDownloadService, APP_MESSAGES);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(APP_MESSAGES))
                .build();
    }

    // ========== POST /api/cancel/{artworkId} ==========

    @Test
    @DisplayName("POST /api/cancel/{artworkId} 应取消下载")
    void shouldCancelDownload() throws Exception {
        when(setupService.hasAdminScope(any())).thenReturn(true);
        mockMvc.perform(post("/api/cancel/12345").locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("下载任务已取消"));

        verify(artworkDownloadExecutor).cancelDownload(12345L);
    }

    @Test
    @DisplayName("POST /api/download/queue/clear 在多人模式下只清除当前 owner")
    void shouldClearOnlyCurrentOwnerDownloadsInMultiMode() throws Exception {
        String ownerUuid = "11111111-1111-1111-1111-111111111111";
        when(setupService.getMode()).thenReturn("multi");
        when(setupService.isAdminLoggedIn(any())).thenReturn(false);
        when(artworkDownloadExecutor.forceClearDownloadsForOwner(ownerUuid)).thenReturn(2);
        when(novelDownloadService.forceClearDownloadsForOwner(ownerUuid)).thenReturn(1);

        mockMvc.perform(post("/api/download/queue/clear")
                        .header("X-User-UUID", ownerUuid)
                        .locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(artworkDownloadExecutor).forceClearDownloadsForOwner(ownerUuid);
        verify(novelDownloadService).forceClearDownloadsForOwner(ownerUuid);
        verify(artworkDownloadExecutor, never()).forceClearDownloads();
        verify(novelDownloadService, never()).forceClearDownloads();
    }

    @Test
    @DisplayName("POST /api/download/queue/clear 在 solo 模式下清除全部后端状态")
    void shouldClearAllDownloadsInSoloMode() throws Exception {
        when(setupService.getMode()).thenReturn("solo");
        when(artworkDownloadExecutor.forceClearDownloads()).thenReturn(2);
        when(novelDownloadService.forceClearDownloads()).thenReturn(1);

        mockMvc.perform(post("/api/download/queue/clear").locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(artworkDownloadExecutor).forceClearDownloads();
        verify(novelDownloadService).forceClearDownloads();
        verify(artworkDownloadExecutor, never()).forceClearDownloadsForOwner(any());
        verify(novelDownloadService, never()).forceClearDownloadsForOwner(any());
    }
}
