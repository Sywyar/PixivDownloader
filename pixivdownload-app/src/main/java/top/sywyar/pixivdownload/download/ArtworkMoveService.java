package top.sywyar.pixivdownload.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.util.TimestampUtils;

/**
 * 把作品移动落库：图片分类器（{@code ImageClassifier} 桌面端经
 * {@code POST /api/downloaded/move/{artworkId}}）将作品移动到目标目录后，
 * 由本服务写入 {@code artworks.move_*} 并递增 {@code statistics.total_moved}。
 * <p>
 * 写核心 {@code artworks} / {@code statistics} 表、属下载事实，因此是核心 Spring 服务；
 * 与分类器的集成只发生在 REST 端点上（分类器经 HTTP 调用，本服务不持有任何分类器逻辑）。
 * 方法标 {@code @Async} 异步落库，经 CGLIB 代理生效；委托池化的 {@link PixivDatabase}，不自写 SQL。
 */
@Slf4j
@Service
public class ArtworkMoveService {

    private final PixivDatabase pixivDatabase;
    private final AppMessages messages;

    public ArtworkMoveService(PixivDatabase pixivDatabase, AppMessages messages) {
        this.pixivDatabase = pixivDatabase;
        this.messages = messages;
    }

    @Async
    public void moveArtWork(Long artworkId, String movePath, Long moveTime) {
        moveArtWork(artworkId, movePath, moveTime, null);
    }

    @Async
    public void moveArtWork(Long artworkId, String movePath, Long moveTime, String classifierTargetFolder) {
        try {
            ArtworkRecord existing = pixivDatabase.getArtwork(artworkId);
            if (existing == null) {
                return;
            }
            pixivDatabase.updateArtworkMove(artworkId, movePath,
                    TimestampUtils.toMillis(moveTime), classifierTargetFolder);
            if (!existing.moved()) {
                pixivDatabase.incrementMoved();
            }
        } catch (Exception e) {
            log.error(messages.getForLog("download.log.move-record.failed", e.getMessage()), e);
        }
    }
}
