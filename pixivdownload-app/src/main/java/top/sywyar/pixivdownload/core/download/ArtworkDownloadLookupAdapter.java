package top.sywyar.pixivdownload.core.download;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadLookup;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * 将插画判重端口适配到宿主的只读记录查询与可选文件校验。
 */
@Component
public class ArtworkDownloadLookupAdapter implements ArtworkDownloadLookup {

    private final DownloadedArtworkService downloadedArtworkService;
    private final ArtworkFileLocator files;

    public ArtworkDownloadLookupAdapter(DownloadedArtworkService downloadedArtworkService,
                                       ArtworkFileLocator files) {
        this.downloadedArtworkService = downloadedArtworkService;
        this.files = files;
    }

    @Override
    public Optional<Set<Path>> registeredOutputs(long artworkId) throws IOException {
        var record = downloadedArtworkService.getDownloadedRecord(artworkId);
        return record == null || record.deleted() ? Optional.empty() : Optional.of(files.managedOutputs(record));
    }

    @Override
    public boolean isDownloaded(long artworkId, boolean verifyFiles) {
        return downloadedArtworkService.getDownloadedRecord(artworkId, verifyFiles) != null;
    }
}
