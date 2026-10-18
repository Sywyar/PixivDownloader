package top.sywyar.pixivdownload.duplicate;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.core.hash.ArtworkHashIndexMaintenance;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@PluginManagedBean
public class DuplicateScanService implements QueueOperations {

    private static final String SCAN_QUEUE_TYPE = "duplicate-scan";

    private final ArtworkHashIndexMaintenance hashIndexMaintenance;
    private final DuplicateService duplicateService;
    private final MessageResolver messages;
    private final top.sywyar.pixivdownload.core.database.DatabaseAvailability database;
    private final TaskExecutor taskExecutor;
    private final QueueTaskTracker taskTracker = new QueueTaskTracker(SCAN_QUEUE_TYPE);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String state = "IDLE";
    private volatile int processed;
    private volatile int total;
    private volatile Long startedTime;

    public DuplicateScanService(ArtworkHashIndexMaintenance hashIndexMaintenance,
                                DuplicateService duplicateService,
                                MessageResolver messages,
                                TaskExecutor taskExecutor, top.sywyar.pixivdownload.core.database.DatabaseAvailability database) {
        this.hashIndexMaintenance = hashIndexMaintenance;
        this.duplicateService = duplicateService;
        this.messages = messages;
        this.taskExecutor = taskExecutor;
        this.database = database;
    }

    public DuplicateDto.ScanStatus startScan(boolean force) {
        database.requireAvailable();
        QueueTaskTracker.Task task = taskTracker.beginRunning(null);
        boolean scanClaimed = false;
        boolean handedOff = false;
        try {
            if (!running.compareAndSet(false, true)) {
                return status();
            }
            scanClaimed = true;
            task.onTermination(() -> running.set(false));
            startedTime = System.currentTimeMillis();
            processed = 0;
            total = force
                    ? safeLongToInt(hashIndexMaintenance.artworkCount())
                    : hashIndexMaintenance.missingArtworkCount();
            state = "RUNNING";

            handedOff = task.handoff(() -> runScan(force, task));
            if (!handedOff) {
                resetCancelledState();
                return status();
            }
            try {
                taskExecutor.execute(task);
            } catch (RuntimeException | Error failure) {
                task.publishIfActive(() -> state = "FAILED");
                task.rejectSubmission();
                throw failure;
            }
            return status();
        } catch (RuntimeException | Error failure) {
            if (scanClaimed && !handedOff) {
                if (task.isCancellationRequested()) {
                    resetCancelledState();
                } else {
                    state = "FAILED";
                }
            }
            throw failure;
        } finally {
            if (!handedOff) {
                task.completeRunning();
            }
        }
    }

    public DuplicateDto.ScanStatus status() {
        return new DuplicateDto.ScanStatus(database.available() ? state : "UNAVAILABLE", processed, total, startedTime);
    }

    @Override
    public String queueType() {
        return SCAN_QUEUE_TYPE;
    }

    @Override
    public QueueGenerationDrain prepareQuiesce(String registeredQueueType) {
        return taskTracker.prepareQuiesce();
    }

    @Override
    public void cancelQuiescedTasks() {
        QueueGenerationDrain drain = taskTracker.prepareQuiesce();
        taskTracker.cancelQuiescedTasks();
        if (drain.isDrained() && "RUNNING".equals(state)) {
            resetCancelledState();
        }
    }

    @Override
    public int clearAll() {
        return 0;
    }

    @Override
    public int clearForOwner(String ownerUuid) {
        return 0;
    }

    private void runScan(boolean force, QueueTaskTracker.Task task) {
        try {
            log.info(messages.getForLog("duplicate.log.scan.started", force, total));
            database.requireAvailable();
            if (task.isCancellationRequested()) {
                resetCancelledState();
                return;
            }
            List<Long> artworkIds;
            if (force) {
                hashIndexMaintenance.clearAllHashes();
                artworkIds = hashIndexMaintenance.artworkIdsNewestFirst();
            } else {
                // 一次性快照缺哈希作品列表后遍历：若按批轮询，文件缺失/损坏/不可哈希的作品会
                // 始终写入 0 行、永远留在"缺哈希"集合里，导致 artworkIdsMissingHashes 永不返回空、循环无法终止。
                artworkIds = hashIndexMaintenance.artworkIdsMissingHashes(Integer.MAX_VALUE);
            }
            for (Long artworkId : artworkIds) {
                database.requireAvailable();
                if (task.isCancellationRequested()) {
                    resetCancelledState();
                    return;
                }
                recordArtwork(artworkId);
            }
            database.requireAvailable();
            if (task.isCancellationRequested()) {
                resetCancelledState();
                return;
            }
            state = "DONE";
            duplicateService.invalidate();
            log.info(messages.getForLog("duplicate.log.scan.done", processed, total));
        } catch (Exception e) {
            if (task.isCancellationRequested()) {
                resetCancelledState();
            } else {
                state = "FAILED";
                log.warn(messages.getForLog("duplicate.log.scan.failed", e.getMessage()), e);
            }
        }
    }

    @PreDestroy
    void destroy() {
        QueueGenerationDrain drain = prepareQuiesce(SCAN_QUEUE_TYPE);
        Throwable cancellationFailure = null;
        try {
            cancelQuiescedTasks();
        } catch (Throwable failure) {
            cancellationFailure = failure;
        }

        boolean interrupted = false;
        while (!drain.isDrained()) {
            if (!drain.awaitDrained()) {
                interrupted = true;
                Thread.interrupted();
            }
        }
        if ("RUNNING".equals(state)) {
            resetCancelledState();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        rethrow(cancellationFailure);
    }

    private void recordArtwork(Long artworkId) {
        database.requireAvailable();
        if (artworkId == null) {
            return;
        }
        hashIndexMaintenance.rebuildArtwork(artworkId);
        processed = total <= 0 ? processed + 1 : Math.min(processed + 1, total);
    }

    private int safeLongToInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(value, 0);
    }

    private void resetCancelledState() {
        state = "IDLE";
        processed = 0;
        total = 0;
        startedTime = null;
    }

    private static void rethrow(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("duplicate scan cancellation failed", failure);
    }
}
