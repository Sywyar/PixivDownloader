package top.sywyar.pixivdownload.download;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.plugin.api.LocalWorkAsset;
import top.sywyar.pixivdownload.plugin.api.WorkAssetFile;
import top.sywyar.pixivdownload.plugin.api.WorkType;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalWorkAssetServiceTest {

    private DownloadService downloadService;
    private ArtworkFileLocator artworkFileLocator;
    private PixivDatabase pixivDatabase;
    private LocalWorkAssetService service;

    @BeforeEach
    void setUp() {
        downloadService = mock(DownloadService.class);
        artworkFileLocator = mock(ArtworkFileLocator.class);
        pixivDatabase = mock(PixivDatabase.class);
        service = new LocalWorkAssetService(downloadService, artworkFileLocator, pixivDatabase);
    }

    private static ArtworkRecord artwork(long artworkId, int count) {
        return new ArtworkRecord(artworkId, "title", "folder/" + artworkId, count, "jpg",
                1000L, false, null, null, 0, false, 1L, null);
    }

    @Test
    @DisplayName("thumbnail 委托 DownloadService 缩略图缓存并映射为 WorkAssetFile")
    void thumbnailDelegatesToThumbnailCache() throws Exception {
        Path cachePath = Path.of("data", "gallery_thumbs", "42", "p1.jpg");
        when(downloadService.getThumbnailFile(42L, 1))
                .thenReturn(new DownloadService.ThumbnailFile(cachePath, "jpg"));

        Optional<WorkAssetFile> thumbnail = service.thumbnail(WorkType.ARTWORK, 42L, 1);

        assertTrue(thumbnail.isPresent());
        assertEquals(new WorkAssetFile(1, cachePath, "jpg"), thumbnail.get());
    }

    @Test
    @DisplayName("thumbnail 缩略图不可得时返回 empty")
    void thumbnailReturnsEmptyWhenUnavailable() throws Exception {
        when(downloadService.getThumbnailFile(42L, 0)).thenReturn(null);

        assertTrue(service.thumbnail(WorkType.ARTWORK, 42L, 0).isEmpty());
    }

    @Test
    @DisplayName("rawFile 委托原图定位并从文件名导出小写扩展名")
    void rawFileDelegatesToImageFile() {
        File imageFile = new File("folder/42", "42_p0.PNG");
        when(downloadService.getImageFile(42L, 0)).thenReturn(imageFile);

        Optional<WorkAssetFile> raw = service.rawFile(WorkType.ARTWORK, 42L, 0);

        assertTrue(raw.isPresent());
        assertEquals(new WorkAssetFile(0, imageFile.toPath(), "png"), raw.get());
    }

    @Test
    @DisplayName("rawFile 原图缺失时返回 empty")
    void rawFileReturnsEmptyWhenMissing() {
        when(downloadService.getImageFile(42L, 3)).thenReturn(null);

        assertTrue(service.rawFile(WorkType.ARTWORK, 42L, 3).isEmpty());
    }

    @Test
    @DisplayName("deleteLocalFiles 透传 ArtworkFileLocator 的文件层清理结果")
    void deleteLocalFilesDelegatesFileCleanupResult() {
        ArtworkRecord record = artwork(42L, 2);
        when(pixivDatabase.getArtwork(42L)).thenReturn(record);
        when(artworkFileLocator.deleteArtworkFiles(record)).thenReturn(true);

        assertTrue(service.deleteLocalFiles(WorkType.ARTWORK, 42L));

        when(artworkFileLocator.deleteArtworkFiles(record)).thenReturn(false);

        assertFalse(service.deleteLocalFiles(WorkType.ARTWORK, 42L));
    }

    @Test
    @DisplayName("deleteLocalFiles 无下载记录时视为无事可做（透传 locator 对 null 的语义）")
    void deleteLocalFilesTreatsMissingRecordAsNoOp() {
        when(pixivDatabase.getArtwork(404L)).thenReturn(null);
        when(artworkFileLocator.deleteArtworkFiles(null)).thenReturn(true);

        assertTrue(service.deleteLocalFiles(WorkType.ARTWORK, 404L));
    }

    @Test
    @DisplayName("findAsset 作品无下载记录时返回 empty")
    void findAssetReturnsEmptyWhenRecordMissing() {
        when(pixivDatabase.getArtwork(404L)).thenReturn(null);

        assertTrue(service.findAsset(WorkType.ARTWORK, 404L).isEmpty());
    }

    @Test
    @DisplayName("findAsset 汇总作品目录、声明页数与磁盘上存在的页文件（缺页跳过）")
    void findAssetCollectsDirectoryAndExistingPages() {
        ArtworkRecord record = artwork(42L, 3);
        File page0 = new File("folder/42", "42_p0.jpg");
        File page2 = new File("folder/42", "42_p2.webp");
        when(pixivDatabase.getArtwork(42L)).thenReturn(record);
        when(artworkFileLocator.resolveArtworkDirectory(record)).thenReturn("folder/42");
        when(artworkFileLocator.resolveImageFile(record, 0)).thenReturn(page0);
        when(artworkFileLocator.resolveImageFile(record, 1)).thenReturn(null);
        when(artworkFileLocator.resolveImageFile(record, 2)).thenReturn(page2);

        Optional<LocalWorkAsset> asset = service.findAsset(WorkType.ARTWORK, 42L);

        assertTrue(asset.isPresent());
        assertEquals(WorkType.ARTWORK, asset.get().workType());
        assertEquals(42L, asset.get().workId());
        assertEquals(Path.of("folder", "42"), asset.get().directory());
        assertEquals(3, asset.get().pageCount());
        assertEquals(List.of(
                new WorkAssetFile(0, page0.toPath(), "jpg"),
                new WorkAssetFile(2, page2.toPath(), "webp")), asset.get().files());
    }

    @Test
    @DisplayName("findAsset 下载记录目录为空（污染行）时 directory 为 null、页文件为空")
    void findAssetHandlesBlankDirectory() {
        ArtworkRecord record = artwork(42L, 1);
        when(pixivDatabase.getArtwork(42L)).thenReturn(record);
        when(artworkFileLocator.resolveArtworkDirectory(record)).thenReturn(" ");
        when(artworkFileLocator.resolveImageFile(record, 0)).thenReturn(null);

        Optional<LocalWorkAsset> asset = service.findAsset(WorkType.ARTWORK, 42L);

        assertTrue(asset.isPresent());
        assertNull(asset.get().directory());
        assertEquals(1, asset.get().pageCount());
        assertTrue(asset.get().files().isEmpty());
    }

    /**
     * NOVEL 接入前的契约单测：小说画廊改走核心接口接入小说侧实现时，本用例翻转为正常断言。
     */
    @Test
    @DisplayName("NOVEL 契约：接入前四个方法一律显式抛 UnsupportedOperationException")
    void novelIsExplicitlyUnsupportedUntilWired() {
        assertThrows(UnsupportedOperationException.class, () -> service.findAsset(WorkType.NOVEL, 42L));
        assertThrows(UnsupportedOperationException.class, () -> service.thumbnail(WorkType.NOVEL, 42L, 0));
        assertThrows(UnsupportedOperationException.class, () -> service.rawFile(WorkType.NOVEL, 42L, 0));
        assertThrows(UnsupportedOperationException.class, () -> service.deleteLocalFiles(WorkType.NOVEL, 42L));
    }
}
