package top.sywyar.pixivdownload.download;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ArtworkFileLocator 删除链路")
class ArtworkFileLocatorTest {

    @TempDir
    Path tempDir;

    private final PixivDatabase pixivDatabase = mock(PixivDatabase.class);
    private final DownloadConfig downloadConfig = mock(DownloadConfig.class);
    private final StagedFileDeletion stagedFileDeletion = new StagedFileDeletion(TestI18nBeans.appMessages());
    private final ArtworkFileLocator locator =
            new ArtworkFileLocator(pixivDatabase, downloadConfig, TestI18nBeans.appMessages(), stagedFileDeletion);

    @BeforeEach
    void isolateStagingDirectory() {
        // 原子删除的暂存目录走 RuntimeFiles，测试中指向临时 data 目录，避免污染工作目录
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, tempDir.resolve("rt-data").toString());
    }

    @AfterEach
    void clearStagingDirectoryProperty() {
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
    }

    private ArtworkRecord artwork(long id, String dir, int count) {
        return new ArtworkRecord(id, "t", dir, count, "jpg", 1000L,
                false, null, null, 0, false, null, null);
    }

    @Test
    @DisplayName("删除作品时一并清除 {id}.meta.json sidecar")
    void shouldDeleteSidecarWithArtwork() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("100"));
        Files.writeString(dir.resolve("100.jpg"), "img");
        Files.writeString(dir.resolve("100.meta.json"), "{\"schemaVersion\":1}");
        when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());
        when(pixivDatabase.getFileNameTemplate(anyLong())).thenReturn("{artwork_id}");

        assertTrue(locator.deleteArtworkFiles(artwork(100L, dir.toString(), 1)));
        assertFalse(Files.exists(dir.resolve("100.meta.json")), "sidecar 应随作品删除");
        assertFalse(Files.exists(dir.resolve("100.jpg")), "图片应被删除");
    }

    @Test
    @DisplayName("某文件删除失败时原子回滚：作品全部文件复原、返回 false")
    void shouldRollBackAllFilesWhenOneDeletionFails() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("300"));
        Path p0 = Files.writeString(dir.resolve("300_p0.jpg"), "p0");
        Path p1 = Files.writeString(dir.resolve("300_p1.jpg"), "p1");
        Path sidecar = Files.writeString(dir.resolve("300.meta.json"), "{\"schemaVersion\":1}");
        when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());
        when(pixivDatabase.getFileNameTemplate(anyLong())).thenReturn("{artwork_id}_p{page}");

        // 删 300_p1.jpg 时失败：无论枚举顺序如何，最终所有原文件都应被复原
        ArtworkFileLocator failingLocator = new ArtworkFileLocator(
                pixivDatabase, downloadConfig, TestI18nBeans.appMessages(),
                failOn(dir.resolve("300_p1.jpg")));

        assertFalse(failingLocator.deleteArtworkFiles(artwork(300L, dir.toString(), 2)),
                "删除失败应返回 false");
        assertTrue(Files.exists(p0), "p0 应被回滚复原");
        assertTrue(Files.exists(p1), "p1 应保留");
        assertTrue(Files.exists(sidecar), "sidecar 应被回滚复原");
        assertTrue(Files.isDirectory(dir), "回滚后作品目录不应被移除");
    }

    @Test
    @DisplayName("图库缩略图缓存删除失败按 best-effort 处理，不影响删除成败（不触发 409）")
    void galleryThumbnailCacheFailureDoesNotFailDeletion() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("200"));
        Files.writeString(dir.resolve("200.jpg"), "img");
        when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());
        when(pixivDatabase.getFileNameTemplate(anyLong())).thenReturn("{artwork_id}");

        ArtworkFileLocator failingCacheLocator = new ArtworkFileLocator(
                pixivDatabase, downloadConfig, TestI18nBeans.appMessages(), stagedFileDeletion) {
            @Override
            protected boolean deleteGalleryThumbnailCache(long artworkId) {
                return false; // 模拟可再生缓存删除失败
            }
        };

        assertTrue(failingCacheLocator.deleteArtworkFiles(artwork(200L, dir.toString(), 1)),
                "缓存删失败不应导致整体失败");
        assertFalse(Files.exists(dir.resolve("200.jpg")), "图片仍应被删除");
    }

    private static StagedFileDeletion failOn(Path poison) {
        Path normalizedPoison = poison.toAbsolutePath().normalize();
        return new StagedFileDeletion(TestI18nBeans.appMessages()) {
            @Override
            protected void deleteFile(Path original) throws java.io.IOException {
                if (original.toAbsolutePath().normalize().equals(normalizedPoison)) {
                    throw new java.io.IOException("simulated lock on " + original);
                }
                super.deleteFile(original);
            }
        };
    }
}
