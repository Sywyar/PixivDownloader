package top.sywyar.pixivdownload.maintenance;

/**
 * 维护窗口的进度快照持有者（进程内、GUI 直读）。
 *
 * <p>{@link MaintenanceCoordinator} 在维护窗口开启 / 切换任务 / 关闭时更新这里的快照；
 * 正在执行的 {@link top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask} 可在运行中调用
 * {@link #updateProgress(int, int)} 上报
 * 任务级的「已处理 / 总数」进度（如相似图哈希回填的已处理作品数），供 GUI 显示进度与自我修正 ETA。
 * GUI 状态页在同一 JVM 内直接读取，无需经过 HTTP —— 维护期间 {@code /api/gui/status}
 * 会被 {@code AuthFilter} 以 503 短路，HTTP 路径本就拿不到进度。
 */
public final class MaintenanceStatusHolder {

    /**
     * @param active        是否处于维护窗口内
     * @param trigger       触发来源（{@code schedule} / {@code manual}）
     * @param index         当前任务序号（1-based；窗口刚开启、尚未进入第一个任务时为 0）
     * @param total         本轮维护任务总数
     * @param taskName      当前任务的稳定名称（{@link top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask#name()}）；尚未进入任务时为 {@code null}
     * @param taskStartedAt 当前任务开始时间（epoch millis，供 GUI 计算实时已运行秒数与 ETA）；尚未进入任务时为 0
     * @param unitsDone     当前任务已处理的工作单元数；任务未上报进度时为 0
     * @param unitsTotal    当前任务的工作单元总数；任务未上报进度时为 0（GUI 据此判断是否展示进度/ETA）
     */
    public record Snapshot(boolean active, String trigger, int index, int total,
                           String taskName, long taskStartedAt, int unitsDone, int unitsTotal) {}

    private static final Snapshot IDLE = new Snapshot(false, null, 0, 0, null, 0L, 0, 0);

    private static volatile Snapshot current = IDLE;

    private MaintenanceStatusHolder() {
    }

    public static Snapshot snapshot() {
        return current;
    }

    /** 维护窗口开启、尚未进入第一个任务时调用。 */
    public static void begin(String trigger, int total) {
        current = new Snapshot(true, trigger, 0, total, null, 0L, 0, 0);
    }

    /** 进入第 {@code index}（1-based）个任务时调用，同时清零上一个任务的进度。 */
    public static void enterTask(String trigger, int index, int total, String taskName, long taskStartedAt) {
        current = new Snapshot(true, trigger, index, total, taskName, taskStartedAt, 0, 0);
    }

    /**
     * 正在执行的任务上报自身进度（只改工作单元计数，保留当前任务身份与开始时间）。
     * 仅在维护窗口仍激活时生效，避免任务收尾后写入残留进度。
     */
    public static void updateProgress(int unitsDone, int unitsTotal) {
        Snapshot s = current;
        if (!s.active()) {
            return;
        }
        current = new Snapshot(s.active(), s.trigger(), s.index(), s.total(),
                s.taskName(), s.taskStartedAt(), unitsDone, unitsTotal);
    }

    /** 维护窗口结束时调用，恢复空闲态。 */
    public static void clear() {
        current = IDLE;
    }
}
