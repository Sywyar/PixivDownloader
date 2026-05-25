package top.sywyar.pixivdownload.duplicate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.download.db.ArtworkRecord;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.maintenance.MaintenanceContext;
import top.sywyar.pixivdownload.maintenance.MaintenanceTask;

@Slf4j
@Component
@Order(150)
@RequiredArgsConstructor
public class DuplicateHashBackfillTask implements MaintenanceTask {

    private final ImageHashMapper imageHashMapper;
    private final ImageHashService imageHashService;
    private final DuplicateService duplicateService;
    private final PixivDatabase pixivDatabase;
    private final AppMessages messages;

    @Override
    public String name() {
        return "duplicate-hash-backfill";
    }

    @Override
    public void execute(MaintenanceContext context) {
        int processed = 0;
        int written = 0;
        // 一次性补齐全部缺哈希作品（不限批量）；「已尝试但无结果」的作品已被哨兵行标记，不会反复重试。
        for (Long artworkId : imageHashMapper.artworkIdsMissingHashes(Integer.MAX_VALUE)) {
            ArtworkRecord artwork = pixivDatabase.getArtwork(artworkId);
            if (artwork == null) {
                continue;
            }
            processed++;
            written += imageHashService.recordArtworkHashes(artwork, false);
        }
        if (processed > 0) {
            duplicateService.invalidate();
        }
        log.info(messages.getForLog("duplicate.log.backfill.done", processed, written));
    }
}
