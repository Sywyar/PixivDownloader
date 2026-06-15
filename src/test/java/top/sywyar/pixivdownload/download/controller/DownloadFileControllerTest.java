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
import top.sywyar.pixivdownload.download.response.ImageResponse;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DownloadFileController 单元测试")
class DownloadFileControllerTest {
    private static final AppMessages APP_MESSAGES = TestI18nBeans.appMessages();

    private MockMvc mockMvc;

    @Mock
    private DownloadService downloadService;
    @Mock
    private top.sywyar.pixivdownload.setup.guest.GuestAccessGuard guestAccessGuard;

    @BeforeEach
    void setUp() {
        DownloadFileController controller = new DownloadFileController(downloadService, guestAccessGuard);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(APP_MESSAGES))
                .build();
    }

    // ========== GET /api/downloaded/thumbnail & image ==========

    @Nested
    @DisplayName("GET /api/downloaded/thumbnail & image")
    class ThumbnailAndImageTests {

        @Test
        @DisplayName("获取缩略图成功")
        void shouldReturnThumbnail() throws Exception {
            ImageResponse imageResponse = new ImageResponse(true, "base64data", "jpg", 100, 200, 150, "成功");
            when(downloadService.getImageResponse(12345L, 0, true)).thenReturn(imageResponse);

            mockMvc.perform(get("/api/downloaded/thumbnail/12345/0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.image").value("base64data"));
        }

        @Test
        @DisplayName("获取缩略图不存在应返回 404")
        void shouldReturn404ForMissingThumbnail() throws Exception {
            when(downloadService.getImageResponse(99999L, 0, true)).thenReturn(null);

            mockMvc.perform(get("/api/downloaded/thumbnail/99999/0"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("获取原始图片成功")
        void shouldReturnImage() throws Exception {
            ImageResponse imageResponse = new ImageResponse(true, "base64data", "png", 200, 400, 300, "成功");
            when(downloadService.getImageResponse(12345L, 0, false)).thenReturn(imageResponse);

            mockMvc.perform(get("/api/downloaded/image/12345/0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }
}
