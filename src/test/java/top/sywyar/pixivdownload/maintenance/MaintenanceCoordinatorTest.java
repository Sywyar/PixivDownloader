package top.sywyar.pixivdownload.maintenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MaintenanceCoordinator 调度配置测试")
class MaintenanceCoordinatorTest {

    @Test
    @DisplayName("默认仅在周一 10:00 触发且同一分钟不重复触发")
    void shouldRunAtDefaultMondaySlotOnlyOncePerMinute() {
        AtomicInteger runs = new AtomicInteger();
        MaintenanceCoordinator coordinator = new MaintenanceCoordinator(
                List.of(countingTask(runs)),
                new MaintenanceProperties());

        assertThat(coordinator.runScheduledIfDue(LocalDateTime.of(2026, 5, 25, 9, 59, 59))).isFalse();
        assertThat(coordinator.runScheduledIfDue(LocalDateTime.of(2026, 5, 25, 10, 0))).isTrue();
        assertThat(coordinator.runScheduledIfDue(LocalDateTime.of(2026, 5, 25, 10, 0, 30))).isFalse();

        assertThat(runs).hasValue(1);
    }

    @Test
    @DisplayName("可为不同星期配置独立维护时间")
    void shouldUseConfiguredWeekdaySpecificTime() {
        AtomicInteger runs = new AtomicInteger();
        MaintenanceProperties properties = new MaintenanceProperties();
        properties.getMonday().setEnabled(false);
        properties.getWednesday().setEnabled(true);
        properties.getWednesday().setTime("22:30");
        MaintenanceCoordinator coordinator = new MaintenanceCoordinator(
                List.of(countingTask(runs)),
                properties);

        assertThat(coordinator.runScheduledIfDue(LocalDateTime.of(2026, 5, 25, 10, 0))).isFalse();
        assertThat(coordinator.runScheduledIfDue(LocalDateTime.of(2026, 5, 27, 22, 29))).isFalse();
        assertThat(coordinator.runScheduledIfDue(LocalDateTime.of(2026, 5, 27, 22, 30))).isTrue();

        assertThat(runs).hasValue(1);
    }

    @Test
    @DisplayName("维护框架禁用时不会按调度触发")
    void shouldSkipScheduleWhenDisabled() {
        AtomicInteger runs = new AtomicInteger();
        MaintenanceProperties properties = new MaintenanceProperties();
        properties.setEnabled(false);
        MaintenanceCoordinator coordinator = new MaintenanceCoordinator(
                List.of(countingTask(runs)),
                properties);

        assertThat(coordinator.runScheduledIfDue(LocalDateTime.of(2026, 5, 25, 10, 0))).isFalse();

        assertThat(runs).hasValue(0);
    }

    @Test
    @DisplayName("启用星期但时间格式无效时不会触发")
    void shouldSkipInvalidTime() {
        AtomicInteger runs = new AtomicInteger();
        MaintenanceProperties properties = new MaintenanceProperties();
        properties.getMonday().setTime("24:00");
        MaintenanceCoordinator coordinator = new MaintenanceCoordinator(
                List.of(countingTask(runs)),
                properties);

        assertThat(coordinator.runScheduledIfDue(LocalDateTime.of(2026, 5, 25, 10, 0))).isFalse();

        assertThat(runs).hasValue(0);
    }

    private static MaintenanceTask countingTask(AtomicInteger runs) {
        return new MaintenanceTask() {
            @Override
            public String name() {
                return "counting";
            }

            @Override
            public void execute(MaintenanceContext context) {
                runs.incrementAndGet();
            }
        };
    }
}
