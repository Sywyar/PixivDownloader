package top.sywyar.pixivdownload.download;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkSidecarStore;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelRecord;
import top.sywyar.pixivdownload.plugin.api.work.model.LocalWorkAsset;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkSidecarMeta;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalWorkAssetServiceTest {

    @TempDir
    Path tempDir;

    private ArtworkFileService artworkFileService;
    private ArtworkFileLocator artworkFileLocator;
    private PixivDatabase pixivDatabase;
    private NovelMetadataRepository novelMetadataRepository;
    private DownloadConfig downloadConfig;
    private WorkSidecarStore sidecarStore;
    private LocalWorkAssetService service;

    @BeforeEach
    void setUp() {
        // 小说删除经真实 StagedFileDeletion 走原子删除，暂存目录指向临时 data 目录避免污染工作目录
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, tempDir.resolve("rt-data").toString());
        artworkFileService = mock(ArtworkFileService.class);
        artworkFileLocator = mock(ArtworkFileLocator.class);
        pixivDatabase = mock(PixivDatabase.class);
        novelMetadataRepository = mock(NovelMetadataRepository.class);
        downloadConfig = mock(DownloadConfig.class);
        sidecarStore = new WorkSidecarStore(new ObjectMapper());
        service = new LocalWorkAssetService(artworkFileService, artworkFileLocator, pixivDatabase,
                novelMetadataRepository, downloadConfig, sidecarStore, TestI18nBeans.appMessages(),
                new StagedFileDeletion(TestI18nBeans.appMessages()));
    }

    @AfterEach
    void clearStagingDirectoryProperty() {
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
    }

    private static ArtworkRecord artwork(long artworkId, int count) {
        return new ArtworkRecord(artworkId, "title", "folder/" + artworkId, count, "jpg",
                1000L, false, null, null, 0, false, 1L, null);
    }

    @Test
    @DisplayName("thumbnail 委托 ArtworkFileService 缩略图缓存并映射为 WorkAssetFile")
    void thumbnailDelegatesToThumbnailCache() throws Exception {
        Path cachePath = Path.of("data", "gallery_thumbs", "42", "p1.jpg");
        when(artworkFileService.getThumbnailFile(42L, 1))
                .thenReturn(new ArtworkFileService.ThumbnailFile(cachePath, "jpg"));

        Optional<WorkAssetFile> thumbnail = service.thumbnail(WorkType.ARTWORK, 42L, 1);

        assertTrue(thumbnail.isPresent());
        assertEquals(new WorkAssetFile(1, cachePath, "jpg"), thumbnail.get());
    }

    @Test
    @DisplayName("thumbnail 缩略图不可得时返回 empty")
    void thumbnailReturnsEmptyWhenUnavailable() throws Exception {
        when(artworkFileService.getThumbnailFile(42L, 0)).thenReturn(null);

        assertTrue(service.thumbnail(WorkType.ARTWORK, 42L, 0).isEmpty());
    }

    @Test
    @DisplayName("rawFile 委托原图定位并从文件名导出小写扩展名")
    void rawFileDelegatesToImageFile() {
        File imageFile = new File("folder/42", "42_p0.PNG");
        when(artworkFileService.getImageFile(42L, 0)).thenReturn(imageFile);

        Optional<WorkAssetFile> raw = service.rawFile(WorkType.ARTWORK, 42L, 0);

        assertTrue(raw.isPresent());
        assertEquals(new WorkAssetFile(0, imageFile.toPath(), "png"), raw.get());
    }

    @Test
    @DisplayName("rawFile 原图缺失时返回 empty")
    void rawFileReturnsEmptyWhenMissing() {
        when(artworkFileService.getImageFile(42L, 3)).thenReturn(null);

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

    @Test
    @DisplayName("findSidecarMeta 读取并解析作品目录下的 {id}.meta.json")
    void findSidecarMetaReadsArtworkSidecar() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("42"));
        Files.writeString(dir.resolve("42.meta.json"),
                "{\"schemaVersion\":1,\"workType\":\"ARTWORK\",\"workId\":42,\"source\":\"schedule\","
                        + "\"normalized\":{\"isOriginal\":true},\"raw\":{}}");
        ArtworkRecord record = artwork(42L, 1);
        when(pixivDatabase.getArtwork(42L)).thenReturn(record);
        when(artworkFileLocator.resolveArtworkDirectory(record)).thenReturn(dir.toString());

        Optional<WorkSidecarMeta> meta = service.findSidecarMeta(WorkType.ARTWORK, 42L);

        assertTrue(meta.isPresent());
        assertEquals(42L, meta.get().workId());
        assertEquals(Boolean.TRUE, meta.get().normalized().isOriginal());
    }

    @Test
    @DisplayName("findSidecarMeta 损坏 / 非法 sidecar 时返回 empty（不上抛给插件）")
    void findSidecarMetaEmptyWhenSidecarInvalid() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("42"));
        ArtworkRecord record = artwork(42L, 1);
        when(pixivDatabase.getArtwork(42L)).thenReturn(record);
        when(artworkFileLocator.resolveArtworkDirectory(record)).thenReturn(dir.toString());

        // 损坏 JSON
        Files.writeString(dir.resolve("42.meta.json"), "{not json");
        assertTrue(service.findSidecarMeta(WorkType.ARTWORK, 42L).isEmpty());

        // workType 张冠李戴（文件声明 NOVEL，请求 ARTWORK）
        Files.writeString(dir.resolve("42.meta.json"),
                "{\"schemaVersion\":1,\"workType\":\"NOVEL\",\"workId\":42,\"source\":\"schedule\","
                        + "\"normalized\":{},\"raw\":{}}");
        assertTrue(service.findSidecarMeta(WorkType.ARTWORK, 42L).isEmpty());
    }

    @Test
    @DisplayName("findSidecarMeta 无下载记录时返回 empty")
    void findSidecarMetaEmptyWhenRecordMissing() {
        when(pixivDatabase.getArtwork(404L)).thenReturn(null);

        assertTrue(service.findSidecarMeta(WorkType.ARTWORK, 404L).isEmpty());
    }

    @Nested
    @DisplayName("小说侧（novel-{id} 独占目录语义）")
    class NovelAssetTests {

        private NovelRecord novel(long novelId, String folder, Long fileName, String coverExt) {
            return new NovelRecord(novelId, "小说标题", folder, 1, "txt", 1000L, 0, false, 88L,
                    null, fileName, null, null, null, null, null, null, null, null, null,
                    "正文", coverExt, false, null);
        }

        private Path novelDir(long novelId) throws Exception {
            return Files.createDirectories(tempDir.resolve("novel-" + novelId));
        }

        @Test
        @DisplayName("findAsset 无下载记录时返回 empty")
        void findAssetReturnsEmptyWhenNovelMissing() {
            when(novelMetadataRepository.getNovel(404L)).thenReturn(null);

            assertTrue(service.findAsset(WorkType.NOVEL, 404L).isEmpty());
        }

        @Test
        @DisplayName("findAsset 按路径字典序枚举目录全部常规文件，页号为枚举序号、pageCount 为文件数")
        void findAssetEnumeratesNovelFilesInOrder() throws Exception {
            Path dir = novelDir(7L);
            Path a = Files.writeString(dir.resolve("7_p0.txt"), "text");
            Path b = Files.writeString(dir.resolve("7_p0_thumb.jpg"), "cover");
            Path c = Files.writeString(dir.resolve("embed_x.png"), "img");
            when(novelMetadataRepository.getNovel(7L)).thenReturn(novel(7L, dir.toString(), null, "jpg"));
            when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());

            Optional<LocalWorkAsset> asset = service.findAsset(WorkType.NOVEL, 7L);

            assertTrue(asset.isPresent());
            assertEquals(WorkType.NOVEL, asset.get().workType());
            assertEquals(dir.toAbsolutePath().normalize(), asset.get().directory());
            assertEquals(3, asset.get().pageCount());
            assertEquals(List.of(
                    new WorkAssetFile(0, a.toAbsolutePath().normalize(), "txt"),
                    new WorkAssetFile(1, b.toAbsolutePath().normalize(), "jpg"),
                    new WorkAssetFile(2, c.toAbsolutePath().normalize(), "png")), asset.get().files());
        }

        @Test
        @DisplayName("findAsset 枚举层真过滤 meta sidecar（{id}.meta.json 不入导出枚举）")
        void findAssetExcludesNovelSidecar() throws Exception {
            Path dir = novelDir(7L);
            Path txt = Files.writeString(dir.resolve("7_p0.txt"), "text");
            Files.writeString(dir.resolve("7.meta.json"), "{\"schemaVersion\":1}");
            when(novelMetadataRepository.getNovel(7L)).thenReturn(novel(7L, dir.toString(), null, "jpg"));
            when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());

            Optional<LocalWorkAsset> asset = service.findAsset(WorkType.NOVEL, 7L);

            assertTrue(asset.isPresent());
            assertEquals(1, asset.get().pageCount());
            assertEquals(List.of(new WorkAssetFile(0, txt.toAbsolutePath().normalize(), "txt")),
                    asset.get().files());
        }

        @Test
        @DisplayName("findSidecarMeta 读取并解析 novel-{id} 目录下的 {id}.meta.json")
        void findSidecarMetaReadsNovelSidecar() throws Exception {
            Path dir = novelDir(7L);
            Files.writeString(dir.resolve("7.meta.json"),
                    "{\"schemaVersion\":1,\"workType\":\"NOVEL\",\"workId\":7,\"source\":\"schedule\","
                            + "\"normalized\":{\"uploadTime\":123},\"raw\":{\"restrict\":0}}");
            when(novelMetadataRepository.getNovel(7L)).thenReturn(novel(7L, dir.toString(), null, "jpg"));
            when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());

            Optional<WorkSidecarMeta> meta = service.findSidecarMeta(WorkType.NOVEL, 7L);

            assertTrue(meta.isPresent());
            assertEquals(7L, meta.get().workId());
            assertEquals(123L, meta.get().normalized().uploadTime());
        }

        @Test
        @DisplayName("findAsset 目录名不是 novel-{id} 独占目录时（污染行）directory 为 null、文件为空")
        void findAssetRefusesNonExclusiveDirectory() throws Exception {
            Path shared = Files.createDirectories(tempDir.resolve("shared"));
            Files.writeString(shared.resolve("other.txt"), "x");
            when(novelMetadataRepository.getNovel(7L)).thenReturn(novel(7L, shared.toString(), null, "jpg"));
            when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());

            Optional<LocalWorkAsset> asset = service.findAsset(WorkType.NOVEL, 7L);

            assertTrue(asset.isPresent());
            assertNull(asset.get().directory());
            assertEquals(0, asset.get().pageCount());
            assertTrue(asset.get().files().isEmpty());
        }

        @Test
        @DisplayName("thumbnail 恒解析封面 {存储基名}_thumb.{coverExt}，page 参数被忽略、返回页号 0")
        void thumbnailResolvesCoverFile() throws Exception {
            Path dir = novelDir(7L);
            Path cover = Files.writeString(dir.resolve("7_thumb.jpg"), "cover");
            when(novelMetadataRepository.getNovel(7L)).thenReturn(novel(7L, dir.toString(), 5L, "jpg"));
            when(pixivDatabase.getFileNameTemplate(5L)).thenReturn("{artwork_id}");

            Optional<WorkAssetFile> thumbnail = service.thumbnail(WorkType.NOVEL, 7L, 3);

            assertTrue(thumbnail.isPresent());
            assertEquals(0, thumbnail.get().page());
            assertEquals(cover, thumbnail.get().path());
            assertEquals("jpg", thumbnail.get().extension());
        }

        @Test
        @DisplayName("thumbnail 模板缺省时按默认文件名模板解析封面基名")
        void thumbnailFallsBackToDefaultTemplate() throws Exception {
            Path dir = novelDir(7L);
            Path cover = Files.writeString(dir.resolve("7_p0_thumb.jpg"), "cover");
            when(novelMetadataRepository.getNovel(7L)).thenReturn(novel(7L, dir.toString(), null, "jpg"));

            Optional<WorkAssetFile> thumbnail = service.thumbnail(WorkType.NOVEL, 7L, 0);

            assertTrue(thumbnail.isPresent());
            assertEquals(cover, thumbnail.get().path());
        }

        @Test
        @DisplayName("thumbnail 无封面记录或封面文件缺失时返回 empty")
        void thumbnailReturnsEmptyWithoutCover() throws Exception {
            Path dir = novelDir(7L);
            when(novelMetadataRepository.getNovel(7L)).thenReturn(novel(7L, dir.toString(), null, null));
            assertTrue(service.thumbnail(WorkType.NOVEL, 7L, 0).isEmpty());

            when(novelMetadataRepository.getNovel(8L)).thenReturn(novel(8L, dir.toString(), null, "jpg"));
            assertTrue(service.thumbnail(WorkType.NOVEL, 8L, 0).isEmpty());
        }

        @Test
        @DisplayName("rawFile 按 findAsset 同一枚举序号取文件，序号越界返回 empty")
        void rawFileUsesEnumerationOrdinal() throws Exception {
            Path dir = novelDir(7L);
            Path a = Files.writeString(dir.resolve("7_p0.txt"), "text");
            Path b = Files.writeString(dir.resolve("embed_x.png"), "img");
            when(novelMetadataRepository.getNovel(7L)).thenReturn(novel(7L, dir.toString(), null, "jpg"));
            when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());

            assertEquals(a.toAbsolutePath().normalize(),
                    service.rawFile(WorkType.NOVEL, 7L, 0).orElseThrow().path());
            assertEquals(b.toAbsolutePath().normalize(),
                    service.rawFile(WorkType.NOVEL, 7L, 1).orElseThrow().path());
            assertTrue(service.rawFile(WorkType.NOVEL, 7L, 2).isEmpty());
            assertTrue(service.rawFile(WorkType.NOVEL, 7L, -1).isEmpty());
        }

        @Test
        @DisplayName("deleteLocalFiles 递归删除独占目录（含子目录与目录本身）")
        void deleteLocalFilesRemovesExclusiveDirectory() throws Exception {
            Path dir = novelDir(7L);
            Files.writeString(dir.resolve("7_p0.txt"), "text");
            Path sub = Files.createDirectories(dir.resolve("extra"));
            Files.writeString(sub.resolve("note.txt"), "x");
            when(novelMetadataRepository.getNovel(7L)).thenReturn(novel(7L, dir.toString(), null, "jpg"));
            when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());

            assertTrue(service.deleteLocalFiles(WorkType.NOVEL, 7L));
            assertFalse(Files.exists(dir));
        }

        @Test
        @DisplayName("deleteLocalFiles 边界守卫：非独占目录 / 等于 root-folder 本身时不触碰磁盘并视为无事可做")
        void deleteLocalFilesRefusesGuardedDirectories() throws Exception {
            Path shared = Files.createDirectories(tempDir.resolve("shared"));
            Path keep = Files.writeString(shared.resolve("keep.txt"), "x");
            when(novelMetadataRepository.getNovel(7L)).thenReturn(novel(7L, shared.toString(), null, "jpg"));
            when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());

            assertTrue(service.deleteLocalFiles(WorkType.NOVEL, 7L));
            assertTrue(Files.exists(keep));

            when(novelMetadataRepository.getNovel(8L)).thenReturn(novel(8L, tempDir.toString(), null, "jpg"));
            assertTrue(service.deleteLocalFiles(WorkType.NOVEL, 8L));
            assertTrue(Files.exists(keep));
        }

        @Test
        @DisplayName("deleteLocalFiles 无下载记录时视为无事可做")
        void deleteLocalFilesTreatsMissingNovelAsNoOp() {
            when(novelMetadataRepository.getNovel(404L)).thenReturn(null);

            assertTrue(service.deleteLocalFiles(WorkType.NOVEL, 404L));
        }

        @Test
        @DisplayName("deleteLocalFiles 某文件删除失败时原子回滚：小说目录与全部文件复原、返回 false")
        void deleteLocalFilesRollsBackWhenDeletionFails() throws Exception {
            Path dir = novelDir(7L);
            Path txt = Files.writeString(dir.resolve("7_p0.txt"), "text");
            Path sidecar = Files.writeString(dir.resolve("7.meta.json"), "{\"schemaVersion\":1}");
            when(novelMetadataRepository.getNovel(7L)).thenReturn(novel(7L, dir.toString(), null, "jpg"));
            when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());

            LocalWorkAssetService failingService = new LocalWorkAssetService(
                    artworkFileService, artworkFileLocator, pixivDatabase, novelMetadataRepository,
                    downloadConfig, sidecarStore, TestI18nBeans.appMessages(),
                    failOn(dir.resolve("7.meta.json")));

            assertFalse(failingService.deleteLocalFiles(WorkType.NOVEL, 7L), "删除失败应返回 false");
            assertTrue(Files.exists(txt), "正文文件应被回滚复原");
            assertTrue(Files.exists(sidecar), "sidecar 应保留");
            assertTrue(Files.isDirectory(dir), "回滚后小说目录不应被移除");
        }
    }

    /** 删除指定路径时抛 IOException 的 StagedFileDeletion，用于确定性模拟删除失败。 */
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
