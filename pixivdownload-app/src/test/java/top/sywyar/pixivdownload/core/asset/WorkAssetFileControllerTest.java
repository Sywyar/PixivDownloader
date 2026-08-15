package top.sywyar.pixivdownload.core.asset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.GlobalExceptionHandler;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.core.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkAssetFileController 单元测试")
class WorkAssetFileControllerTest {
    @TempDir
    Path tempDir;

    @Mock
    private WorkAssetService workAssetService;
    @Mock
    private GuestAccessGuard guestAccessGuard;

    private MockMvc mockMvc;
    private Path pngFile;
    private Path webpFile;

    @BeforeEach
    void setUp() throws Exception {
        WorkAssetFileController controller =
                new WorkAssetFileController(workAssetService, guestAccessGuard);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(TestI18nBeans.appMessages()))
                .build();

        pngFile = tempDir.resolve("12345_p0.png");
        ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", pngFile.toFile());
        webpFile = tempDir.resolve("12345_p0.webp");
        Files.write(webpFile, new byte[]{1, 2, 3, 4});
    }

    // ========== GET /api/downloaded/thumbnail（二进制缩略图） ==========

    @Test
    @DisplayName("获取缩略图成功：返回可缓存的 image/png 文件流")
    void shouldReturnThumbnail() throws Exception {
        when(workAssetService.thumbnail(WorkType.ARTWORK, 12345L, 0))
                .thenReturn(Optional.of(new WorkAssetFile(0, pngFile, "png")));

        mockMvc.perform(get("/api/downloaded/thumbnail/12345/0"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(Files.readAllBytes(pngFile)))
                .andExpect(header().string("Cache-Control", "max-age=2592000, private"));
    }

    @Test
    @DisplayName("获取缩略图不存在应返回 404")
    void shouldReturn404ForMissingThumbnail() throws Exception {
        when(workAssetService.thumbnail(WorkType.ARTWORK, 99999L, 0)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/downloaded/thumbnail/99999/0"))
                .andExpect(status().isNotFound());
    }

    // ========== GET /api/downloaded/thumbnail-file（FileSystemResource 流） ==========

    @Test
    @DisplayName("获取缩略图文件成功：按扩展名返回 image/png 与文件流")
    void shouldReturnThumbnailFile() throws Exception {
        when(workAssetService.thumbnail(WorkType.ARTWORK, 12345L, 0))
                .thenReturn(Optional.of(new WorkAssetFile(0, pngFile, "png")));

        mockMvc.perform(get("/api/downloaded/thumbnail-file/12345/0"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    @DisplayName("缩略图文件不存在应返回 404")
    void shouldReturn404ForMissingThumbnailFile() throws Exception {
        when(workAssetService.thumbnail(WorkType.ARTWORK, 99999L, 0)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/downloaded/thumbnail-file/99999/0"))
                .andExpect(status().isNotFound());
    }

    // ========== GET /api/downloaded/rawfile（原始文件流） ==========

    @Test
    @DisplayName("获取原始文件成功：按扩展名返回 image/png 字节")
    void shouldReturnRawFile() throws Exception {
        when(workAssetService.rawFile(WorkType.ARTWORK, 12345L, 0))
                .thenReturn(Optional.of(new WorkAssetFile(0, pngFile, "png")));

        mockMvc.perform(get("/api/downloaded/rawfile/12345/0"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    @DisplayName("原始文件不存在应返回 404")
    void shouldReturn404ForMissingRawFile() throws Exception {
        when(workAssetService.rawFile(WorkType.ARTWORK, 99999L, 0)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/downloaded/rawfile/99999/0"))
                .andExpect(status().isNotFound());
    }

    // ========== GET /api/downloaded/image（二进制原图） ==========

    @Test
    @DisplayName("获取原始图片成功：不重编码，直接返回 image/png 文件流")
    void shouldReturnImage() throws Exception {
        when(workAssetService.rawFile(WorkType.ARTWORK, 12345L, 0))
                .thenReturn(Optional.of(new WorkAssetFile(0, pngFile, "png")));

        mockMvc.perform(get("/api/downloaded/image/12345/0"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(Files.readAllBytes(pngFile)));
    }

    @Test
    @DisplayName("获取 webp 原始图片：返回 image/webp 文件流")
    void shouldReturnWebpImageAsRawBytes() throws Exception {
        when(workAssetService.rawFile(WorkType.ARTWORK, 12345L, 0))
                .thenReturn(Optional.of(new WorkAssetFile(0, webpFile, "webp")));

        mockMvc.perform(get("/api/downloaded/image/12345/0"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/webp"))
                .andExpect(content().bytes(Files.readAllBytes(webpFile)));
    }

    @Test
    @DisplayName("获取原始图片不存在应返回 404")
    void shouldReturn404ForMissingImage() throws Exception {
        when(workAssetService.rawFile(WorkType.ARTWORK, 99999L, 0)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/downloaded/image/99999/0"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("每个端点都先做访客可见性校验")
    void shouldEnforceGuestVisibility() throws Exception {
        when(workAssetService.rawFile(WorkType.ARTWORK, 12345L, 0))
                .thenReturn(Optional.of(new WorkAssetFile(0, pngFile, "png")));

        mockMvc.perform(get("/api/downloaded/rawfile/12345/0")).andExpect(status().isOk());

        verify(guestAccessGuard).requireVisible(any(), eq(12345L));
    }
}
