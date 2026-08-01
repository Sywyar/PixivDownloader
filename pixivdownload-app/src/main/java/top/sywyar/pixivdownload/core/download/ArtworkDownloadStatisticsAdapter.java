package top.sywyar.pixivdownload.core.download;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadStatistics;

/**
 * 将完成下载统计端口适配到宿主累计统计服务。
 */
@Component
public class ArtworkDownloadStatisticsAdapter implements ArtworkDownloadStatistics {

    private final DownloadStatisticsService downloadStatisticsService;

    public ArtworkDownloadStatisticsAdapter(DownloadStatisticsService downloadStatisticsService) {
        this.downloadStatisticsService = downloadStatisticsService;
    }

    @Override
    public void recordCompleted(int imageCount) {
        downloadStatisticsService.recordStatistics(imageCount);
    }
}
