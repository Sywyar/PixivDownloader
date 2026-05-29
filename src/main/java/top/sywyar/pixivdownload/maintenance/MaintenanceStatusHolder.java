package top.sywyar.pixivdownload.maintenance;

/**
 * 维护窗口的进度快照持有者（进程内、GUI 直读）。
 *
 * <p>{@link MaintenanceCoordinator} 在维护窗口开启 / 切换任务 / 关闭时更新这里的快照；
 * GUI 状态页在同一 JVM 内直接读取，无需经过 HTTP —— 维护期间 {@code /api/gui/status}
 * 会被 {@code AuthFilter} 以 503 短路，HTTP 路径本就拿不到进度。
 */
public final class MaintenanceStatusHolder {

    /**
     * @param active        是否处于维护窗口内
     * @param trigger       触发来源（{@code schedule} / {@code manual}）
     * @param index         当前任务序号（1-based；窗口刚开启、尚未进入第一个任务时为 0）
     * @param total         本轮维护任务总数
     * @param taskName      当前任务的稳定名称（{@link MaintenanceTask#name()}）；尚未进入任务时为 {@code null}
     * @param taskStartedAt 当前任务开始时间（epoch millis，供 GUI 计算实时已运行秒数）；尚未进入任务时为 0
     */
    public record Snapshot(boolean active, String trigger, int index, int total,
                           String taskName, long taskStartedAt) {}

    private static final Snapshot IDLE = new Snapshot(false, null, 0, 0, null, 0L);

    private static volatile Snapshot current = IDLE;

    private MaintenanceStatusHolder() {
    }

    public static Snapshot snapshot() {
        return current;
    }

    /** 维护窗口开启、尚未进入第一个任务时调用。 */
    public static void begin(String trigger, int total) {
        current = new Snapshot(true, trigger, 0, total, null, 0L);
    }

    /** 进入第 {@code index}（1-based）个任务时调用。 */
    public static void enterTask(String trigger, int index, int total, String taskName, long taskStartedAt) {
        current = new Snapshot(true, trigger, index, total, taskName, taskStartedAt);
    }

    /** 维护窗口结束时调用，恢复空闲态。 */
    public static void clear() {
        current = IDLE;
    }
}
