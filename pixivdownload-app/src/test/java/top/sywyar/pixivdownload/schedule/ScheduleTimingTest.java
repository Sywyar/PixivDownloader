package top.sywyar.pixivdownload.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScheduleTiming 下次运行时刻计算")
class ScheduleTimingTest {

    @Test
    @DisplayName("固定周期：在基准时刻上加 intervalMinutes 分钟")
    void shouldComputeIntervalNextRun() {
        long base = 1_700_000_000_000L;
        Long next = ScheduleTiming.computeNextRun(
                ScheduledTask.TRIGGER_INTERVAL, 30, null, base);
        assertThat(next).isEqualTo(base + 30 * 60_000L);
    }

    @Test
    @DisplayName("固定周期：interval 非正数返回 null")
    void shouldRejectNonPositiveInterval() {
        assertThat(ScheduleTiming.computeNextRun(ScheduledTask.TRIGGER_INTERVAL, 0, null, 0L)).isNull();
        assertThat(ScheduleTiming.computeNextRun(ScheduledTask.TRIGGER_INTERVAL, null, null, 0L)).isNull();
    }

    @Test
    @DisplayName("Cron：求基准时刻之后的下一个触发点（每天 03:00）")
    void shouldComputeCronNextRun() {
        // 基准设为某天 01:00，期望下一个 03:00 在同一天
        LocalDateTime baseLdt = LocalDateTime.of(2026, 5, 25, 1, 0, 0);
        long base = baseLdt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        Long next = ScheduleTiming.computeNextRun(
                ScheduledTask.TRIGGER_CRON, null, "0 0 3 * * *", base);

        assertThat(next).isNotNull();
        LocalDateTime nextLdt = LocalDateTime.ofInstant(Instant.ofEpochMilli(next), ZoneId.systemDefault());
        assertThat(nextLdt).isEqualTo(LocalDateTime.of(2026, 5, 25, 3, 0, 0));
    }

    @Test
    @DisplayName("Cron：非法表达式返回 null")
    void shouldRejectInvalidCron() {
        assertThat(ScheduleTiming.computeNextRun(ScheduledTask.TRIGGER_CRON, null, "not a cron", 0L)).isNull();
        assertThat(ScheduleTiming.computeNextRun(ScheduledTask.TRIGGER_CRON, null, "", 0L)).isNull();
    }
}
