package top.sywyar.pixivdownload.core.download;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;
import top.sywyar.pixivdownload.core.asset.DownloadDirectories;
import top.sywyar.pixivdownload.core.artwork.download.PartialArtwork;
import top.sywyar.pixivdownload.core.work.directory.DirectoryTemplateException;
import top.sywyar.pixivdownload.core.work.service.WorkDeletionException;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion.UnsafeDeletionPathException;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ArtworkFileLocator 删除链路")
class ArtworkFileLocatorTest {
    @org.junit.jupiter.api.AfterEach
    void closeFileOperationTestDatabases() { top.sywyar.pixivdownload.core.asset.FileOperationTestSupport.close(); }


    @Test
    @DisplayName("按实际存在的原图 WebP PNG JPG 顺序选择并随作品删除遗留媒体清单")
    void prefersOriginalThenAvailableDerivatives() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("42"));
        when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());
        when(pixivDatabase.getFileNameTemplate(anyLong())).thenReturn("{artwork_id}_p{page}");
        var record = artwork(42, dir.toString(), 1);
        for (String extension : java.util.List.of("jpg", "png", "webp")) {
            Files.writeString(dir.resolve("42_p0." + extension), "fixture");
        }
        when(mediaStore.find(42L, 0)).thenReturn(java.util.Optional.of(new top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest("png",
                java.util.List.of("jpg", "png", "webp"), false)));
        org.junit.jupiter.api.Assertions.assertEquals(dir.resolve("42_p0.webp").toFile(), locator.resolveImageFile(record, 0));
        when(mediaStore.find(42L, 0)).thenReturn(java.util.Optional.of(new top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest("jpg",
                java.util.List.of("jpg", "png", "webp"), true)));
        for (String extension : java.util.List.of("jpg", "webp", "png")) {
            Path expected = dir.resolve("42_p0." + extension);
            org.junit.jupiter.api.Assertions.assertEquals(expected.toFile(), locator.resolveImageFile(record, 0));
            Files.delete(expected);
        }
        Path legacyManifest = Files.writeString(dir.resolve("42_p0.media.properties"), "original=jpg");
        assertTrue(locator.deleteArtworkFiles(record));
        assertFalse(Files.exists(legacyManifest));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void videoThumbnailAndHashUseOnlyTheOwnedCompanion(boolean thumbnailOwned) throws Exception {
        var video = Files.createFile(tempDir.resolve("animation.mp4")).toFile();
        var jpeg = Files.createFile(tempDir.resolve("animation_thumb.jpg")).toFile();
        var record = new ArtworkRecord(42L, "animation", tempDir.toString(), 1, "mp4", 1000L,
                false, null, null, 0, false, null, null);
        when(pixivDatabase.getArtworkFileNames(42L)).thenReturn(List.of("animation"));
        when(mediaStore.find(42L, 0)).thenReturn(Optional.of(
                new ArtworkMediaManifest("zip", List.of("mp4"), false, thumbnailOwned)));
        var expected = thumbnailOwned ? jpeg : video;
        assertThat(locator.resolveThumbnailSourceFile(record, 0)).isEqualTo(expected);
        assertThat(locator.resolveHashSourceFile(record, 0)).isEqualTo(new ArtworkFileLocator.LocatedArtworkFile(expected, "mp4"));
        assertThat(locator.resolveImageFile(record, 0)).isEqualTo(video);
    }

    @Test
    void replacementUsesRegisteredOutputsAndLeavesExternalReferencesAlone() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("42"));
        var record = artwork(42, dir.toString(), 2);
        when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());
        when(pixivDatabase.getArtworkFileNames(42L)).thenReturn(java.util.List.of("animation", "legacy"));
        when(mediaStore.find(42L, 0)).thenReturn(java.util.Optional.of(
                new top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest("zip", java.util.List.of("webp", "zip"), true, true)));
        var names = java.util.List.of("animation.webp", "animation.zip", "animation_thumb.jpg",
                "animation.frames.properties", "legacy.jpg");
        for (String name : names) Files.writeString(dir.resolve(name), "owned");
        Files.writeString(dir.resolve("animation.png"), "unregistered");
        Files.writeString(dir.resolve("legacy.txt"), "user note");
        org.assertj.core.api.Assertions.assertThat(locator.managedOutputs(record))
                .containsExactlyInAnyOrderElementsOf(names.stream().map(dir::resolve).toList());
        var references = mock(top.sywyar.pixivdownload.core.asset.ExternalWorkFiles.class);
        when(references.contains(top.sywyar.pixivdownload.core.work.model.WorkType.ARTWORK, 42L)).thenReturn(true);
        var imported = new ArtworkFileLocator(pixivDatabase, downloadConfig, TestI18nBeans.appMessages(),
                stagedFileDeletion, mediaStore, references, org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.asset.DownloadDirectories.class));
        org.assertj.core.api.Assertions.assertThat(imported.managedOutputs(record)).isEmpty();
    }

    @Test
    void mixedLegacyFormatsWithoutPageManifestsDoNotOwnEveryCombination() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("legacy"));
        when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());
        when(pixivDatabase.getArtworkFileNames(42L)).thenReturn(java.util.List.of("p0", "p1"));
        var record = new ArtworkRecord(42, "legacy", dir.toString(), 2, "jpg,png", 1000L,
                false, null, null, 0, false, null, null);
        Path first = Files.writeString(dir.resolve("p0.jpg"), "first");
        Files.writeString(dir.resolve("p1.png"), "second");
        Files.writeString(dir.resolve("p0.png"), "user");
        org.assertj.core.api.Assertions.assertThat(locator.managedOutputs(record)).isEmpty();
        when(mediaStore.find(42L, 0)).thenReturn(java.util.Optional.of(
                new top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest("jpg", java.util.List.of("jpg"))));
        org.assertj.core.api.Assertions.assertThat(locator.managedOutputs(record)).containsExactly(first);
        Files.writeString(dir.resolve("42.meta.json"), "{}");
        org.assertj.core.api.Assertions.assertThat(locator.archiveSources(record)).isEmpty();
    }

    @Test
    void cannotArchiveOnlyTheRecognizedPartOfALegacyRecord() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("legacy-formats"));
        when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());
        when(pixivDatabase.getArtworkFileNames(42L)).thenReturn(java.util.List.of("p0"));
        Files.writeString(dir.resolve("p0.jpg"), "registered");
        Files.writeString(dir.resolve("p0.other"), "unknown format");
        var record = new ArtworkRecord(42, "legacy", dir.toString(), 1, "jpg,other", 1000L,
                false, null, null, 0, false, null, null);
        org.assertj.core.api.Assertions.assertThat(locator.archiveSources(record)).isEmpty();
    }

    @Test
    void protectsMatchingPageOfForeignLegacyArtworkBeforeStagingAnyFile() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("shared"));
        var own = artwork(42, dir.toString(), 2);
        var foreign = new ArtworkRecord(43, "legacy", dir.toString(), 2, "jpg,png", 1000L,
                false, null, null, 0, false, null, null);
        when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());
        when(directories.otherArtworksInDirectory(42, dir, false)).thenReturn(List.of(43L));
        when(pixivDatabase.getArtwork(43L)).thenReturn(foreign);
        when(pixivDatabase.getArtworkFileNames(42L)).thenReturn(List.of("own", "shared"));
        when(pixivDatabase.getArtworkFileNames(43L)).thenReturn(List.of("other", "shared"));
        Path ownPage = Files.writeString(dir.resolve("own.jpg"), "own");
        Path shared = Files.writeString(dir.resolve("shared.jpg"), "shared");
        assertThat(assertThrows(WorkDeletionException.class, () -> locator.deleteArtworkFiles(own)).reason())
                .isEqualTo(WorkDeletionException.Reason.INCOMPLETE_FILE_OWNERSHIP);
        assertThat(Files.readString(ownPage)).isEqualTo("own");
        assertThat(Files.readString(shared)).isEqualTo("shared");
        Path foreignPage = Files.writeString(dir.resolve("shared.png"), "foreign");
        when(mediaStore.find(43L, 1)).thenReturn(Optional.of(new ArtworkMediaManifest("png", List.of("png"))));
        assertThat(locator.deleteArtworkFiles(own)).isTrue();
        assertThat(ownPage).doesNotExist();
        assertThat(shared).doesNotExist();
        assertThat(Files.readString(foreignPage)).isEqualTo("foreign");
    }

    @Test
    void onlyPublishedFormatsAndOwnedThumbnailsOfAnotherSelectionBlockChanges() throws Exception {
        var manifest = new ArtworkMediaManifest("png", List.of("png", "webp"), true, true);
        var partial = new PartialArtwork(43L, tempDir.toString(), 2, "p{page}", 180, "original,webp", "v1",
                List.of("p0", "p1"), Map.of(0, manifest), Map.of());
        when(directories.otherArtworksInDirectory(42, tempDir, true)).thenReturn(List.of(43L));
        when(pixivDatabase.partialArtwork(43L)).thenReturn(partial);
        for (String name : List.of("p0.png", "p0.webp", "p0_thumb.jpg")) {
            assertThrows(DirectoryTemplateException.class,
                    () -> locator.reserveOutputs(42, Set.of(tempDir.resolve(name))));
        }
        var independent = Set.of(tempDir.resolve("p0.jpg"), tempDir.resolve("p1.png"));
        var lease = mock(DownloadDirectories.DeletionReservation.class);
        when(directories.reserveDeletion(independent)).thenReturn(lease);
        try (var reservation = locator.reserveOutputs(42, independent)) {
            assertThat(reservation).isSameAs(lease);
        }
        org.mockito.Mockito.verify(lease).close();
    }

    @Test
    void legacyZipWithoutPageManifestRequiresTimingForArchiveButCannotReplaceIt() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("legacy-animation"));
        when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());
        when(pixivDatabase.getArtworkFileNames(42L)).thenReturn(java.util.List.of("animation"));
        var record = new ArtworkRecord(42, "legacy", dir.toString(), 1, "zip", 1000L,
                false, null, null, 0, false, null, null);
        Path archive = Files.writeString(dir.resolve("animation.zip"), "frames");
        Path timing = dir.resolve("animation.frames.properties");
        org.assertj.core.api.Assertions.assertThat(locator.archiveSources(record)).contains(archive, timing);
        Files.writeString(timing, "user");
        org.assertj.core.api.Assertions.assertThat(locator.managedOutputs(record)).containsExactly(archive);
    }

    @Test
    void nativeWebpRecordDoesNotClaimAnUnregisteredThumbnail() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("native"));
        when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());
        when(pixivDatabase.getArtworkFileNames(42L)).thenReturn(java.util.List.of("source"));
        when(mediaStore.find(42L, 0)).thenReturn(java.util.Optional.of(
                new top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest("webp", java.util.List.of("webp", "png"))));
        Path source = Files.writeString(dir.resolve("source.webp"), "original");
        Path added = Files.writeString(dir.resolve("source.png"), "later conversion");
        Files.writeString(dir.resolve("source_thumb.jpg"), "user");
        org.assertj.core.api.Assertions.assertThat(locator.managedOutputs(artwork(42, dir.toString(), 1)))
                .containsExactlyInAnyOrder(source, added);
    }

    @Test
    @DisplayName("媒体查询期间数据库失效时不把空结果当作可用旧文件")
    void rejectsFallbackAfterMediaDatabaseBecomesUnavailable() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("43"));
        Files.writeString(dir.resolve("43_p0.jpg"), "existing");
        when(pixivDatabase.getArtworkFileNames(43L)).thenReturn(java.util.List.of("43_p0"));
        when(mediaStore.find(43L, 0)).thenAnswer(call -> {
            when(pixivDatabase.isUnavailable()).thenReturn(true);
            return java.util.Optional.empty();
        });
        assertThrows(top.sywyar.pixivdownload.core.database.DatabaseUnavailableException.class,
                () -> locator.resolveImageFile(artwork(43L, dir.toString(), 1), 0));
        assertTrue(Files.exists(dir.resolve("43_p0.jpg")));
    }

    @TempDir
    Path tempDir;

    private final top.sywyar.pixivdownload.core.asset.ArtworkMediaStore mediaStore = org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.asset.ArtworkMediaStore.class);
    private final PixivDatabase pixivDatabase = mock(PixivDatabase.class);
    private final DownloadConfig downloadConfig = mock(DownloadConfig.class);
    private final DownloadDirectories directories = mock(DownloadDirectories.class);
    private final StagedFileDeletion stagedFileDeletion = new StagedFileDeletion(TestI18nBeans.appMessages(), top.sywyar.pixivdownload.core.asset.FileOperationTestSupport.journal());
    private final ArtworkFileLocator locator =
            new ArtworkFileLocator(pixivDatabase, downloadConfig, TestI18nBeans.appMessages(), stagedFileDeletion, mediaStore, org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.asset.ExternalWorkFiles.class), directories);

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
    @DisplayName("截断后的名称可重新定位并随作品删除")
    void locatesAndDeletesTruncatedFilename() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("101"));
        Path saved = Files.writeString(dir.resolve("101_p0.jpg"), "image");
        when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());
        when(pixivDatabase.getFileNameTemplate(anyLong())).thenReturn("{artwork_id}_p{page}_long-title");
        when(pixivDatabase.getFileNameMaxLength(101L, false)).thenReturn(6);
        ArtworkRecord record = artwork(101L, dir.toString(), 1);
        assertTrue(saved.toFile().equals(locator.resolveImageFile(record, 0)));
        assertTrue(locator.deleteArtworkFiles(record));
        assertFalse(Files.exists(saved));
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
                failOn(dir.resolve("300_p1.jpg")), mediaStore, org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.asset.ExternalWorkFiles.class), org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.asset.DownloadDirectories.class));

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
                pixivDatabase, downloadConfig, TestI18nBeans.appMessages(), stagedFileDeletion, mediaStore, org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.asset.ExternalWorkFiles.class), org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.asset.DownloadDirectories.class)) {
            @Override
            protected boolean deleteGalleryThumbnailCache(long artworkId) {
                return false; // 模拟可再生缓存删除失败
            }
        };

        assertTrue(failingCacheLocator.deleteArtworkFiles(artwork(200L, dir.toString(), 1)),
                "缓存删失败不应导致整体失败");
        assertFalse(Files.exists(dir.resolve("200.jpg")), "图片仍应被删除");
    }

    @Test
    @DisplayName("现存作品目录等于下载根时明确拒绝删除")
    void rejectsExistingDownloadRootAsArtworkDirectory() throws Exception {
        Path keep = Files.writeString(tempDir.resolve("keep.txt"), "keep");
        when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());

        UnsafeDeletionPathException exception = assertThrows(
                UnsafeDeletionPathException.class,
                () -> locator.deleteArtworkFiles(artwork(400L, tempDir.toString(), 1)));

        assertTrue(exception.path().contains(tempDir.getFileName().toString()));
        assertTrue(Files.exists(keep), "拒绝下载根后不得触碰其中内容");
    }

    private static StagedFileDeletion failOn(Path poison) {
        Path normalizedPoison = poison.toAbsolutePath().normalize();
        return new StagedFileDeletion(TestI18nBeans.appMessages(), top.sywyar.pixivdownload.core.asset.FileOperationTestSupport.journal()) {
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
