package top.sywyar.pixivdownload.schedule;


import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 计划任务的同进程协调态登记（仅内存，不落库）。
 *
 * <p>持久化 Store 的 claim token、state version 与 run state 是运行真相。本登记镜像同 JVM 内已认领的
 * {@code QUEUED}/{@code RUNNING}/{@code CANCEL_REQUESTED}，用于快速单飞、协作式取消和界面即时刷新：
 *
 * <ul>
 *   <li>{@link #QUEUED}：本轮 tick 已选中、出于「同一时刻只跑一个」的串行约束在排队等待前序任务结束；</li>
 *   <li>{@link #RUNNING}：当前正在执行。</li>
 *   <li>{@link #CANCEL_REQUESTED}：当前 claim 已收到协作式取消请求，等待执行方收尾。</li>
 * </ul>
 *
 * <p>每轮运行都必须先拿到一个 {@link Claim}。后续从 QUEUED 转 RUNNING、结束清理时都校验 claim，
 * 避免同一任务被手动运行 / tick 重复触发后互相覆盖或误清对方状态。
 *
 * <p>本登记不是运行真相：持久化 Store 另以 claim token、state version 和运行态做 CAS。
 * 这里仅用于同进程内快速单飞、协作式取消与界面即时刷新；进程重启后的中断恢复完全由持久化状态机负责。
 */
public class ScheduleRunState {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";

    private final AtomicLong nextClaim = new AtomicLong();
    private final ConcurrentMap<Long, Entry> states = new ConcurrentHashMap<>();

    public Claim tryMarkQueued(long id) {
        return tryClaim(id, QUEUED);
    }

    public Claim tryMarkRunning(long id) {
        return tryClaim(id, RUNNING);
    }

    public boolean markRunning(Claim claim) {
        if (claim == null) {
            return false;
        }
        Entry entry = states.computeIfPresent(claim.taskId(), (id, current) -> {
            if (current.claimId() != claim.claimId() || CANCEL_REQUESTED.equals(current.state())) {
                return current;
            }
            return new Entry(RUNNING, claim.claimId());
        });
        return entry != null && entry.claimId() == claim.claimId();
    }

    public void clear(Claim claim) {
        if (claim == null) {
            return;
        }
        states.computeIfPresent(claim.taskId(), (id, current) ->
                current.claimId() == claim.claimId() ? null : current);
    }

    /** 返回该任务的瞬时运行态，无则返回 {@code null}。 */
    public String get(long id) {
        Entry entry = states.get(id);
        return entry == null ? null : entry.state();
    }

    /**
     * 请求协作式取消：仅当任务正在 {@link #QUEUED} / {@link #RUNNING} 时生效（绑定当前 Claim）。
     *
     * <p>用于手动「暂停」让运行中的任务在下一个安全检查点（{@code WorkRunner.process} 入口）干净 unwind。
     * 取消态属于当前 {@link Entry}，{@link #clear(Claim)} 移除 Entry 时一并消失，不影响下一轮运行。
     * 任务空闲（无 Entry）时返回 {@code false}：持久化挂起原因已足以让 {@code findDue} 把它挡住，
     * 无需在内存里保留取消标记。
     */
    public boolean requestCancel(long taskId) {
        return states.computeIfPresent(taskId, (id, current) ->
                new Entry(CANCEL_REQUESTED, current.claimId())) != null;
    }

    /** 宿主停止准入后，向当前全部运行发送协作式取消。 */
    public void requestCancelAll() {
        states.replaceAll((id, current) -> new Entry(CANCEL_REQUESTED, current.claimId()));
    }

    /** 当前任务的运行是否被请求取消（手动暂停）；executor 在派发前轮询。 */
    public boolean isCancelRequested(long taskId) {
        return CANCEL_REQUESTED.equals(get(taskId));
    }

    private Claim tryClaim(long id, String state) {
        long claimId = nextClaim.incrementAndGet();
        Entry existing = states.putIfAbsent(id, new Entry(state, claimId));
        return existing == null ? new Claim(id, claimId) : null;
    }

    public record Claim(long taskId, long claimId) {
    }

    private record Entry(String state, long claimId) {
    }
}
