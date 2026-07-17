package top.sywyar.pixivdownload.duplicate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import top.sywyar.pixivdownload.core.hash.ArtworkHashIndexMaintenance;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceContext;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.util.List;
import java.util.OptionalInt;

@Slf4j
@PluginManagedBean
@Order(150)
@RequiredArgsConstructor
public class DuplicateHashBackfillTask implements MaintenanceTask {

    private final ArtworkHashIndexMaintenance hashIndexMaintenance;
    private final DuplicateService duplicateService;
    private final MessageResolver messages;

    @Override
    public String name() {
        return "duplicate-hash-backfill";
    }

    @Override
    public void execute(MaintenanceContext context) {
        // 一次性补齐全部缺哈希作品（不限批量）；「已尝试但无结果」的作品已被哨兵行标记，不会反复重试。
        List<Long> artworkIds = hashIndexMaintenance.artworkIdsMissingHashes(Integer.MAX_VALUE);
        int total = artworkIds.size();
        int processed = 0;
        int written = 0;
        int scanned = 0;
        // 进度按「作品」粒度上报：GUI 据此显示已处理/总数，并按已用时 / 已处理量线性外推出自我修正的 ETA。
        context.updateProgress(0, total);
        for (Long artworkId : artworkIds) {
            OptionalInt result = artworkId == null
                    ? OptionalInt.empty()
                    : hashIndexMaintenance.rebuildArtwork(artworkId);
            if (result.isPresent()) {
                processed++;
                written += result.getAsInt();
            }
            scanned++;
            context.updateProgress(scanned, total);
        }
        if (processed > 0) {
            duplicateService.invalidate();
        }
        log.info(messages.getForLog("duplicate.log.backfill.done", processed, written));
    }
}
