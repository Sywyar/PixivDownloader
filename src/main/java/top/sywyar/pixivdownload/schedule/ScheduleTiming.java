package top.sywyar.pixivdownload.schedule;

import org.springframework.scheduling.support.CronExpression;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 计划任务下次运行时刻（epoch 毫秒）的计算。
 *
 * <ul>
 *   <li>{@code interval}：{@code from + intervalMinutes 分钟}。</li>
 *   <li>{@code cron}：用 Spring 自带的 {@link CronExpression}（无新依赖）求 {@code from} 之后的下一个触发点。</li>
 * </ul>
 */
public final class ScheduleTiming {

    private ScheduleTiming() {}

    /**
     * 计算下次运行时刻。
     *
     * @param triggerKind     {@link ScheduledTask#TRIGGER_INTERVAL} 或 {@link ScheduledTask#TRIGGER_CRON}
     * @param intervalMinutes interval 模式的周期分钟数
     * @param cronExpr        cron 模式的表达式
     * @param fromMillis      计算基准时刻（epoch 毫秒）
     * @return 下次运行 epoch 毫秒；无法计算（参数非法）时返回 {@code null}
     */
    public static Long computeNextRun(String triggerKind, Integer intervalMinutes,
                                      String cronExpr, long fromMillis) {
        if (ScheduledTask.TRIGGER_CRON.equals(triggerKind)) {
            if (cronExpr == null || cronExpr.isBlank() || !CronExpression.isValidExpression(cronExpr)) {
                return null;
            }
            LocalDateTime from = LocalDateTime.ofInstant(Instant.ofEpochMilli(fromMillis), ZoneId.systemDefault());
            LocalDateTime next = CronExpression.parse(cronExpr).next(from);
            if (next == null) {
                return null;
            }
            return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        // 默认按固定周期处理
        if (intervalMinutes == null || intervalMinutes <= 0) {
            return null;
        }
        return fromMillis + intervalMinutes * 60_000L;
    }
}
