package top.sywyar.pixivdownload.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
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
    private final ArtworkFileLocator locator =
            new ArtworkFileLocator(pixivDatabase, downloadConfig, TestI18nBeans.appMessages());

    @Test
    @DisplayName("删除作品时一并清除 {id}.meta.json sidecar")
    void shouldDeleteSidecarWithArtwork() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("100"));
        Files.writeString(dir.resolve("100.jpg"), "img");
        Files.writeString(dir.resolve("100.meta.json"), "{\"schemaVersion\":1}");
        when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());
        when(pixivDatabase.getFileNameTemplate(anyLong())).thenReturn("{artwork_id}");

        ArtworkRecord record = new ArtworkRecord(100L, "t", dir.toString(), 1, "jpg", 1000L,
                false, null, null, 0, false, null, null);

        assertTrue(locator.deleteArtworkFiles(record));
        assertFalse(Files.exists(dir.resolve("100.meta.json")), "sidecar 应随作品删除");
        assertFalse(Files.exists(dir.resolve("100.jpg")), "图片应被删除");
    }
}
