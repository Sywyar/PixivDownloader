package top.sywyar.pixivdownload.core.download;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.InsertArtworkArgument;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DownloadedArtworkService 单元测试")
class DownloadedArtworkServiceTest {
    @org.junit.jupiter.api.AfterEach
    void closeFileOperationTestDatabases() { top.sywyar.pixivdownload.core.asset.FileOperationTestSupport.close(); }

    private static final AppMessages APP_MESSAGES = TestI18nBeans.appMessages();

    @TempDir
    Path tempDir;

    @Mock
    private DownloadConfig downloadConfig;
    @Mock
    private PixivDatabase pixivDatabase;
    @Mock
    private AuthorService authorService;

    private DownloadedArtworkService downloadedArtworkService;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        ArtworkFileLocator artworkFileLocator = new ArtworkFileLocator(pixivDatabase, downloadConfig, APP_MESSAGES,
                new StagedFileDeletion(APP_MESSAGES, top.sywyar.pixivdownload.core.asset.FileOperationTestSupport.journal()), org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.asset.ArtworkMediaStore.class), org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.asset.ExternalWorkFiles.class));
        ArtworkFileService artworkFileService = new ArtworkFileService(pixivDatabase, artworkFileLocator,
                new top.sywyar.pixivdownload.core.asset.artwork.ArtworkMediaDecoder(
                        org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner.class),
                        new com.fasterxml.jackson.databind.ObjectMapper()));
        ArtworkMetadataRecoveryService artworkMetadataRecoveryService =
                new ArtworkMetadataRecoveryService(pixivDatabase, authorService, downloadConfig, APP_MESSAGES);
        downloadedArtworkService = new DownloadedArtworkService(pixivDatabase, artworkFileService);
    }

    // ========== getDownloadedRecord ==========

    @Nested
    @DisplayName("getDownloadedRecord")
    class GetDownloadedRecordTests {

        @Test
        @DisplayName("已存在的作品应返回记录")
        void shouldReturnArtworkRecord() {
            ArtworkRecord record = new ArtworkRecord(12345L, "测试作品", "/path/to/folder",
                    3, "jpg", 1700000000L, false, null, null, 0, null, null, null);
            when(pixivDatabase.getArtwork(12345L)).thenReturn(record);

            ArtworkRecord result = downloadedArtworkService.getDownloadedRecord(12345L);

            assertThat(result).isNotNull();
            assertThat(result.artworkId()).isEqualTo(12345L);
            assertThat(result.title()).isEqualTo("测试作品");
        }

        @Test
        @DisplayName("不存在的作品应返回 null")
        void shouldReturnNullForNonExistentArtwork() {
            when(pixivDatabase.getArtwork(99999L)).thenReturn(null);

            assertThat(downloadedArtworkService.getDownloadedRecord(99999L)).isNull();
        }

        @Test
        @DisplayName("数据库异常时应向上传播")
        void shouldPropagateOnDatabaseError() {
            when(pixivDatabase.getArtwork(12345L)).thenThrow(new RuntimeException("DB error"));

            assertThatCode(() -> downloadedArtworkService.getDownloadedRecord(12345L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("DB error");
        }

        @Test
        @DisplayName("verifyFiles=true 且目录为空时允许重下但保留登记")
        void shouldDeleteStaleRecordWhenDirectoryIsEmpty() throws Exception {
            Path folder = Files.createDirectories(tempDir.resolve("12345"));
            ArtworkRecord record = new ArtworkRecord(12345L, "测试作品", folder.toString(),
                    3, "jpg", 1700000000L, false, null, null, 0, null, null, null);
            when(pixivDatabase.getArtwork(12345L)).thenReturn(record);

            ArtworkRecord result = downloadedArtworkService.getDownloadedRecord(12345L, true);

            assertThat(result).isNull();
            verify(pixivDatabase, never()).deleteArtwork(12345L);
        }

        @Test
        @DisplayName("verifyFiles=true 且目录里没有作品图片时保留登记")
        void shouldDeleteStaleRecordWhenDirectoryHasNoArtworkImages() throws Exception {
            Path folder = Files.createDirectories(tempDir.resolve("22345"));
            Files.writeString(folder.resolve("note.txt"), "orphan");
            Files.writeString(folder.resolve("22345_p0.json"), "{}");
            ArtworkRecord record = new ArtworkRecord(22345L, "测试作品", folder.toString(),
                    1, "jpg", 1700000000L, false, null, null, 0, null, null, null);
            when(pixivDatabase.getArtwork(22345L)).thenReturn(record);

            ArtworkRecord result = downloadedArtworkService.getDownloadedRecord(22345L, true);

            assertThat(result).isNull();
            verify(pixivDatabase, never()).deleteArtwork(22345L);
        }

        @Test
        @DisplayName("缺页作品及仅余缩略图的作品允许重下但不删除原登记")
        void incompleteArtworkKeepsItsRecord() throws Exception {
            Path folder = Files.createDirectories(tempDir.resolve("33"));
            Files.writeString(folder.resolve("33_p0.jpg"), "page zero");
            Files.writeString(folder.resolve("33_p1_thumb.jpg"), "thumbnail only");
            ArtworkRecord record = new ArtworkRecord(33L, "title", folder.toString(), 2, "jpg", 1L,
                    false, null, null, 0, false, null, null);
            when(pixivDatabase.getArtwork(33L)).thenReturn(record);
            assertThat(downloadedArtworkService.getDownloadedRecord(33L, true)).isNull();
            verify(pixivDatabase, never()).deleteArtwork(33L);
            Files.writeString(folder.resolve("33_p1.jpg"), "page one");
            assertThat(downloadedArtworkService.getDownloadedRecord(33L, true)).isSameAs(record);
        }

        @Test
        @DisplayName("verifyFiles=true 时应优先使用 move_folder 检测作品图片文件")
        void shouldUseMoveFolderWhenArtworkImageExists() throws Exception {
            Path originalFolder = Files.createDirectories(tempDir.resolve("32345-original"));
            Path movedFolder = Files.createDirectories(tempDir.resolve("32345-moved"));
            Files.write(movedFolder.resolve("32345_p0.webp"), new byte[]{1, 2, 3});
            ArtworkRecord record = new ArtworkRecord(32345L, "测试作品", originalFolder.toString(),
                    1, "webp", 1700000000L, true, movedFolder.toString(), 1700000001L, 0, null, null, null);
            when(pixivDatabase.getArtwork(32345L)).thenReturn(record);

            ArtworkRecord result = downloadedArtworkService.getDownloadedRecord(32345L, true);

            assertThat(result).isSameAs(record);
            verify(pixivDatabase, never()).deleteArtwork(32345L);
        }

        @Test
        @DisplayName("verifyFiles=true 时应按数据库文件名模板检测作品图片文件")
        void shouldVerifyArtworkFilesByStoredFileNameTemplate() throws Exception {
            Path folder = Files.createDirectories(tempDir.resolve("42345"));
            Files.write(folder.resolve("42345-Title_Name_p0.jpg"), new byte[]{1, 2, 3});
            ArtworkRecord record = new ArtworkRecord(42345L, "Title/Name", folder.toString(),
                    1, "jpg", 1700000000L, false, null, null, 0, false, null, null, 9L);
            when(pixivDatabase.getArtwork(42345L)).thenReturn(record);
            when(pixivDatabase.getFileNameTemplate(9L)).thenReturn("{artwork_id}-{artwork_title}_p{page}");

            ArtworkRecord result = downloadedArtworkService.getDownloadedRecord(42345L, true);

            assertThat(result).isSameAs(record);
            verify(pixivDatabase, never()).deleteArtwork(42345L);
        }
    }

    @Test
    @DisplayName("无记录时连续页号也不补登记，不能由现有文件推断尾页完整")
    void queryNeverRecoversUnregisteredFiles() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("77777"));
        Files.writeString(dir.resolve("77777_p0.jpg"), "first page");
        Files.writeString(dir.resolve("77777_p1.jpg"), "second page");
        assertThat(downloadedArtworkService.getDownloadedRecord(77777L, true)).isNull();
        verify(pixivDatabase, never()).insertArtwork(any(InsertArtworkArgument.class));
    }

    @Nested
    @DisplayName("getSortTimeArtworkPaged")
    class GetSortTimeArtworkPagedTests {

        @Test
        @DisplayName("分页查询应正确计算 offset")
        void shouldCalculateOffsetCorrectly() {
            when(pixivDatabase.getArtworkIdsSortedByTimeDescPaged(20, 10))
                    .thenReturn(List.of(100L, 99L, 98L));

            List<Long> result = downloadedArtworkService.getSortTimeArtworkPaged(2, 10);

            assertThat(result).containsExactly(100L, 99L, 98L);
            verify(pixivDatabase).getArtworkIdsSortedByTimeDescPaged(20, 10);
        }
    }

    // ========== getArtworkCount ==========

    @Test
    @DisplayName("仅保留原始 ZIP 的动图仍按正式产物判定完整")
    void recognizesRetainedUgoiraZip() throws Exception {
        Path folder = Files.createDirectories(tempDir.resolve("456"));
        Files.write(folder.resolve("456_p0.zip"), new byte[]{1, 2});
        ArtworkRecord record = new ArtworkRecord(456L, "fixture", folder.toString(),
                1, "zip", 1L, false, null, null, 0, false, null, null);
        when(pixivDatabase.getArtwork(456L)).thenReturn(record);
        assertThat(downloadedArtworkService.getDownloadedRecord(456L, true)).isSameAs(record);
    }

    @Test
    @DisplayName("getArtworkCount 应委托给数据库")
    void shouldDelegateCountToDatabase() {
        when(pixivDatabase.countArtworks()).thenReturn(42L);

        assertThat(downloadedArtworkService.getArtworkCount()).isEqualTo(42L);
    }

    // ========== getDownloadedRecord (List version) ==========

    @Test
    @DisplayName("getDownloadedRecord() 应返回所有作品ID的字符串列表")
    void shouldReturnAllArtworkIdsAsStrings() {
        when(pixivDatabase.getAllArtworkIds()).thenReturn(List.of(1L, 2L, 3L));

        List<String> result = downloadedArtworkService.getDownloadedRecord();

        assertThat(result).containsExactly("1", "2", "3");
    }

    private static InsertArtworkArgument recoveredArtwork(long artworkId, String folder,
                                                            int count, String extensions) {
        return InsertArtworkArgument.builder()
                .artworkId(artworkId)
                .title("")
                .folder(folder)
                .count(count)
                .extensions(extensions)
                .time(1700000200L)
                .description("")
                .build();
    }
}
