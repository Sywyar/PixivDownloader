package top.sywyar.pixivdownload.series;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkSeriesObservation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("ArtworkSeriesObservationAdapter 系列事实适配器")
class ArtworkSeriesObservationAdapterTest {

    private final MangaSeriesService mangaSeriesService = mock(MangaSeriesService.class);
    private final PixivDatabase pixivDatabase = mock(PixivDatabase.class);
    private final ArtworkSeriesObservationAdapter adapter =
            new ArtworkSeriesObservationAdapter(mangaSeriesService, pixivDatabase);

    @Test
    @DisplayName("已知基础系列事实应走轻量观察")
    void observesKnownSeriesWithoutEnrichment() {
        ArtworkSeriesObservation observation = new ArtworkSeriesObservation(
                42L, true, 7L, "series", 8L, null, null);

        adapter.observe(observation, "credential");

        verify(mangaSeriesService).observe(7L, "series", 8L);
        verify(mangaSeriesService, never()).observeWithMetadata(
                7L, "series", 8L, null, null, "credential");
    }

    @Test
    @DisplayName("已知丰富系列事实应携带短生命周期凭证补齐元数据")
    void observesKnownSeriesWithEnrichment() {
        ArtworkSeriesObservation observation = new ArtworkSeriesObservation(
                42L, true, 7L, "series", 8L, "description", "https://example.test/cover.jpg");

        adapter.observe(observation, "credential");

        verify(mangaSeriesService).observeWithMetadata(
                7L,
                "series",
                8L,
                "description",
                "https://example.test/cover.jpg",
                "credential"
        );
    }

    @Test
    @DisplayName("缺少系列事实且允许查询时应触发补齐")
    void resolvesMissingSeriesWhenAllowed() {
        ArtworkSeriesObservation observation = new ArtworkSeriesObservation(
                42L, true, null, null, null, null, null);

        adapter.observe(observation, "credential");

        verify(mangaSeriesService).asyncLookupMissingSeries(42L, "credential");
        verify(pixivDatabase, never()).updateSeriesInfo(42L, 0L, 0L);
    }

    @Test
    @DisplayName("已知非漫画作品应写入无系列事实而不联网")
    void marksSeriesAbsentWithoutLookup() {
        ArtworkSeriesObservation observation = new ArtworkSeriesObservation(
                42L, false, null, null, null, null, null);

        adapter.observe(observation, "credential");

        verify(pixivDatabase).updateSeriesInfo(42L, 0L, 0L);
        verify(mangaSeriesService, never()).asyncLookupMissingSeries(42L, "credential");
    }
}
