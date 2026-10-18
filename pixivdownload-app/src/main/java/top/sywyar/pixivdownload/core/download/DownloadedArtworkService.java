package top.sywyar.pixivdownload.core.download;

import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;

import java.util.LinkedList;
import java.util.List;

/**
 * 已下载插画记录的读取面：历史 / 判重 / 排序 / 计数。{@code verifyFiles} 去重在 DB 行之上叠加
 * 「实际目录检测」——文件不完整时允许重新下载，保留原登记和关联数据，
 * 查询不补写记录；磁盘文件的连续页号不能证明作品完整。
 */
@Service
public class DownloadedArtworkService {

    private final PixivDatabase pixivDatabase;
    private final ArtworkFileService artworkFileService;

    public DownloadedArtworkService(PixivDatabase pixivDatabase,
                                    ArtworkFileService artworkFileService) {
        this.pixivDatabase = pixivDatabase;
        this.artworkFileService = artworkFileService;
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
        try (var workFileLease = top.sywyar.pixivdownload.core.work.service.WorkFileLock.acquire(top.sywyar.pixivdownload.core.work.model.WorkType.ARTWORK, artworkId)) {
            ArtworkRecord artwork = pixivDatabase.getArtwork(artworkId);
            // 软删除记录保留判重依据，不再检查已删除的文件；由调用方按 deleted 决定是否重下。
            if (artwork != null && (artwork.deleted() || !verifyFiles || artworkFileService.hasArtworkFiles(artwork))) {
                return artwork;
            }
            return null;
        }
    }

    public List<Long> getSortTimeArtwork() {
        return pixivDatabase.getArtworkIdsSortedByTimeDesc();
    }

    public List<Long> getSortAuthorArtwork() {
        return pixivDatabase.getArtworkIdsSortedByAuthorIdAsc();
    }

    public List<Long> getSortTimeArtworkPaged(int page, int size) {
        return pixivDatabase.getArtworkIdsSortedByTimeDescPaged((long) page * size, size);
    }

    public List<Long> getSortAuthorArtworkPaged(int page, int size) {
        return pixivDatabase.getArtworkIdsSortedByAuthorIdAscPaged((long) page * size, size);
    }

    public long getArtworkCount() {
        return pixivDatabase.countArtworks();
    }

}
