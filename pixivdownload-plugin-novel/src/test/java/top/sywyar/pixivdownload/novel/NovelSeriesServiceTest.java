package top.sywyar.pixivdownload.novel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.core.pixiv.PixivImageDownloader;
import top.sywyar.pixivdownload.novel.testsupport.NovelTestMessages;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelSeries;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NovelSeriesService 插件所有权")
class NovelSeriesServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private NovelDatabase novelDatabase;

    @Mock
    private DownloadSettings downloadSettings;

    @Mock
    private PixivImageDownloader imageDownloader;

    @Test
    @DisplayName("系列封面路径、扩展名和数据库投影由小说插件决定")
    void shouldOwnSeriesCoverPathExtensionAndProjection() throws Exception {
        when(downloadSettings.getRootFolder()).thenReturn(tempDir.toString());
        when(novelDatabase.getSeries(42L)).thenReturn(
                new NovelSeries(42L, "系列", 7L, 1L, null, null, null));
        when(imageDownloader.download(any(), any(), any(), eq("PHPSESSID=test"), any())).thenReturn(true);
        NovelSeriesService service = new NovelSeriesService(
                novelDatabase, downloadSettings, imageDownloader, NovelTestMessages.messageResolver());

        service.observeWithMetadata(
                42L,
                "系列",
                7L,
                "简介",
                "https://i.pximg.net/series/cover.webp",
                List.of(),
                "PHPSESSID=test");

        ArgumentCaptor<URI> source = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<URI> referer = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<Path> target = ArgumentCaptor.forClass(Path.class);
        verify(imageDownloader).download(
                source.capture(), referer.capture(), target.capture(), eq("PHPSESSID=test"), any());
        assertThat(source.getValue()).isEqualTo(URI.create("https://i.pximg.net/series/cover.webp"));
        assertThat(referer.getValue()).isEqualTo(URI.create("https://www.pixiv.net/novel/series/42"));
        Path expectedFolder = tempDir.resolve("novel-series-42").toAbsolutePath().normalize();
        assertThat(target.getValue()).isEqualTo(expectedFolder.resolve("cover.webp"));
        verify(novelDatabase).updateSeriesMetadata(42L, "简介", "webp", expectedFolder.toString());
    }
}
