package top.sywyar.pixivdownload.maintenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.Order;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceContext;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("维护任务可信 owner 注册中心测试")
class MaintenanceTaskRegistryTest {

    @Test
    @DisplayName("核心任务始终保留，外置 publication 精确撤回并按顺序合并")
    void coreTasksRemainWhileExternalPublicationIsWithdrawnExactly() {
        MaintenanceTaskRegistry registry = new MaintenanceTaskRegistry(
                List.of(new LastCoreTask(), new FirstCoreTask()));
        ExternalCapabilityOwner first = new ExternalCapabilityOwner("duplicate", "duplicate", 3L, 11L);
        ExternalCapabilityOwner replacement = new ExternalCapabilityOwner("duplicate", "duplicate", 4L, 12L);
        MaintenanceTask external = task("duplicate-hash-backfill");

        registry.registerPrepared(first,
                List.of(new MaintenanceTaskRegistry.PreparedTask(150, external)));

        assertThat(registry.tasks()).extracting(MaintenanceTask::name)
                .containsExactly("core-first", "duplicate-hash-backfill", "core-last");
        assertThatThrownBy(() -> registry.tasks().add(task("forbidden")))
                .isInstanceOf(UnsupportedOperationException.class);

        registry.unregisterPrepared(first);
        registry.registerPrepared(replacement,
                List.of(new MaintenanceTaskRegistry.PreparedTask(150, external)));
        registry.unregisterPrepared(first);

        assertThat(registry.tasks()).extracting(MaintenanceTask::name)
                .containsExactly("core-first", "duplicate-hash-backfill", "core-last");

        registry.unregisterPrepared(replacement);
        assertThat(registry.tasks()).extracting(MaintenanceTask::name)
                .containsExactly("core-first", "core-last");
    }

    @Test
    @DisplayName("PriorityOrdered 任务优先于数值更小的普通任务")
    void priorityOrderedTasksRemainInTheSpringPriorityBucket() {
        MaintenanceTaskRegistry registry = new MaintenanceTaskRegistry(
                List.of(new PlainHighestTask(), new PriorityLowestTask()));

        assertThat(registry.tasks()).extracting(MaintenanceTask::name)
                .containsExactly("priority-lowest", "plain-highest");
    }

    private static MaintenanceTask task(String name) {
        return new MaintenanceTask() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void execute(MaintenanceContext context) {
            }
        };
    }

    @Order(100)
    private static final class FirstCoreTask implements MaintenanceTask {
        @Override
        public String name() {
            return "core-first";
        }

        @Override
        public void execute(MaintenanceContext context) {
        }
    }

    @Order(200)
    private static final class LastCoreTask implements MaintenanceTask {
        @Override
        public String name() {
            return "core-last";
        }

        @Override
        public void execute(MaintenanceContext context) {
        }
    }

    @Order(-1_000)
    private static final class PlainHighestTask implements MaintenanceTask {
        @Override
        public String name() {
            return "plain-highest";
        }

        @Override
        public void execute(MaintenanceContext context) {
        }
    }

    private static final class PriorityLowestTask implements MaintenanceTask, PriorityOrdered {
        @Override
        public int getOrder() {
            return 1_000;
        }

        @Override
        public String name() {
            return "priority-lowest";
        }

        @Override
        public void execute(MaintenanceContext context) {
        }
    }

}
