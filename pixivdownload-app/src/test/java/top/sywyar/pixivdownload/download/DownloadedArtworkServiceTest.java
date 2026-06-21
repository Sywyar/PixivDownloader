package top.sywyar.pixivdownload.download;

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
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
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
                new StagedFileDeletion(APP_MESSAGES));
        ArtworkFileService artworkFileService = new ArtworkFileService(pixivDatabase, artworkFileLocator);
        ArtworkMetadataRecoveryService artworkMetadataRecoveryService =
                new ArtworkMetadataRecoveryService(pixivDatabase, authorService, downloadConfig, APP_MESSAGES);
        downloadedArtworkService = new DownloadedArtworkService(pixivDatabase, artworkFileService,
                artworkMetadataRecoveryService, artworkFileLocator, APP_MESSAGES);
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
        @DisplayName("verifyFiles=true 且目录为空时应删除脏记录")
        void shouldDeleteStaleRecordWhenDirectoryIsEmpty() throws Exception {
            Path folder = Files.createDirectories(tempDir.resolve("12345"));
            ArtworkRecord record = new ArtworkRecord(12345L, "测试作品", folder.toString(),
                    3, "jpg", 1700000000L, false, null, null, 0, null, null, null);
            when(pixivDatabase.getArtwork(12345L)).thenReturn(record);

            ArtworkRecord result = downloadedArtworkService.getDownloadedRecord(12345L, true);

            assertThat(result).isNull();
            verify(pixivDatabase).deleteArtwork(12345L);
        }

        @Test
        @DisplayName("verifyFiles=true 且目录里没有作品图片文件时应删除脏记录")
        void shouldDeleteStaleRecordWhenDirectoryHasNoArtworkImages() throws Exception {
            Path folder = Files.createDirectories(tempDir.resolve("22345"));
            Files.writeString(folder.resolve("note.txt"), "orphan");
            Files.writeString(folder.resolve("22345_p0.json"), "{}");
            ArtworkRecord record = new ArtworkRecord(22345L, "测试作品", folder.toString(),
                    1, "jpg", 1700000000L, false, null, null, 0, null, null, null);
            when(pixivDatabase.getArtwork(22345L)).thenReturn(record);

            ArtworkRecord result = downloadedArtworkService.getDownloadedRecord(22345L, true);

            assertThat(result).isNull();
            verify(pixivDatabase).deleteArtwork(22345L);
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

    @Nested
    @DisplayName("getDownloadedRecord 磁盘反向恢复")
    class FindArtworkOnDiskTests {

        @BeforeEach
        void setupRootFolder() {
            lenient().when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());
            lenient().when(pixivDatabase.getUniqueTime()).thenReturn(1700000200L);
        }

        @Test
        @DisplayName("verifyFiles=false 时即便磁盘有文件也不应恢复")
        void shouldNotRecoverWhenVerifyFilesFalse() throws Exception {
            Path dir = Files.createDirectories(tempDir.resolve("11111"));
            Files.write(dir.resolve("11111_p0.jpg"), new byte[]{1});
            when(pixivDatabase.getArtwork(11111L)).thenReturn(null);

            ArtworkRecord result = downloadedArtworkService.getDownloadedRecord(11111L, false);

            assertThat(result).isNull();
            verify(pixivDatabase, never()).insertArtwork(anyLong(), anyString(), anyString(), anyInt(),
                    anyString(), anyLong(), any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("根目录不存在时应返回 null")
        void shouldReturnNullWhenRootFolderMissing() {
            when(downloadConfig.getRootFolder()).thenReturn(tempDir.resolve("no-such-root").toString());
            when(pixivDatabase.getArtwork(22222L)).thenReturn(null);

            assertThat(downloadedArtworkService.getDownloadedRecord(22222L, true)).isNull();
            verify(pixivDatabase, never()).insertArtwork(anyLong(), anyString(), anyString(), anyInt(),
                    anyString(), anyLong(), any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("作品目录不存在时应返回 null")
        void shouldReturnNullWhenArtworkDirMissing() {
            when(pixivDatabase.getArtwork(33333L)).thenReturn(null);

            assertThat(downloadedArtworkService.getDownloadedRecord(33333L, true)).isNull();
            verify(pixivDatabase, never()).insertArtwork(anyLong(), anyString(), anyString(), anyInt(),
                    anyString(), anyLong(), any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("目录仅含非匹配文件时不应恢复")
        void shouldNotRecoverWhenNoMatchingFiles() throws Exception {
            Path dir = Files.createDirectories(tempDir.resolve("44444"));
            Files.writeString(dir.resolve("note.txt"), "x");
            // 不同作品 ID 的图片不应被算作匹配
            Files.write(dir.resolve("99999_p0.jpg"), new byte[]{1});
            // 非默认模板格式
            Files.write(dir.resolve("44444-title_p0.jpg"), new byte[]{1});
            when(pixivDatabase.getArtwork(44444L)).thenReturn(null);

            assertThat(downloadedArtworkService.getDownloadedRecord(44444L, true)).isNull();
            verify(pixivDatabase, never()).insertArtwork(anyLong(), anyString(), anyString(), anyInt(),
                    anyString(), anyLong(), any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("单页同扩展名应按实际页数与扩展名恢复")
        void shouldRecoverSinglePage() throws Exception {
            long artworkId = 55555L;
            Path dir = Files.createDirectories(tempDir.resolve(String.valueOf(artworkId)));
            Files.write(dir.resolve("55555_p0.jpg"), new byte[]{1, 2});
            String absolute = dir.toAbsolutePath().toString();
            ArtworkRecord inserted = new ArtworkRecord(artworkId, "", absolute, 1, "jpg",
                    1700000200L, false, null, null, null, null, null, "", 1L, null, null, null);
            when(pixivDatabase.getArtwork(artworkId)).thenReturn(null, inserted);

            ArtworkRecord result = downloadedArtworkService.getDownloadedRecord(artworkId, true);

            assertThat(result).isSameAs(inserted);
            verify(pixivDatabase).insertArtwork(artworkId, "", absolute, 1, "jpg",
                    1700000200L, null, null, null, "");
        }

        @Test
        @DisplayName("多页同扩展名应记 count=最大页号+1，extensions 去重为单值")
        void shouldRecoverMultiPageSameExt() throws Exception {
            long artworkId = 66666L;
            Path dir = Files.createDirectories(tempDir.resolve(String.valueOf(artworkId)));
            Files.write(dir.resolve("66666_p0.png"), new byte[]{1});
            Files.write(dir.resolve("66666_p1.png"), new byte[]{1});
            Files.write(dir.resolve("66666_p2.png"), new byte[]{1});
            String absolute = dir.toAbsolutePath().toString();
            when(pixivDatabase.getArtwork(artworkId)).thenReturn(null,
                    new ArtworkRecord(artworkId, "", absolute, 3, "png",
                            1700000200L, false, null, null, null, null, null, "", 1L, null, null, null));

            downloadedArtworkService.getDownloadedRecord(artworkId, true);

            verify(pixivDatabase).insertArtwork(artworkId, "", absolute, 3, "png",
                    1700000200L, null, null, null, "");
        }

        @Test
        @DisplayName("多页混合扩展名应按逗号拼接 extensions")
        void shouldRecoverMultiPageMixedExt() throws Exception {
            long artworkId = 77777L;
            Path dir = Files.createDirectories(tempDir.resolve(String.valueOf(artworkId)));
            Files.write(dir.resolve("77777_p0.jpg"), new byte[]{1});
            Files.write(dir.resolve("77777_p1.png"), new byte[]{1});
            String absolute = dir.toAbsolutePath().toString();
            when(pixivDatabase.getArtwork(artworkId)).thenReturn(null,
                    new ArtworkRecord(artworkId, "", absolute, 2, "jpg,png",
                            1700000200L, false, null, null, null, null, null, "", 1L, null, null, null));

            downloadedArtworkService.getDownloadedRecord(artworkId, true);

            // 按页号升序拼接 extensions（p0=jpg, p1=png）
            verify(pixivDatabase).insertArtwork(artworkId, "", absolute, 2, "jpg,png",
                    1700000200L, null, null, null, "");
        }

        @Test
        @DisplayName("页号有空洞时不应恢复为已下载记录")
        void shouldNotRecoverWhenPagesHaveGap() throws Exception {
            long artworkId = 88888L;
            Path dir = Files.createDirectories(tempDir.resolve(String.valueOf(artworkId)));
            Files.write(dir.resolve("88888_p0.jpg"), new byte[]{1});
            Files.write(dir.resolve("88888_p3.jpg"), new byte[]{1});
            when(pixivDatabase.getArtwork(artworkId)).thenReturn(null);

            assertThat(downloadedArtworkService.getDownloadedRecord(artworkId, true)).isNull();
            verify(pixivDatabase, never()).insertArtwork(anyLong(), anyString(), anyString(), anyInt(),
                    anyString(), anyLong(), any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("缺第 0 页（部分失败残留）时不应恢复为已下载记录")
        void shouldNotRecoverWhenFirstPageMissing() throws Exception {
            long artworkId = 88889L;
            Path dir = Files.createDirectories(tempDir.resolve(String.valueOf(artworkId)));
            Files.write(dir.resolve("88889_p1.jpg"), new byte[]{1});
            Files.write(dir.resolve("88889_p2.jpg"), new byte[]{1});
            when(pixivDatabase.getArtwork(artworkId)).thenReturn(null);

            assertThat(downloadedArtworkService.getDownloadedRecord(artworkId, true)).isNull();
            verify(pixivDatabase, never()).insertArtwork(anyLong(), anyString(), anyString(), anyInt(),
                    anyString(), anyLong(), any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("大小写后缀应被识别且归一化为小写")
        void shouldRecognizeUppercaseExtensions() throws Exception {
            long artworkId = 99998L;
            Path dir = Files.createDirectories(tempDir.resolve(String.valueOf(artworkId)));
            Files.write(dir.resolve("99998_p0.JPG"), new byte[]{1});
            String absolute = dir.toAbsolutePath().toString();
            when(pixivDatabase.getArtwork(artworkId)).thenReturn(null,
                    new ArtworkRecord(artworkId, "", absolute, 1, "jpg",
                            1700000200L, false, null, null, null, null, null, "", 1L, null, null, null));

            downloadedArtworkService.getDownloadedRecord(artworkId, true);

            verify(pixivDatabase).insertArtwork(artworkId, "", absolute, 1, "jpg",
                    1700000200L, null, null, null, "");
        }
    }

    // ========== getSortTimeArtworkPaged ==========

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
}
