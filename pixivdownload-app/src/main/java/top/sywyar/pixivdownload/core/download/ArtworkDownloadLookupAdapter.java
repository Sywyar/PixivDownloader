package top.sywyar.pixivdownload.core.download;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadLookup;

/**
 * 将插画判重端口适配到宿主的只读记录查询与可选文件校验。
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
