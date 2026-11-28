package top.sywyar.pixivdownload.core.download;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.time.EpochMillisNormalizer;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkFileLock;

/** 将调用方已完成的文件移动登记到作品库，同时更新移动统计。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ArtworkMoveService {
    private final PixivDatabase pixivDatabase;

    public boolean recordMove(long artworkId, String movePath, Long moveTime, String classifierTargetFolder) {
        try (var lease = WorkFileLock.acquire(WorkType.ARTWORK, artworkId)) {
            boolean recorded = pixivDatabase.updateArtworkMove(artworkId, movePath,
                    EpochMillisNormalizer.normalize(moveTime), classifierTargetFolder);
            log.info("Artwork move registration id={} result={}", artworkId, recorded ? "recorded" : "not-found");
            return recorded;
        }
    }
}
