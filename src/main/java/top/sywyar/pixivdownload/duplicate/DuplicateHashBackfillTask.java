package top.sywyar.pixivdownload.duplicate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.maintenance.MaintenanceStatusHolder;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceContext;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.util.List;

@Slf4j
@PluginManagedBean
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
        // 一次性补齐全部缺哈希作品（不限批量）；「已尝试但无结果」的作品已被哨兵行标记，不会反复重试。
        List<Long> artworkIds = imageHashMapper.artworkIdsMissingHashes(Integer.MAX_VALUE);
        int total = artworkIds.size();
        int processed = 0;
        int written = 0;
        int scanned = 0;
        // 进度按「作品」粒度上报：GUI 据此显示已处理/总数，并按已用时 / 已处理量线性外推出自我修正的 ETA。
        MaintenanceStatusHolder.updateProgress(0, total);
        for (Long artworkId : artworkIds) {
            ArtworkRecord artwork = pixivDatabase.getArtwork(artworkId);
            if (artwork != null) {
                processed++;
                written += imageHashService.recordArtworkHashes(artwork, false);
            }
            scanned++;
            MaintenanceStatusHolder.updateProgress(scanned, total);
        }
        if (processed > 0) {
            duplicateService.invalidate();
        }
        log.info(messages.getForLog("duplicate.log.backfill.done", processed, written));
    }
}
