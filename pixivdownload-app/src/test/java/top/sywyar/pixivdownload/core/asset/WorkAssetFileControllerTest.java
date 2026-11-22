package top.sywyar.pixivdownload.core.asset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.GlobalExceptionHandler;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.download.ArtworkFileService;
import top.sywyar.pixivdownload.core.download.LocalWorkAssetService;
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

    @Test
    void rawFileServesCompleteRangesAndAnEmptyUnsatisfiableResponse() throws Exception {
        when(workAssetService.rawFile(WorkType.ARTWORK, 12345L, 0))
                .thenReturn(Optional.of(new WorkAssetFile(0, pngFile, "png")));
        var path = "/api/downloaded/rawfile/12345/0";
        byte[] bytes = Files.readAllBytes(pngFile);
        mockMvc.perform(get(path).header("Range", "bytes=0-9"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-9/" + bytes.length))
                .andExpect(content().bytes(java.util.Arrays.copyOf(bytes, 10)));
        var multipart = mockMvc.perform(get(path).header("Range", "bytes=0-1,4-6"))
                .andExpect(status().isPartialContent()).andReturn().getResponse();
        var boundary = MediaType.parseMediaType(multipart.getContentType()).getParameter("boundary");
        var body = multipart.getContentAsString(java.nio.charset.StandardCharsets.ISO_8859_1);
        org.assertj.core.api.Assertions.assertThat(body).contains("Content-Range: bytes 0-1/" + bytes.length,
                "Content-Range: bytes 4-6/" + bytes.length, "--" + boundary + "--");
        String length = multipart.getHeader("Content-Length");
        if (length != null) org.assertj.core.api.Assertions.assertThat(Long.parseLong(length)).isEqualTo(multipart.getContentAsByteArray().length);
        mockMvc.perform(get(path).header("Range", "bytes=" + bytes.length + "-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */" + bytes.length))
                .andExpect(content().bytes(new byte[0]));
    }

    @Test
    @DisplayName("ZIP 画廊图片使用首帧，原文件端点仍返回归档字节")
    void previewsArchiveWithoutChangingOriginalDownload() throws Exception {
        Path zip = tempDir.resolve("animation.zip");
        Files.write(zip, new byte[]{80,75,3,4});
        when(workAssetService.rawFile(WorkType.ARTWORK, 12345L, 0)).thenReturn(Optional.of(new WorkAssetFile(0, zip, "zip")));
        when(workAssetService.previewFile(WorkType.ARTWORK, 12345L, 0)).thenReturn(Optional.of(new WorkAssetFile(0, zip, "zip")));
        when(workAssetService.thumbnail(WorkType.ARTWORK, 12345L, 0)).thenReturn(Optional.of(new WorkAssetFile(0, pngFile, "png")));
        mockMvc.perform(get("/api/downloaded/image/12345/0")).andExpect(status().isOk())
                .andExpect(content().contentType("image/png")).andExpect(content().bytes(Files.readAllBytes(pngFile)));
        mockMvc.perform(get("/api/downloaded/rawfile/12345/0")).andExpect(status().isOk())
                .andExpect(content().contentType("application/zip")).andExpect(content().bytes(Files.readAllBytes(zip)));
    }

    @Test
    @DisplayName("导出保留的 ZIP 或转换产物，预览优先动画且允许回退到缩略图")
    void resolvesExportAndPreviewThroughTheAssetServices() throws Exception {
        var database = mock(PixivDatabase.class);
        var record = new ArtworkRecord(42L, "animation", tempDir.toString(), 1, "webp,zip",
                1000L, false, null, null, 0, false, 1L, null);
        when(database.getArtwork(42L)).thenReturn(record);
        when(database.getArtworkFileNames(42L)).thenReturn(java.util.List.of("animation"));
        var media = mock(ArtworkMediaStore.class);
        when(media.find(42L, 0)).thenReturn(Optional.of(new ArtworkMediaManifest("zip", java.util.List.of("webp", "zip"))));
        var external = mock(ExternalWorkFiles.class);
        var locator = new ArtworkFileLocator(database, null, TestI18nBeans.appMessages(), null, media, external);
        var assets = new LocalWorkAssetService(new ArtworkFileService(database, locator, null), locator,
                database, null, null, TestI18nBeans.appMessages(), null, external, null);
        var server = MockMvcBuilders.standaloneSetup(new WorkAssetFileController(assets, guestAccessGuard)).build();
        Path archive = Files.write(tempDir.resolve("animation.zip"), new byte[]{80, 75, 3, 4});
        Path animation = Files.copy(webpFile, tempDir.resolve("animation.webp"));
        Path thumbnail = Files.write(tempDir.resolve("animation_thumb.jpg"), new byte[]{1, 2});

        server.perform(get("/api/downloaded/rawfile/42/0")).andExpect(status().isOk())
                .andExpect(content().contentType("application/zip")).andExpect(content().bytes(Files.readAllBytes(archive)));
        server.perform(get("/api/downloaded/image/42/0")).andExpect(status().isOk())
                .andExpect(content().contentType("image/webp")).andExpect(content().bytes(Files.readAllBytes(animation)));
        Files.delete(archive);
        server.perform(get("/api/downloaded/rawfile/42/0")).andExpect(status().isOk())
                .andExpect(content().bytes(Files.readAllBytes(animation)));
        Files.delete(animation);
        server.perform(get("/api/downloaded/rawfile/42/0")).andExpect(status().isNotFound());
        server.perform(get("/api/downloaded/image/42/0")).andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg")).andExpect(content().bytes(Files.readAllBytes(thumbnail)));
        server.perform(get("/api/downloaded/rawfile/42/1")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"thumbnail", "thumbnail-file"})
    @DisplayName("两个缩略图端点返回相同的可缓存图片字节")
    void shouldReturnThumbnail(String endpoint) throws Exception {
        when(workAssetService.thumbnail(WorkType.ARTWORK, 12345L, 0, 512))
                .thenReturn(Optional.of(new WorkAssetFile(0, pngFile, "png")));

        mockMvc.perform(get("/api/downloaded/{endpoint}/12345/0", endpoint))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(Files.readAllBytes(pngFile)))
                .andExpect(header().string("Cache-Control", "max-age=2592000, private"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"thumbnail", "thumbnail-file"})
    @DisplayName("获取缩略图不存在应返回 404")
    void shouldReturn404ForMissingThumbnail(String endpoint) throws Exception {
        when(workAssetService.thumbnail(WorkType.ARTWORK, 99999L, 0, 512)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/downloaded/{endpoint}/99999/0", endpoint))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"thumbnail", "thumbnail-file"})
    @DisplayName("方形封面沿用可见性检查并传递尺寸，拒绝访问时不生成缓存")
    void servesCoverOnlyAfterVisibilityCheck(String endpoint) throws Exception {
        when(workAssetService.coverThumbnail(WorkType.ARTWORK, 12345L, 0, 256))
                .thenReturn(Optional.of(new WorkAssetFile(0, pngFile, "png")));
        mockMvc.perform(get("/api/downloaded/{endpoint}/12345/0", endpoint).param("cover", "true").param("size", "256"))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));
        var order = inOrder(guestAccessGuard, workAssetService);
        order.verify(guestAccessGuard).requireVisible(any(), eq(12345L));
        order.verify(workAssetService).coverThumbnail(WorkType.ARTWORK, 12345L, 0, 256);
        reset(workAssetService);
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN))
                .when(guestAccessGuard).requireVisible(any(), eq(12345L));
        mockMvc.perform(get("/api/downloaded/{endpoint}/12345/0", endpoint).param("cover", "true"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(workAssetService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"thumbnail", "thumbnail-file"})
    @DisplayName("缩略图请求把展示尺寸传给资产服务且保留可见性检查")
    void forwardsPreviewSize(String endpoint) throws Exception {
        when(workAssetService.thumbnail(WorkType.ARTWORK, 12345L, 0, 300))
                .thenReturn(Optional.of(new WorkAssetFile(0, pngFile, "png")));
        mockMvc.perform(get("/api/downloaded/{endpoint}/12345/0", endpoint).param("size", "300"))
                .andExpect(status().isOk());
        verify(guestAccessGuard).requireVisible(any(), eq(12345L));
        verify(workAssetService).thumbnail(WorkType.ARTWORK, 12345L, 0, 300);
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "webp"})
    @DisplayName("图片预览与导出保留静态图的字节与媒体类型")
    void streamsStaticImageBytes(String extension) throws Exception {
        Path path = extension.equals("png") ? pngFile : webpFile;
        var file = Optional.of(new WorkAssetFile(0, path, extension));
        when(workAssetService.rawFile(WorkType.ARTWORK, 12345L, 0)).thenReturn(file);
        when(workAssetService.previewFile(WorkType.ARTWORK, 12345L, 0)).thenReturn(file);
        for (String endpoint : java.util.List.of("image", "rawfile")) {
            mockMvc.perform(get("/api/downloaded/{endpoint}/12345/0", endpoint))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("image/" + extension))
                    .andExpect(content().bytes(Files.readAllBytes(path)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"image", "rawfile"})
    @DisplayName("获取原始图片不存在应返回 404")
    void shouldReturn404ForMissingImage(String endpoint) throws Exception {
        mockMvc.perform(get("/api/downloaded/{endpoint}/99999/0", endpoint))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"thumbnail", "thumbnail-file", "image", "rawfile"})
    @DisplayName("每个端点拒绝不可见作品且不读取或生成文件")
    void shouldEnforceGuestVisibility(String endpoint) throws Exception {
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN))
                .when(guestAccessGuard).requireVisible(any(), eq(12345L));

        mockMvc.perform(get("/api/downloaded/{endpoint}/12345/0", endpoint)).andExpect(status().isForbidden());
        verifyNoInteractions(workAssetService);
    }
}
