package top.sywyar.pixivdownload.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.util.LinkedList;
import java.util.List;

/**
 * 已下载插画记录的读取面：历史 / 判重 / 排序 / 计数。{@code verifyFiles} 去重在 DB 行之上叠加
 * 「实际目录检测」——磁盘缺文件则删陈旧记录（{@link ArtworkFileService#hasArtworkFiles}），
 * DB 无记录但磁盘有匹配文件则补登记（{@link ArtworkMetadataRecoveryService#findArtworkOnDisk}）。
 */
@Slf4j
@Service
public class DownloadedArtworkService {

    private final PixivDatabase pixivDatabase;
    private final ArtworkFileService artworkFileService;
    private final ArtworkMetadataRecoveryService artworkMetadataRecoveryService;
    private final ArtworkFileLocator artworkFileLocator;
    private final AppMessages messages;

    public DownloadedArtworkService(PixivDatabase pixivDatabase,
                                    ArtworkFileService artworkFileService,
                                    ArtworkMetadataRecoveryService artworkMetadataRecoveryService,
                                    ArtworkFileLocator artworkFileLocator,
                                    AppMessages messages) {
        this.pixivDatabase = pixivDatabase;
        this.artworkFileService = artworkFileService;
        this.artworkMetadataRecoveryService = artworkMetadataRecoveryService;
        this.artworkFileLocator = artworkFileLocator;
        this.messages = messages;
    }

    public List<String> getDownloadedRecord() {
        List<String> ids = new LinkedList<>();
        pixivDatabase.getAllArtworkIds().forEach(id -> ids.add(String.valueOf(id)));
        return ids;
    }

    public ArtworkRecord getDownloadedRecord(Long artworkId) {
        return pixivDatabase.getArtwork(artworkId);
    }

    public ArtworkRecord getDownloadedRecord(Long artworkId, boolean verifyFiles) {
        ArtworkRecord artwork = pixivDatabase.getArtwork(artworkId);
        if (artwork != null) {
            // 软删除标记的记录磁盘文件本就已删：跳过实际目录检测，更不能当陈旧记录清掉
            // （那会抹掉「已下载过，但被删除」的判重依据）。原样返回，由调用方按 deleted 决策。
            if (artwork.deleted()) {
                return artwork;
            }
            if (!verifyFiles) {
                return artwork;
            }
            if (artworkFileService.hasArtworkFiles(artwork)) {
                return artwork;
            }
            removeStaleArtworkRecord(artwork);
            return null;
        }
        if (verifyFiles) {
            return artworkMetadataRecoveryService.findArtworkOnDisk(artworkId);
        }
        return null;
    }

    private void removeStaleArtworkRecord(ArtworkRecord artwork) {
        try {
            pixivDatabase.deleteArtwork(artwork.artworkId());
            log.info(logMessage("download.log.stale-record.deleted",
                    id(artwork.artworkId()), artworkFileLocator.resolveArtworkDirectory(artwork)));
        } catch (Exception e) {
            log.warn(logMessage("download.log.stale-record.delete-failed", id(artwork.artworkId())), e);
        }
    }

    public List<Long> getSortTimeArtwork() {
        return pixivDatabase.getArtworkIdsSortedByTimeDesc();
    }

    public List<Long> getSortAuthorArtwork() {
        return pixivDatabase.getArtworkIdsSortedByAuthorIdAsc();
    }

    public List<Long> getSortTimeArtworkPaged(int page, int size) {
        return pixivDatabase.getArtworkIdsSortedByTimeDescPaged(page * size, size);
    }

    public List<Long> getSortAuthorArtworkPaged(int page, int size) {
        return pixivDatabase.getArtworkIdsSortedByAuthorIdAscPaged(page * size, size);
    }

    public long getArtworkCount() {
        return pixivDatabase.countArtworks();
    }

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }

    private String id(Long value) {
        return value == null ? "null" : String.valueOf(value);
    }
}
