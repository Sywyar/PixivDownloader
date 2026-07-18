package top.sywyar.pixivdownload.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("计划执行器下载池并发边界")
class ScheduleExecutorConcurrencyTest {

    @Test
    @DisplayName("兼容调度路径按实际固定线程池容量收紧任务并发")
    void clampsTaskConcurrencyToActualPoolCapacity() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setMaxPoolSize(2);
        executor.setCorePoolSize(2);

        assertThat(ScheduleExecutor.effectiveConcurrency(executor, 5)).isEqualTo(2);
        assertThat(ScheduleExecutor.effectiveConcurrency(executor, 1)).isEqualTo(1);
        assertThat(ScheduleExecutor.effectiveConcurrency(executor, 0)).isEqualTo(1);
    }

    @Test
    @DisplayName("无容量元数据的同步测试执行器只受任务声明约束")
    void keepsRequestedLimitForExecutorWithoutCapacityMetadata() {
        TaskExecutor direct = Runnable::run;

        assertThat(ScheduleExecutor.effectiveConcurrency(direct, 4)).isEqualTo(4);
    }
}
