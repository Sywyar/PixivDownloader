package top.sywyar.pixivdownload.series;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkSeriesObservation;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkSeriesObserver;

/**
 * 将插画系列观察命令适配到系列 owner 的持久化与补齐策略。
 */
@Component
public class ArtworkSeriesObservationAdapter implements ArtworkSeriesObserver {

    private final MangaSeriesService mangaSeriesService;
    private final PixivDatabase pixivDatabase;

    public ArtworkSeriesObservationAdapter(MangaSeriesService mangaSeriesService,
                                           PixivDatabase pixivDatabase) {
        this.mangaSeriesService = mangaSeriesService;
        this.pixivDatabase = pixivDatabase;
    }

    @Override
    public void observe(ArtworkSeriesObservation observation, String credential) {
        Long seriesId = observation.seriesId();
        if (seriesId != null && seriesId > 0) {
            if (StringUtils.hasText(observation.description())
                    || StringUtils.hasText(observation.coverUrl())) {
                mangaSeriesService.observeWithMetadata(
                        seriesId,
                        observation.title(),
                        observation.authorId(),
                        observation.description(),
                        observation.coverUrl(),
                        credential
                );
            } else {
                mangaSeriesService.observe(seriesId, observation.title(), observation.authorId());
            }
            return;
        }
        if (observation.lookupWhenMissing()) {
            mangaSeriesService.asyncLookupMissingSeries(
                    observation.artworkId(),
                    credential
            );
            return;
        }
        pixivDatabase.updateSeriesInfo(
                observation.artworkId(),
                MangaSeriesService.NO_SERIES_SENTINEL,
                MangaSeriesService.NO_SERIES_SENTINEL
        );
    }
}
