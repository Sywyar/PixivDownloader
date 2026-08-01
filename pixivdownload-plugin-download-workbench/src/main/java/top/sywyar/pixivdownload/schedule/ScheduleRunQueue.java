package top.sywyar.pixivdownload.schedule;

import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 计划任务<b>每轮运行的作品队列</b>登记（仅内存，不落库）。
 *
 * <p>与 {@link ScheduleRunState}（只记 QUEUED / RUNNING 这两个瞬时灯态）互补：本登记记录某一轮
 * 实际发现到的<b>每一个作品</b>及其处理结果，供前端在任务卡片底部的「本轮队列详情」可折叠区域展示。
 *
 * <p>每个任务只保留<b>最近一轮</b>的队列：下一轮运行 {@link #begin(long)} 即整体替换旧队列
 * （= 文案里的「下一次任务刷新」）。进程退出后所有队列自然消失；前端用 localStorage 缓存渲染结果，
 * 因此重启后仍能展示上一份，直到任务再次运行刷新。
 *
 * <p>单轮队列同时受 {@link #MAX_ITEMS} 条目数与 {@link #MAX_RETAINED_UTF8_BYTES} 原始 UTF-8
 * 聚合字节预算约束：USER_NEW 首轮等大集合会发现成千上万个作品，超限后只继续下载、不再逐条登记
 * （{@link Run#truncated()} 置位，前端给出「列表过长」提示），避免插件用接近单字段上限的安全文本
 * 组合出过大的内存队列或响应体。
 *
 * <p>调度按串行单线程写入，HTTP 读线程并发读取，故 {@link Run} 的读写都在其自身锁内完成、
 * 对外只暴露 {@link Run#snapshot()} 的拷贝，避免并发遍历。
 */
@PluginManagedBean
public class ScheduleRunQueue {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_DOWNLOADED = "downloaded";
    public static final String STATUS_SKIPPED_DOWNLOADED = "skipped-downloaded";
    public static final String STATUS_SKIPPED_FILTER = "skipped-filter";
    public static final String STATUS_FAILED = "failed";

    /** 单轮队列最多逐条登记的作品数；超出后只计下载、不再记录条目，防止大集合撑爆内存 / 响应体。 */
    static final int MAX_ITEMS = 5000;
    /** 单轮队列保留字段的原始 UTF-8 聚合预算；Java 对象与 JSON 转义开销另由条目数上限兜底。 */
    static final long MAX_RETAINED_UTF8_BYTES = 2L * 1024L * 1024L;

    private final ConcurrentMap<Long, Run> runs = new ConcurrentHashMap<>();

    /** 开始新一轮：整体替换该任务的旧队列并返回新队列供执行期写入。 */
    public Run begin(long taskId) {
        Run run = new Run(System.currentTimeMillis());
        runs.put(taskId, run);
        return run;
    }

    /** 取该任务最近一轮队列；从未运行（或进程重启后）返回 {@code null}。 */
    public Run get(long taskId) {
        return runs.get(taskId);
    }

    /** 任务删除时连带清除其队列。 */
    public void remove(long taskId) {
        runs.remove(taskId);
    }

    /** 不入登记表的游离队列，仅供单元测试构造 {@link Run} 而无需经 Spring 容器。 */
    static Run detachedRun() {
        return new Run(System.currentTimeMillis());
    }

    /** 一轮运行的队列：保留发现顺序，按作品类型与 ID 的复合身份增量更新元数据与状态。 */
    public static final class Run {

        private final long startedTime;
        private final List<ScheduledWorkKey> order = new ArrayList<>();
        private final Map<ScheduledWorkKey, Item> byKey = new HashMap<>();
        private long retainedUtf8Bytes;
        private boolean truncated;

        Run(long startedTime) {
            this.startedTime = startedTime;
        }

        /** 发现一个作品（按发现顺序追加，重复复合身份幂等）；超过上限只置 truncated、不再记录。 */
        public synchronized void discovered(
                ScheduledWork work,
                ScheduleCapabilityOwner workExecutorOwner,
                long workExecutorPublicationId) {
            if (work == null) {
                return;
            }
            Objects.requireNonNull(workExecutorOwner, "workExecutorOwner");
            if (workExecutorPublicationId <= 0L) {
                throw new IllegalArgumentException(
                        "work executor publication id must be positive");
            }
            ScheduledWorkKey key = work.key();
            if (byKey.containsKey(key)) {
                return;
            }
            if (truncated || order.size() >= MAX_ITEMS) {
                truncated = true;
                return;
            }
            Item item = Item.pending(
                    key,
                    work.presentation(),
                    workExecutorOwner,
                    workExecutorPublicationId);
            long itemBytes = itemUtf8Bytes(item);
            if (itemBytes > MAX_RETAINED_UTF8_BYTES - retainedUtf8Bytes) {
                truncated = true;
                return;
            }
            order.add(key);
            byKey.put(key, item);
            retainedUtf8Bytes += itemBytes;
        }

        /** 更新某作品的处理状态与可选说明（未登记的作品 ID 直接忽略）。 */
        public synchronized void mark(
                ScheduledWorkKey key, String status, String message) {
            Item item = byKey.get(key);
            if (item == null) {
                return;
            }
            replaceOrDrop(key, item, item.withState(status, message, item.result()));
        }

        /**
         * 保存作品执行器经宿主安全校验后的结果，并更新中性宿主状态。结果属性保持插件自有机器数据，
         * 队列不解释具体作品类型、属性名或插件私有阶段。
         */
        public synchronized void markResult(
                ScheduledWorkKey key,
                ScheduledWorkResult result,
                String status,
                String message) {
            Item item = byKey.get(key);
            if (item == null) {
                return;
            }
            replaceOrDrop(
                    key,
                    item,
                    item.withState(
                            status,
                            message,
                            Objects.requireNonNull(result, "result")));
        }

        public synchronized long startedTime() {
            return startedTime;
        }

        public synchronized boolean truncated() {
            return truncated;
        }

        synchronized long retainedUtf8Bytes() {
            return retainedUtf8Bytes;
        }

        /** 拷贝当前全部条目，供对外视图组装；调用方拿到的是快照，不随后续写入变化。 */
        public synchronized List<Item> snapshot() {
            List<Item> copy = new ArrayList<>(order.size());
            for (ScheduledWorkKey key : order) {
                copy.add(byKey.get(key).copy());
            }
            return List.copyOf(copy);
        }

        private void replaceOrDrop(
                ScheduledWorkKey key,
                Item current,
                Item replacement) {
            long currentBytes = itemUtf8Bytes(current);
            long replacementBytes = itemUtf8Bytes(replacement);
            long withoutCurrent = retainedUtf8Bytes - currentBytes;
            if (replacementBytes > MAX_RETAINED_UTF8_BYTES - withoutCurrent) {
                byKey.remove(key);
                order.remove(key);
                retainedUtf8Bytes = withoutCurrent;
                truncated = true;
                return;
            }
            byKey.put(key, replacement);
            retainedUtf8Bytes = withoutCurrent + replacementBytes;
        }
    }

    /**
     * 队列中的单个中性作品值。只保存稳定作品身份、安全展示快照、已校验执行结果和宿主机器状态。
     */
    public record Item(
            ScheduledWorkKey key,
            ScheduledWorkPresentation presentation,
            ScheduleCapabilityOwner workExecutorOwner,
            long workExecutorPublicationId,
            ScheduledWorkResult result,
            String status,
            String message) {

        public Item {
            key = Objects.requireNonNull(key, "key");
            workExecutorOwner = Objects.requireNonNull(
                    workExecutorOwner, "workExecutorOwner");
            if (workExecutorPublicationId <= 0L) {
                throw new IllegalArgumentException(
                        "work executor publication id must be positive");
            }
            presentation = presentation == null
                    ? ScheduledWorkPresentation.empty()
                    : presentation;
            status = Objects.requireNonNull(status, "status");
        }

        static Item pending(
                ScheduledWorkKey key,
                ScheduledWorkPresentation presentation,
                ScheduleCapabilityOwner workExecutorOwner,
                long workExecutorPublicationId) {
            return new Item(
                    key,
                    presentation,
                    workExecutorOwner,
                    workExecutorPublicationId,
                    null,
                    STATUS_PENDING,
                    null);
        }

        Item withState(
                String nextStatus,
                String nextMessage,
                ScheduledWorkResult nextResult) {
            return new Item(
                    key,
                    presentation,
                    workExecutorOwner,
                    workExecutorPublicationId,
                    nextResult,
                    nextStatus,
                    nextMessage);
        }

        Item copy() {
            return new Item(
                    key,
                    presentation,
                    workExecutorOwner,
                    workExecutorPublicationId,
                    result,
                    status,
                    message);
        }
    }

    private static long itemUtf8Bytes(Item item) {
        long total = utf8Bytes(item.key().workType()) + utf8Bytes(item.key().id());
        total += utf8Bytes(item.workExecutorOwner().featurePluginId());
        total += utf8Bytes(item.workExecutorOwner().packageId());
        total += Long.BYTES * 2L;
        ScheduledWorkPresentation presentation = item.presentation();
        total += utf8Bytes(presentation.title());
        total += utf8Bytes(presentation.author());
        total += utf8Bytes(presentation.thumbnailReference());
        total += mapUtf8Bytes(presentation.attributes());
        ScheduledWorkResult result = item.result();
        if (result != null) {
            total += utf8Bytes(result.resultCode());
            total += mapUtf8Bytes(result.attributes());
        }
        total += utf8Bytes(item.status());
        total += utf8Bytes(item.message());
        return total;
    }

    private static long mapUtf8Bytes(Map<String, String> values) {
        long total = 0L;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            total += utf8Bytes(entry.getKey());
            total += utf8Bytes(entry.getValue());
        }
        return total;
    }

    private static int utf8Bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }
}
