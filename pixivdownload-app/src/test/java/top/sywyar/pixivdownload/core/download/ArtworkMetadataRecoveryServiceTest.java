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
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.download.request.RecoverMetadataRequest;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ArtworkMetadataRecoveryService 单元测试")
class ArtworkMetadataRecoveryServiceTest {
    private static final AppMessages APP_MESSAGES = TestI18nBeans.appMessages();

    @TempDir
    Path tempDir;

    @Mock
    private DownloadConfig downloadConfig;
    @Mock
    private PixivDatabase pixivDatabase;
    @Mock
    private AuthorService authorService;

    private ArtworkMetadataRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        recoveryService = new ArtworkMetadataRecoveryService(pixivDatabase, authorService, downloadConfig, APP_MESSAGES);
    }

    @Nested
    @DisplayName("recoverMetadata 两阶段恢复")
    class RecoverMetadataTests {

        @BeforeEach
        void setupRootFolder() {
            lenient().when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());
            lenient().when(pixivDatabase.getUniqueTime()).thenReturn(1700000300L);
        }

        @Test
        @DisplayName("DB 已有完整记录（title 非空）应返回原记录，不覆盖任何字段")
        void shouldReturnExistingWhenTitlePresent() {
            ArtworkRecord existing = new ArtworkRecord(11111L, "原标题", "/folder",
                    3, "jpg", 1600000000L, false, null, null, 0, false, 999L, "原简介", 1L, null, null, null);
            when(pixivDatabase.getArtwork(11111L)).thenReturn(existing);
            RecoverMetadataRequest req = new RecoverMetadataRequest(
                    "新标题", 1234L, "newauthor", 1, true, "新简介");

            ArtworkRecord result = recoveryService.recoverMetadata(11111L, req);

            assertThat(result).isSameAs(existing);
            verify(pixivDatabase, never()).fillArtworkMetadataIfMissing(anyLong(), any(), any(), any(), any(), any());
            verify(pixivDatabase, never()).insertArtwork(anyLong(), anyString(), anyString(), anyInt(),
                    anyString(), anyLong(), any(), any(), any(), anyString());
            verify(authorService, never()).observe(anyLong(), any());
        }

        @Test
        @DisplayName("DB 有记录但 title 为空时应补齐缺失字段并上报作者")
        void shouldFillMissingFieldsWhenTitleEmpty() {
            ArtworkRecord bareRecord = new ArtworkRecord(22222L, "", "/folder",
                    2, "jpg", 1600000000L, false, null, null, null, null, null, "", 1L, null, null, null);
            ArtworkRecord enriched = new ArtworkRecord(22222L, "新标题", "/folder",
                    2, "jpg", 1600000000L, false, null, null, 1, true, 1234L, "新简介", 1L, null, null, null);
            when(pixivDatabase.getArtwork(22222L)).thenReturn(bareRecord, enriched);
            RecoverMetadataRequest req = new RecoverMetadataRequest(
                    "新标题", 1234L, "newauthor", 1, true, "新简介");

            ArtworkRecord result = recoveryService.recoverMetadata(22222L, req);

            assertThat(result).isSameAs(enriched);
            verify(pixivDatabase).fillArtworkMetadataIfMissing(eq(22222L), eq("新标题"),
                    eq(1), eq(true), eq(1234L), eq("新简介"));
            verify(pixivDatabase, never()).insertArtwork(anyLong(), anyString(), anyString(), anyInt(),
                    anyString(), anyLong(), any(), any(), any(), anyString());
            verify(authorService).observe(1234L, "newauthor");
        }

        @Test
        @DisplayName("DB 无记录但磁盘有匹配文件时应写完整记录")
        void shouldWriteCompleteRecordWhenDbEmptyAndDiskMatches() throws Exception {
            long artworkId = 33333L;
            Path dir = Files.createDirectories(tempDir.resolve(String.valueOf(artworkId)));
            Files.write(dir.resolve("33333_p0.jpg"), new byte[]{1});
            Files.write(dir.resolve("33333_p1.jpg"), new byte[]{1});
            String absolute = dir.toAbsolutePath().toString();
            ArtworkRecord inserted = new ArtworkRecord(artworkId, "Pixiv 标题", absolute,
                    2, "jpg", 1700000300L, false, null, null, 1, true, 5678L, "简介", 1L, null, null, null);
            when(pixivDatabase.getArtwork(artworkId)).thenReturn(null, inserted);
            RecoverMetadataRequest req = new RecoverMetadataRequest(
                    "Pixiv 标题", 5678L, "auth", 1, true, "简介");

            ArtworkRecord result = recoveryService.recoverMetadata(artworkId, req);

            assertThat(result).isSameAs(inserted);
            verify(pixivDatabase).insertArtwork(artworkId, "Pixiv 标题", absolute, 2, "jpg",
                    1700000300L, 1, true, 5678L, "简介");
            verify(authorService).observe(5678L, "auth");
        }

        @Test
        @DisplayName("DB 无记录且磁盘文件缺第 0 页时应返回 null 视为未下载")
        void shouldReturnNullWhenDiskFilesIncomplete() throws Exception {
            long artworkId = 66667L;
            Path dir = Files.createDirectories(tempDir.resolve(String.valueOf(artworkId)));
            Files.write(dir.resolve("66667_p1.jpg"), new byte[]{1});
            when(pixivDatabase.getArtwork(artworkId)).thenReturn(null);
            RecoverMetadataRequest req = new RecoverMetadataRequest(
                    "标题", 1234L, "auth", 0, false, "简介");

            assertThat(recoveryService.recoverMetadata(artworkId, req)).isNull();
            verify(pixivDatabase, never()).insertArtwork(anyLong(), anyString(), anyString(), anyInt(),
                    anyString(), anyLong(), any(), any(), any(), anyString());
            verify(authorService, never()).observe(anyLong(), any());
        }

        @Test
        @DisplayName("DB 无记录且磁盘也无匹配文件时应返回 null")
        void shouldReturnNullWhenDbEmptyAndDiskEmpty() {
            when(pixivDatabase.getArtwork(44444L)).thenReturn(null);
            RecoverMetadataRequest req = new RecoverMetadataRequest(
                    "标题", 1234L, "auth", 0, false, "简介");

            assertThat(recoveryService.recoverMetadata(44444L, req)).isNull();
            verify(pixivDatabase, never()).insertArtwork(anyLong(), anyString(), anyString(), anyInt(),
                    anyString(), anyLong(), any(), any(), any(), anyString());
            verify(authorService, never()).observe(anyLong(), any());
        }

        @Test
        @DisplayName("meta 为 null 时按空 meta 处理，不应抛异常")
        void shouldHandleNullMetaGracefully() throws Exception {
            long artworkId = 55555L;
            Path dir = Files.createDirectories(tempDir.resolve(String.valueOf(artworkId)));
            Files.write(dir.resolve("55555_p0.jpg"), new byte[]{1});
            String absolute = dir.toAbsolutePath().toString();
            ArtworkRecord inserted = new ArtworkRecord(artworkId, "", absolute,
                    1, "jpg", 1700000300L, false, null, null, null, null, null, "", 1L, null, null, null);
            when(pixivDatabase.getArtwork(artworkId)).thenReturn(null, inserted);

            ArtworkRecord result = recoveryService.recoverMetadata(artworkId, null);

            assertThat(result).isSameAs(inserted);
            verify(pixivDatabase).insertArtwork(artworkId, "", absolute, 1, "jpg",
                    1700000300L, null, null, null, "");
        }
    }
}
