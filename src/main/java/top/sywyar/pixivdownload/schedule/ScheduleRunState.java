package top.sywyar.pixivdownload.schedule;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
 * <p>不落库是有意的：进程退出后这些瞬时态自然消失，不会出现「卡在 RUNNING」的脏状态；
 * 任务真正的结果始终以持久化的 {@code last_status} 为准。
 */
@Component
public class ScheduleRunState {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";

    private final ConcurrentMap<Long, String> states = new ConcurrentHashMap<>();

    public void markQueued(long id) {
        states.put(id, QUEUED);
    }

    public void markRunning(long id) {
        states.put(id, RUNNING);
    }

    public void clear(long id) {
        states.remove(id);
    }

    /** 返回该任务的瞬时运行态（{@link #QUEUED} / {@link #RUNNING}），无则返回 {@code null}。 */
    public String get(long id) {
        return states.get(id);
    }
}
