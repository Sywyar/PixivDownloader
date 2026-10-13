package top.sywyar.pixivdownload.series;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.database.DatabaseUnavailableException;
import top.sywyar.pixivdownload.core.pixiv.PixivCoverDownloader;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("数据库不可用时系列补齐无副作用")
class MangaSeriesUnavailableTest {
    @Test
    @DisplayName("直接 SDK 命令和已排队任务均不写入、不发网络请求")
    void unavailableStopsSeriesSideEffects() {
        var database = mock(PixivDatabase.class);
        var mapper = mock(MangaSeriesMapper.class);
        var authors = mock(AuthorService.class);
        var network = mock(RestTemplate.class);
        var scheduler = mock(TaskScheduler.class);
        var covers = mock(PixivCoverDownloader.class);
        var service = new MangaSeriesService(mapper, authors, database, network, scheduler,
                null, null, covers, null, null);
        when(database.isUnavailable()).thenReturn(true);
        assertThatThrownBy(() -> service.observe(1, "name", 2L)).isInstanceOf(DatabaseUnavailableException.class);
        assertThatThrownBy(() -> service.observeWithMetadata(1, "name", 2L, "text", "url", "fixture"))
                .isInstanceOf(DatabaseUnavailableException.class);
        assertThatThrownBy(() -> service.asyncLookupMissingSeries(1, "fixture"))
                .isInstanceOf(DatabaseUnavailableException.class);
        service.lookupMissingSeries(1, "fixture");
        verifyNoInteractions(mapper, authors, network, scheduler, covers);
    }
}
