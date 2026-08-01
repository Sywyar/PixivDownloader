package top.sywyar.pixivdownload.core.download;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadLookup;

/**
 * 将插画判重端口适配到带磁盘校验与历史恢复语义的宿主服务。
 */
@Component
public class ArtworkDownloadLookupAdapter implements ArtworkDownloadLookup {

    private final DownloadedArtworkService downloadedArtworkService;

    public ArtworkDownloadLookupAdapter(DownloadedArtworkService downloadedArtworkService) {
        this.downloadedArtworkService = downloadedArtworkService;
    }

    @Override
    public boolean isDownloaded(long artworkId, boolean verifyFiles) {
        return downloadedArtworkService.getDownloadedRecord(artworkId, verifyFiles) != null;
    }
}
