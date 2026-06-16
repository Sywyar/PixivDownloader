package top.sywyar.pixivdownload.schedule;

import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 计划任务的<b>运行期瞬时状态</b>登记（仅内存，不落库）。
 *
 * <p>持久化的 {@code last_status} 只能表达「上一轮的结果」（OK / AUTH_EXPIRED / ERROR / 尚未运行），
 * 无法表达「此刻正在跑」或「在等待前一个任务跑完」。本登记补足这两个瞬时态，供
 * {@link top.sywyar.pixivdownload.schedule.dto.ScheduleTaskView} 透出给前端展示状态灯：
 *
 * <ul>
 *   <li>{@link #QUEUED}：本轮 tick 已选中、出于「同一时刻只跑一个」的串行约束在排队等待前序任务结束；</li>
 *   <li>{@link #RUNNING}：当前正在执行。</li>
 * </ul>
 *
 * <p>每轮运行都必须先拿到一个 {@link Claim}。后续从 QUEUED 转 RUNNING、结束清理时都校验 claim，
 * 避免同一任务被手动运行 / tick 重复触发后互相覆盖或误清对方状态。
 *
 * <p>不落库是有意的：进程退出后这些瞬时态自然消失，不会出现「卡在 RUNNING」的脏状态；
 * 任务真正的结果始终以持久化的 {@code last_status} / {@code run_started_time} 为准。
 */
@PluginManagedBean
public class ScheduleRunState {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";

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
        boolean[] updated = {false};
        states.computeIfPresent(claim.taskId(), (id, current) -> {
            if (current.claimId() != claim.claimId()) {
                return current;
            }
            updated[0] = true;
            return new Entry(RUNNING, claim.claimId(), current.cancelRequested());
        });
        return updated[0];
    }

    public void clear(Claim claim) {
        if (claim == null) {
            return;
        }
        states.computeIfPresent(claim.taskId(), (id, current) ->
                current.claimId() == claim.claimId() ? null : current);
    }

    /** 返回该任务的瞬时运行态（{@link #QUEUED} / {@link #RUNNING}），无则返回 {@code null}。 */
    public String get(long id) {
        Entry entry = states.get(id);
        return entry == null ? null : entry.state();
    }

    /**
     * 请求协作式取消：仅当任务正在 {@link #QUEUED} / {@link #RUNNING} 时生效（绑定当前 Claim）。
     *
     * <p>用于手动「暂停」让运行中的任务在下一个安全检查点（{@code WorkRunner.process} 入口）干净 unwind。
     * 标记位寄存在 {@link Entry} 上，{@link #clear(Claim)} 移除 Entry 时一并消失，下一轮运行自然清零。
     * 任务空闲（无 Entry）时返回 {@code false}：DB 的 {@code last_status=PAUSED} 已足以让 {@code findDue} 把它挡住，
     * 无需在内存里挂残留标记。
     */
    public boolean requestCancel(long taskId) {
        boolean[] applied = {false};
        states.computeIfPresent(taskId, (id, current) -> {
            applied[0] = true;
            return new Entry(current.state(), current.claimId(), true);
        });
        return applied[0];
    }

    /** 当前任务的运行是否被请求取消（手动暂停）；executor 在派发前轮询。 */
    public boolean isCancelRequested(long taskId) {
        Entry e = states.get(taskId);
        return e != null && e.cancelRequested();
    }

    private Claim tryClaim(long id, String state) {
        long claimId = nextClaim.incrementAndGet();
        Entry existing = states.putIfAbsent(id, new Entry(state, claimId, false));
        return existing == null ? new Claim(id, claimId) : null;
    }

    public record Claim(long taskId, long claimId) {
    }

    private record Entry(String state, long claimId, boolean cancelRequested) {
    }
}
