package top.sywyar.pixivdownload.duplicate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class DuplicateScanService {

    private final ImageHashMapper imageHashMapper;
    private final ImageHashService imageHashService;
    private final DuplicateService duplicateService;
    private final PixivDatabase pixivDatabase;
    private final AppMessages messages;
    private final TaskExecutor taskExecutor;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String state = "IDLE";
    private volatile int processed;
    private volatile int total;
    private volatile Long startedTime;

    public DuplicateScanService(ImageHashMapper imageHashMapper,
                                ImageHashService imageHashService,
                                DuplicateService duplicateService,
                                PixivDatabase pixivDatabase,
                                AppMessages messages,
                                @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.imageHashMapper = imageHashMapper;
        this.imageHashService = imageHashService;
        this.duplicateService = duplicateService;
        this.pixivDatabase = pixivDatabase;
        this.messages = messages;
        this.taskExecutor = taskExecutor;
    }

    public DuplicateDto.ScanStatus startScan(boolean force) {
        if (!running.compareAndSet(false, true)) {
            return status();
        }
        startedTime = System.currentTimeMillis();
        processed = 0;
        total = force ? safeLongToInt(pixivDatabase.countArtworks()) : imageHashMapper.countArtworksMissingHashes();
        state = "RUNNING";
        taskExecutor.execute(() -> runScan(force));
        return status();
    }

    public DuplicateDto.ScanStatus status() {
        return new DuplicateDto.ScanStatus(state, processed, total, startedTime);
    }

    private void runScan(boolean force) {
        log.info(messages.getForLog("duplicate.log.scan.started", force, total));
        try {
            if (force) {
                imageHashMapper.deleteAll();
                for (Long artworkId : pixivDatabase.getArtworkIdsSortedByTimeDesc()) {
                    recordArtwork(artworkId);
                }
            } else {
                // 一次性快照缺哈希作品列表后遍历：若按批轮询，文件缺失/损坏/不可哈希的作品会
                // 始终写入 0 行、永远留在"缺哈希"集合里，导致 artworkIdsMissingHashes 永不返回空、循环无法终止。
                for (Long artworkId : imageHashMapper.artworkIdsMissingHashes(Integer.MAX_VALUE)) {
                    recordArtwork(artworkId);
                }
            }
            state = "DONE";
            duplicateService.invalidate();
            log.info(messages.getForLog("duplicate.log.scan.done", processed, total));
        } catch (Exception e) {
            state = "FAILED";
            log.warn(messages.getForLog("duplicate.log.scan.failed", e.getMessage()), e);
        } finally {
            running.set(false);
        }
    }

    private void recordArtwork(Long artworkId) {
        if (artworkId == null) {
            return;
        }
        ArtworkRecord artwork = pixivDatabase.getArtwork(artworkId);
        if (artwork != null) {
            imageHashService.recordArtworkHashes(artwork, false);
        }
        processed = total <= 0 ? processed + 1 : Math.min(processed + 1, total);
    }

    private int safeLongToInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(value, 0);
    }
}
