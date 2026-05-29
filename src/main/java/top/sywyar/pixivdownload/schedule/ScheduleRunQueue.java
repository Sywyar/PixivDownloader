package top.sywyar.pixivdownload.schedule;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 计划任务<b>每轮运行的作品队列</b>登记（仅内存，不落库）。
 *
 * <p>与 {@link ScheduleRunState}（只记 QUEUED / RUNNING 这两个瞬时灯态）互补：本登记记录某一轮
 * 实际发现到的<b>每一个作品</b>及其处理结果，供前端在任务卡片底部的「本轮队列详情」可折叠区域展示。
 *
 * <p>每个任务只保留<b>最近一轮</b>的队列：下一轮运行 {@link #begin(long, String)} 即整体替换旧队列
 * （= 文案里的「下一次任务刷新」）。进程退出后所有队列自然消失；前端用 localStorage 缓存渲染结果，
 * 因此重启后仍能展示上一份，直到任务再次运行刷新。
 *
 * <p>单轮队列以 {@link #MAX_ITEMS} 为上限：USER_NEW 首轮等大集合会发现成千上万个作品，超出上限后
 * 只继续下载、不再逐条登记（{@link Run#truncated()} 置位，前端给出「列表过长」提示），避免内存 / 响应体爆量。
 *
 * <p>调度按串行单线程写入，HTTP 读线程并发读取，故 {@link Run} 的读写都在其自身锁内完成、
 * 对外只暴露 {@link Run#snapshot()} 的拷贝，避免并发遍历。
 */
@Component
public class ScheduleRunQueue {

    public static final String KIND_ILLUST = "illust";
    public static final String KIND_NOVEL = "novel";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_DOWNLOADED = "downloaded";
    public static final String STATUS_SKIPPED_DOWNLOADED = "skipped-downloaded";
    public static final String STATUS_SKIPPED_FILTER = "skipped-filter";
    public static final String STATUS_FAILED = "failed";

    /** 单轮队列最多逐条登记的作品数；超出后只计下载、不再记录条目，防止大集合撑爆内存 / 响应体。 */
    static final int MAX_ITEMS = 5000;

    private final ConcurrentMap<Long, Run> runs = new ConcurrentHashMap<>();

    /** 开始新一轮：整体替换该任务的旧队列并返回新队列供执行期写入。 */
    public Run begin(long taskId, String kind) {
        Run run = new Run(System.currentTimeMillis(), kind);
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
    static Run detachedRun(String kind) {
        return new Run(System.currentTimeMillis(), kind);
    }

    /** 一轮运行的队列：保留发现顺序，按作品 ID 增量更新元数据与状态。 */
    public static final class Run {

        private final long startedTime;
        private final String kind;
        private final List<Item> order = new ArrayList<>();
        private final Map<String, Item> byId = new HashMap<>();
        private boolean truncated;

        Run(long startedTime, String kind) {
            this.startedTime = startedTime;
            this.kind = kind;
        }

        /** 发现一个作品（按发现顺序追加，重复 ID 幂等）；超过上限只置 truncated、不再记录。 */
        public synchronized void discovered(String id) {
            discovered(id, kind);
        }

        /**
         * 发现一个作品并指定其类型（插画/小说）。用于珍藏集等<b>混合</b>来源：同一轮内不同成员可有各自的 kind，
         * 不再统一沿用 run 级 kind。{@code itemKind} 为 {@code null} 时回退到 run 级 kind。
         */
        public synchronized void discovered(String id, String itemKind) {
            if (id == null || byId.containsKey(id)) {
                return;
            }
            if (order.size() >= MAX_ITEMS) {
                truncated = true;
                return;
            }
            Item item = new Item(id, itemKind == null ? kind : itemKind);
            order.add(item);
            byId.put(id, item);
        }

        /** 抓到元数据后补全标题 / 分级 / AI 标记（未登记的作品 ID 直接忽略）。 */
        public synchronized void setMeta(String id, String title, Integer xRestrict, Boolean ai) {
            Item item = byId.get(id);
            if (item == null) {
                return;
            }
            item.title = title;
            item.xRestrict = xRestrict;
            item.ai = ai;
        }

        /** 更新某作品的处理状态与可选说明（未登记的作品 ID 直接忽略）。 */
        public synchronized void mark(String id, String status, String message) {
            Item item = byId.get(id);
            if (item == null) {
                return;
            }
            item.status = status;
            item.message = message;
        }

        public synchronized long startedTime() {
            return startedTime;
        }

        public synchronized boolean truncated() {
            return truncated;
        }

        /** 拷贝当前全部条目，供对外视图组装；调用方拿到的是快照，不随后续写入变化。 */
        public synchronized List<Item> snapshot() {
            List<Item> copy = new ArrayList<>(order.size());
            for (Item item : order) {
                copy.add(item.copy());
            }
            return copy;
        }
    }

    /** 队列中的单个作品条目（可变；对外只通过 {@link Run#snapshot()} 的拷贝暴露）。 */
    public static final class Item {

        private final String id;
        private final String kind;
        private String title;
        private Integer xRestrict;
        private Boolean ai;
        private String status = STATUS_PENDING;
        private String message;

        Item(String id, String kind) {
            this.id = id;
            this.kind = kind;
        }

        private Item(Item other) {
            this.id = other.id;
            this.kind = other.kind;
            this.title = other.title;
            this.xRestrict = other.xRestrict;
            this.ai = other.ai;
            this.status = other.status;
            this.message = other.message;
        }

        Item copy() {
            return new Item(this);
        }

        public String getId() {
            return id;
        }

        public String getKind() {
            return kind;
        }

        public String getTitle() {
            return title;
        }

        public Integer getXRestrict() {
            return xRestrict;
        }

        public Boolean getAi() {
            return ai;
        }

        public String getStatus() {
            return status;
        }

        public String getMessage() {
            return message;
        }
    }
}
