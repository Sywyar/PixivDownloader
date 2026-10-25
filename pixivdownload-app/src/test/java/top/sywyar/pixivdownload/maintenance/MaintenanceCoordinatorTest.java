package top.sywyar.pixivdownload.maintenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.notification.NotificationDispatcher;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceContext;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MaintenanceCoordinator 调度配置测试")
class MaintenanceCoordinatorTest {

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true"})
    @DisplayName("维护运行中拒绝重复请求，关闭窗口后允许再次执行")
    void rejectsDuplicateRequestDuringActiveWindow(boolean scheduled, boolean duplicateScheduled) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var runs = new AtomicInteger();
        MaintenanceTask task = new MaintenanceTask() {
            public String name() { return "held-maintenance"; }
            public void execute(MaintenanceContext context) throws Exception {
                runs.incrementAndGet();
                entered.countDown();
                assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            }
        };
        var coordinator = new MaintenanceCoordinator(List.of(task), new MaintenanceProperties());
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> scheduled
                    ? coordinator.runScheduledIfDue(LocalDateTime.of(2026, 5, 25, 10, 0))
                    : coordinator.runManually());
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator.isPaused()).isTrue();
            var duplicate = workers.submit(() -> duplicateScheduled
                    ? coordinator.runScheduledIfDue(LocalDateTime.of(2026, 5, 25, 10, 0))
                    : coordinator.runManually());
            assertThat(duplicate.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(runs).hasValue(1);
            assertThat(MaintenanceStatusHolder.snapshot().active()).isTrue();
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator.isPaused()).isFalse();
            assertThat(MaintenanceStatusHolder.snapshot().active()).isFalse();
            assertThat(coordinator.runManually()).isTrue();
            assertThat(runs).hasValue(2);
        } finally {
            release.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("缺库不打开维护窗口也不消费时段，运行中失效停止后续任务和通知")
    void unavailableDatabaseDoesNotRunOrNotify() {
        var available = new java.util.concurrent.atomic.AtomicBoolean(false);
        var notifications = mock(NotificationDispatcher.class);
        var runs = new AtomicInteger();
        MaintenanceTask first = new MaintenanceTask() {
            public String name() { return "first"; }
            public void execute(MaintenanceContext context) {
                runs.incrementAndGet();
                available.set(false);
                throw new top.sywyar.pixivdownload.core.database.DatabaseUnavailableException();
            }
        };
        var coordinator = new MaintenanceCoordinator(new MaintenanceTaskRegistry(List.of(first, countingTask(runs))),
                new MaintenanceProperties(), notifications, null, available::get);
        var slot = LocalDateTime.of(2026, 5, 25, 10, 0);
        assertThat(coordinator.runScheduledIfDue(slot)).isFalse();
        assertThat(coordinator.getLastStartedAt()).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(coordinator::runManually)
                .isInstanceOf(top.sywyar.pixivdownload.core.database.DatabaseUnavailableException.class);
        available.set(true);
        assertThat(coordinator.runScheduledIfDue(slot)).isTrue();
        assertThat(runs).hasValue(1);
        assertThat(coordinator.isPaused()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("维护上下文进度回调更新当前任务快照并在窗口结束后清理")
    void maintenanceContextReportsProgressToCurrentSnapshot() {
        AtomicReference<MaintenanceStatusHolder.Snapshot> reported = new AtomicReference<>();
        MaintenanceTask task = new MaintenanceTask() {
            @Override
            public String name() {
                return "progress";
            }

            @Override
            public void execute(MaintenanceContext context) {
                context.updateProgress(2, 5);
                reported.set(MaintenanceStatusHolder.snapshot());
            }
        };
        MaintenanceCoordinator coordinator = new MaintenanceCoordinator(
                List.of(task), new MaintenanceProperties());

        assertThat(coordinator.runScheduledIfDue(LocalDateTime.of(2026, 5, 25, 10, 0))).isTrue();

        assertThat(reported.get().unitsDone()).isEqualTo(2);
        assertThat(reported.get().unitsTotal()).isEqualTo(5);
        assertThat(MaintenanceStatusHolder.snapshot().active()).isFalse();
    }

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

    @Test
    @DisplayName("每个维护窗口只执行开始时取得的稳定任务快照")
    void maintenanceWindowUsesOneStableTaskSnapshot() {
        AtomicInteger coreRuns = new AtomicInteger();
        AtomicInteger externalRuns = new AtomicInteger();
        MaintenanceTask external = countingTask(externalRuns);
        ExternalCapabilityOwner owner = new ExternalCapabilityOwner("featurex", "featurex", 1L, 1L);
        AtomicReference<MaintenanceTaskRegistry> registryRef = new AtomicReference<>();
        MaintenanceTask core = new MaintenanceTask() {
            @Override
            public String name() {
                return "core";
            }

            @Override
            public void execute(MaintenanceContext context) {
                coreRuns.incrementAndGet();
                registryRef.get().registerPrepared(owner,
                        List.of(new MaintenanceTaskRegistry.PreparedTask(150, external)));
            }
        };
        MaintenanceTaskRegistry registry = new MaintenanceTaskRegistry(List.of(core));
        registryRef.set(registry);
        MaintenanceCoordinator coordinator = new MaintenanceCoordinator(registry, new MaintenanceProperties());

        assertThat(coordinator.runManually()).isTrue();
        assertThat(coreRuns).hasValue(1);
        assertThat(externalRuns).as("窗口开始后新发布的任务留到下次执行").hasValue(0);

        assertThat(coordinator.runManually()).isTrue();
        assertThat(coreRuns).hasValue(2);
        assertThat(externalRuns).hasValue(1);
    }

    @Test
    @DisplayName("任务名称读取异常时仍继续执行后续核心任务")
    void taskNameFailureDoesNotAbortRemainingTasks() {
        AtomicInteger coreRuns = new AtomicInteger();
        MaintenanceTask unavailable = new MaintenanceTask() {
            @Override
            public String name() {
                throw new IllegalStateException("broken task name");
            }

            @Override
            public void execute(MaintenanceContext context) {
                throw new AssertionError("名称读取失败的任务不应进入执行");
            }
        };
        MaintenanceCoordinator coordinator = new MaintenanceCoordinator(
                List.of(unavailable, countingTask(coreRuns)), new MaintenanceProperties());

        assertThat(coordinator.runManually()).isTrue();
        assertThat(coreRuns).hasValue(1);
    }

    @Test
    @DisplayName("维护任务失败时发送系统通知并继续后续任务")
    void taskFailurePublishesSystemNotificationAndContinues() {
        AtomicInteger laterRuns = new AtomicInteger();
        MaintenanceTask failed = new MaintenanceTask() {
            @Override
            public String name() {
                return "failed-maintenance";
            }

            @Override
            public void execute(MaintenanceContext context) {
                throw new IllegalStateException("sensitive detail must stay in logs");
            }
        };
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        AppMessages messages = mock(AppMessages.class);
        when(messages.normalizeLocale(any())).thenReturn(Locale.US);
        when(messages.getOrDefault(eq(Locale.US), eq("notification.system.maintenance-trigger.manual"),
                anyString())).thenReturn("Manual");
        MaintenanceCoordinator coordinator = new MaintenanceCoordinator(
                new MaintenanceTaskRegistry(List.of(failed, countingTask(laterRuns))),
                new MaintenanceProperties(), dispatcher, messages);

        assertThat(coordinator.runManually()).isTrue();

        verify(dispatcher).notify(eq(NotificationScenario.MAINTENANCE_TASK_FAILED), eq(Locale.US),
                argThat(values -> "failed-maintenance".equals(values.get("task_name"))
                        && "Manual".equals(values.get("trigger"))
                        && "IllegalStateException".equals(values.get("error_type"))
                        && values.values().stream().noneMatch(value -> value.contains("sensitive detail"))));
        assertThat(laterRuns).hasValue(1);
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
