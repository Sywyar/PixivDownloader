package top.sywyar.pixivdownload.download.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.GlobalExceptionHandler;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.download.DownloadService;
import top.sywyar.pixivdownload.download.DownloadStatus;
import top.sywyar.pixivdownload.setup.SetupService;

import java.util.List;
import java.util.Locale;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DownloadStatusController 单元测试")
class DownloadStatusControllerTest {
    private static final AppMessages APP_MESSAGES = TestI18nBeans.appMessages();

    private MockMvc mockMvc;

    @Mock
    private DownloadService downloadService;
    @Mock
    private SetupService setupService;

    @BeforeEach
    void setUp() {
        DownloadStatusController controller = new DownloadStatusController(
                downloadService, setupService, APP_MESSAGES);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(APP_MESSAGES))
                .build();
    }

    // ========== GET /api/download/status ==========

    @Test
    @DisplayName("GET /api/download/status 应返回服务状态")
    void shouldReturnServiceStatus() throws Exception {
        mockMvc.perform(get("/api/download/status").locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("服务运行正常"));
    }

    // ========== GET /api/download/status/{artworkId} ==========

    @Nested
    @DisplayName("GET /api/download/status/{artworkId}")
    class GetDownloadStatusTests {

        @Test
        @DisplayName("已存在的下载任务应返回状态")
        void shouldReturnDownloadStatus() throws Exception {
            DownloadStatus status = new DownloadStatus(12345L, "测试作品", 5);
            status.setDownloadedCount(3);
            status.setCurrentImageIndex(2);
            status.setDownloadPath("/path/to/download");
            when(setupService.hasAdminScope(any())).thenReturn(true);
            when(downloadService.getDownloadStatus(12345L)).thenReturn(status);

            mockMvc.perform(get("/api/download/status/12345"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.artworkId").value(12345))
                    .andExpect(jsonPath("$.totalImages").value(5))
                    .andExpect(jsonPath("$.downloadedCount").value(3));
        }

        @Test
        @DisplayName("不存在的下载任务应返回未找到")
        void shouldReturnNotFoundStatus() throws Exception {
            when(setupService.hasAdminScope(any())).thenReturn(true);
            when(downloadService.getDownloadStatus(99999L)).thenReturn(null);

            mockMvc.perform(get("/api/download/status/99999").locale(Locale.SIMPLIFIED_CHINESE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("未找到该作品的下载状态"));
        }
    }

    // ========== GET /api/download/status/active ==========

    @Test
    @DisplayName("GET /api/download/status/active 应返回活跃下载列表")
    void shouldReturnActiveDownloads() throws Exception {
        when(setupService.hasAdminScope(any())).thenReturn(true);
        when(downloadService.getDownloadStatus()).thenReturn(List.of(1L, 2L, 3L));

        mockMvc.perform(get("/api/download/status/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artworkIds", hasSize(3)))
                .andExpect(jsonPath("$.artworkIds[0]").value(1))
                .andExpect(jsonPath("$.artworkIds[1]").value(2))
                .andExpect(jsonPath("$.artworkIds[2]").value(3));
    }
}
