package top.sywyar.pixivdownload.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.metadata.CoreWorkDeletionService;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelRecord;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkSidecarStore;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkDeletionService;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("作品删除文件失败时的原子回滚（端到端：编排 + 资产 + 原子删除）")
class WorkDeletionFileRollbackTest {

    @TempDir
    Path tempDir;

    private WorkQueryService workQueryService;
    private PixivDatabase pixivDatabase;
    private NovelMetadataRepository novelMetadataRepository;
    private DownloadConfig downloadConfig;
    private WorkSidecarStore sidecarStore;

    @BeforeEach
    void setUp() {
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, tempDir.resolve("rt-data").toString());
        workQueryService = mock(WorkQueryService.class);
        pixivDatabase = mock(PixivDatabase.class);
        novelMetadataRepository = mock(NovelMetadataRepository.class);
        downloadConfig = mock(DownloadConfig.class);
        sidecarStore = new WorkSidecarStore(new ObjectMapper());
        when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());
    }

    @AfterEach
    void clearStagingDirectoryProperty() {
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
    }

    private WorkDeletionService deletionServiceFailingOn(Path poison) {
        StagedFileDeletion failingHelper = failOn(poison);
        ArtworkFileLocator locator = new ArtworkFileLocator(
                pixivDatabase, downloadConfig, TestI18nBeans.appMessages(), failingHelper);
        LocalWorkAssetService assetService = new LocalWorkAssetService(
                mock(ArtworkFileService.class), locator, pixivDatabase, novelMetadataRepository,
                downloadConfig, sidecarStore, TestI18nBeans.appMessages(), failingHelper);
        return new CoreWorkDeletionService(workQueryService, assetService, pixivDatabase,
                novelMetadataRepository, TestI18nBeans.appMessages());
    }

    @Test
    @DisplayName("插画文件删除失败：抛 409、数据库未软删、全部文件复原")
    void artworkFileDeletionFailureRollsBackAndAbortsSoftDelete() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("300"));
        Path p0 = Files.writeString(dir.resolve("300_p0.jpg"), "p0");
        Path p1 = Files.writeString(dir.resolve("300_p1.jpg"), "p1");
        Path sidecar = Files.writeString(dir.resolve("300.meta.json"), "{\"schemaVersion\":1}");
        when(pixivDatabase.getFileNameTemplate(anyLong())).thenReturn("{artwork_id}_p{page}");
        when(pixivDatabase.getArtwork(300L)).thenReturn(new ArtworkRecord(
                300L, "t", dir.toString(), 2, "jpg", 1000L, false, null, null, 0, false, null, null));
        when(workQueryService.hasActiveWork(WorkType.ARTWORK, 300L)).thenReturn(true);

        WorkDeletionService deletionService = deletionServiceFailingOn(dir.resolve("300_p1.jpg"));

        assertThatThrownBy(() -> deletionService.delete(WorkType.ARTWORK, 300L))
                .isInstanceOf(LocalizedException.class)
                .satisfies(e -> assertThat(((LocalizedException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(p0).exists();
        assertThat(p1).exists();
        assertThat(sidecar).exists();
        verify(pixivDatabase, never()).markArtworkDeleted(anyLong());
    }

    @Test
    @DisplayName("小说文件删除失败：抛 409、小说主行未软删、目录与全部文件复原")
    void novelFileDeletionFailureRollsBackAndAbortsSoftDelete() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("novel-7"));
        Path body = Files.writeString(dir.resolve("7_p0.txt"), "text");
        Path sidecar = Files.writeString(dir.resolve("7.meta.json"), "{\"schemaVersion\":1}");
        when(novelMetadataRepository.getNovel(7L)).thenReturn(new NovelRecord(
                7L, "小说", dir.toString(), 1, "txt", 1000L, 0, false, 88L, null, null, null,
                null, null, null, null, null, null, null, null, "正文", "jpg", false, null));
        when(workQueryService.hasActiveWork(WorkType.NOVEL, 7L)).thenReturn(true);

        WorkDeletionService deletionService = deletionServiceFailingOn(dir.resolve("7.meta.json"));

        assertThatThrownBy(() -> deletionService.delete(WorkType.NOVEL, 7L))
                .isInstanceOf(LocalizedException.class)
                .satisfies(e -> assertThat(((LocalizedException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(body).exists();
        assertThat(sidecar).exists();
        assertThat(dir).isDirectory();
        verify(novelMetadataRepository, never()).markNovelDeleted(anyLong());
    }

    private static StagedFileDeletion failOn(Path poison) {
        Path normalizedPoison = poison.toAbsolutePath().normalize();
        return new StagedFileDeletion(TestI18nBeans.appMessages()) {
            @Override
            protected void deleteFile(Path original) throws IOException {
                if (original.toAbsolutePath().normalize().equals(normalizedPoison)) {
                    throw new IOException("simulated lock on " + original);
                }
                super.deleteFile(original);
            }
        };
    }
}
