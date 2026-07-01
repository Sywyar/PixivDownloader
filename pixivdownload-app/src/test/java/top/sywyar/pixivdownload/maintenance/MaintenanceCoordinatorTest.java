package top.sywyar.pixivdownload.maintenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceContext;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

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

    @Test
    @DisplayName("被禁用插件拥有的维护任务在维护窗口被跳过，核心 / 未归属任务仍执行")
    void skipsMaintenanceTaskOwnedByDisabledPlugin() {
        AtomicInteger coreRuns = new AtomicInteger();
        AtomicInteger pluginRuns = new AtomicInteger();
        UnownedTask coreTask = new UnownedTask(coreRuns);
        OwnedTask pluginTask = new OwnedTask(pluginRuns);

        PluginToggleProperties toggles = new PluginToggleProperties();
        PluginToggleProperties.PluginToggle off = new PluginToggleProperties.PluginToggle();
        off.setEnabled(false);
        toggles.put("featurex", off);
        PluginRegistry registry = new PluginRegistry(
                List.of(corePluginStub(), pluginOwning("featurex", OwnedTask.class)),
                toggles);

        MaintenanceCoordinator coordinator = new MaintenanceCoordinator(
                List.of(coreTask, pluginTask), new MaintenanceProperties(), registry);

        assertThat(coordinator.runScheduledIfDue(LocalDateTime.of(2026, 5, 25, 10, 0))).isTrue();
        assertThat(coreRuns).as("未归属（核心）任务仍执行").hasValue(1);
        assertThat(pluginRuns).as("被禁用插件拥有的任务被跳过").hasValue(0);
    }

    @Test
    @DisplayName("插件启用时其拥有的维护任务正常执行")
    void runsMaintenanceTaskOwnedByEnabledPlugin() {
        AtomicInteger pluginRuns = new AtomicInteger();
        OwnedTask pluginTask = new OwnedTask(pluginRuns);

        PluginRegistry registry = new PluginRegistry(
                List.of(corePluginStub(), pluginOwning("featurex", OwnedTask.class)));

        MaintenanceCoordinator coordinator = new MaintenanceCoordinator(
                List.of(pluginTask), new MaintenanceProperties(), registry);

        assertThat(coordinator.runScheduledIfDue(LocalDateTime.of(2026, 5, 25, 10, 0))).isTrue();
        assertThat(pluginRuns).hasValue(1);
    }

    /** 由插件声明归属的任务（独立具名类型，便于按 {@code Class} 归属判定）。 */
    static final class OwnedTask implements MaintenanceTask {
        private final AtomicInteger runs;

        OwnedTask(AtomicInteger runs) {
            this.runs = runs;
        }

        @Override
        public String name() {
            return "owned";
        }

        @Override
        public void execute(MaintenanceContext context) {
            runs.incrementAndGet();
        }
    }

    /** 不被任何插件声明归属的核心任务（独立具名类型）。 */
    static final class UnownedTask implements MaintenanceTask {
        private final AtomicInteger runs;

        UnownedTask(AtomicInteger runs) {
            this.runs = runs;
        }

        @Override
        public String name() {
            return "unowned";
        }

        @Override
        public void execute(MaintenanceContext context) {
            runs.incrementAndGet();
        }
    }

    private static PixivFeaturePlugin corePluginStub() {
        return new PixivFeaturePlugin() {
            @Override
            public String id() {
                return "core";
            }

            @Override
            public String displayName() {
                return "plugin.label";
            }

            @Override
            public String description() {
                return "plugin.summary";
            }

            @Override
            public PluginKind kind() {
                return PluginKind.CORE;
            }
        };
    }

    private static PixivFeaturePlugin pluginOwning(String id, Class<? extends MaintenanceTask> taskType) {
        return new PixivFeaturePlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String displayName() {
                return "plugin.label";
            }

            @Override
            public String description() {
                return "plugin.summary";
            }

            @Override
            public PluginKind kind() {
                return PluginKind.FEATURE;
            }

            @Override
            public List<Class<? extends MaintenanceTask>> maintenanceTasks() {
                return List.of(taskType);
            }
        };
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
