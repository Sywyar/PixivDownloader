package top.sywyar.pixivdownload.schedule.batch;

import tools.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.work.*;
import top.sywyar.pixivdownload.schedule.ScheduleRunQueue;
import top.sywyar.pixivdownload.schedule.persistence.ScheduleWorkPersistenceCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单个手动批次的完整作品账本，在派发前检查容量，跨轮保留发现顺序和已完成作品的归属。 */
final class BatchWorkLedger extends ScheduleRunQueue {
    static final int MAX_ITEMS = 5000;
    static final long MAX_BYTES = 64L * 1024 * 1024;
    private final LedgerRun run;

    BatchWorkLedger(ObjectMapper json) { run = new LedgerRun(json); }
    @Override public Run begin(long taskId) { return run; }
    @Override public Run get(long taskId) { return run; }

    List<ScheduledPendingWork> retryRows(long taskId, ScheduleWorkPersistenceCodec codec, Map<ScheduledWorkKey, ScheduledPendingWork> pending) {
        return run.retryRows(taskId, codec, pending);
    }

    boolean capacityExceeded() { return run.capacityExceeded; }
    void resetCapacityFailure() { run.capacityExceeded = false; }
    static final String DISCOVERY_INCOMPLETE = "batch.discovery-incomplete";
    void pauseUndispatched(long taskId, ScheduleWorkPersistenceCodec codec, Map<ScheduledWorkKey, ScheduledPendingWork> pending) {
        run.pauseUndispatched(taskId, codec, pending);
    }
    record Counts(int total, long completed, long skipped, long failed, long undispatched) {}
    Counts counts() { return run.counts(); }
    ManualBatchService.Items page(int offset, int limit) { return run.page(offset, limit); }

    private static final class LedgerRun extends Run {
        private final ObjectMapper json;
        private final Map<ScheduledWorkKey, ScheduledWork> works = new LinkedHashMap<>();
        private final Map<ScheduledWorkKey, Item> items = new LinkedHashMap<>();
        private long bytes;
        private volatile boolean capacityExceeded;

        LedgerRun(ObjectMapper json) { super(System.currentTimeMillis()); this.json = json; }

        @Override public synchronized void discovered(ScheduledWork work, ScheduleCapabilityOwner owner, long publication) {
            var key = work.key();
            var previous = works.get(key);
            if (alreadyResolved(key)) return;
            // 同一作品换载荷时只结算大小差额，拒绝超限替换时保留旧信封。
            long cost = 0;
            if (!work.equals(previous)) {
                cost = 2L * json.writeValueAsBytes(work).length;
                cost += previous == null ? ScheduledWorkResult.MAX_ATTRIBUTE_TOTAL_BYTES + 1024
                        : -2L * json.writeValueAsBytes(previous).length;
            }
            if ((previous == null && works.size() >= MAX_ITEMS) || cost > MAX_BYTES - bytes) {
                capacityExceeded = true;
                throw new CapacityExceeded();
            }
            works.put(key, work);
            var item = items.get(key);
            items.put(key, new Item(key, work.presentation(), item == null ? owner : item.workExecutorOwner(),
                    item == null ? publication : item.workExecutorPublicationId(), null, STATUS_PENDING, null));
            bytes += cost;
        }

        @Override public synchronized void dispatched(ScheduledWorkKey key, ScheduleCapabilityOwner owner, long publicationId) {
            var item = items.get(key);
            if (!STATUS_PENDING.equals(item.status()) || item.workExecutorPublicationId() != publicationId) {
                items.put(key, new Item(key, item.presentation(), owner, publicationId, null, STATUS_PENDING, null));
            }
        }

        @Override public synchronized void mark(ScheduledWorkKey key, String status, String message) {
            var item = items.get(key);
            if (item != null) items.put(key, replace(item, item.result(), status, message));
        }

        @Override public synchronized void markResult(ScheduledWorkKey key, ScheduledWorkResult result, String status, String message) {
            var item = items.get(key);
            if (item != null) items.put(key, replace(item, result, status, message));
        }

        private static Item replace(Item item, ScheduledWorkResult result, String status, String message) {
            return new Item(item.key(), item.presentation(), item.workExecutorOwner(), item.workExecutorPublicationId(), result, status, message);
        }

        @Override public synchronized boolean alreadyResolved(ScheduledWorkKey key) {
            var item = items.get(key);
            return item != null && (STATUS_DOWNLOADED.equals(item.status())
                    || STATUS_SKIPPED_DOWNLOADED.equals(item.status()) || STATUS_SKIPPED_FILTER.equals(item.status()));
        }

        @Override public synchronized List<Item> snapshot() { return List.copyOf(items.values()); }
        @Override public boolean truncated() { return false; }

        synchronized Counts counts() {
            long completed = 0, skipped = 0, failed = 0, undispatched = 0;
            for (var item : items.values()) {
                if (STATUS_DOWNLOADED.equals(item.status())) completed++;
                else if (item.status().startsWith("skipped-")) skipped++;
                else if (STATUS_FAILED.equals(item.status()) || STATUS_PAUSED.equals(item.status())) {
                    failed++;
                    if (STATUS_PAUSED.equals(item.status()) && DISCOVERY_INCOMPLETE.equals(item.message())) undispatched++;
                }
            }
            return new Counts(items.size(), completed, skipped, failed, undispatched);
        }

        synchronized void pauseUndispatched(long taskId, ScheduleWorkPersistenceCodec codec, Map<ScheduledWorkKey, ScheduledPendingWork> pending) {
            for (var entry : items.entrySet()) {
                var item = entry.getValue();
                if (!STATUS_PENDING.equals(item.status())) continue;
                pending.computeIfAbsent(item.key(), key -> codec.toPendingWork(taskId, works.get(key), DISCOVERY_INCOMPLETE,
                        "{}", 0, System.currentTimeMillis(), null));
                entry.setValue(replace(item, null, STATUS_PAUSED, DISCOVERY_INCOMPLETE));
            }
        }

        synchronized ManualBatchService.Items page(int offset, int limit) {
            int start = Math.min(offset, items.size());
            return new ManualBatchService.Items(start, items.size(), items.values().stream().skip(start).limit(limit).toList());
        }

        synchronized List<ScheduledPendingWork> retryRows(long taskId, ScheduleWorkPersistenceCodec codec, Map<ScheduledWorkKey, ScheduledPendingWork> pending) {
            var retry = new ArrayList<ScheduledPendingWork>();
            for (var item : items.values()) {
                if (!STATUS_FAILED.equals(item.status()) && !STATUS_PAUSED.equals(item.status())) continue;
                var row = pending.get(item.key());
                if (row == null) {
                    retry.add(codec.toPendingWork(taskId, works.get(item.key()), item.message() == null ? "schedule.work.retry" : item.message(),
                            "{}", 0, System.currentTimeMillis(), null));
                } else {
                    retry.add(new ScheduledPendingWork(row.taskId(), row.workType(), row.workId(), row.payloadSchema(), row.payloadVersion(),
                            row.payloadJson(), row.relationsJson(), row.presentationJson(), row.reasonCode(), row.reasonDetailJson(), 0,
                            row.firstSeenTime(), row.lastAttemptTime()));
                }
            }
            return retry;
        }
    }

    static final class CapacityExceeded extends IllegalStateException {
        CapacityExceeded() { super("batch.work.capacity-exceeded"); }
    }
}
