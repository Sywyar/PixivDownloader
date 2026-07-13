package top.sywyar.pixivdownload.core.download.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.PluginCapabilityContributionAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.QueueOperationsCapabilityAdapter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("队列操作运行期能力适配器")
class QueueOperationsCapabilityAdapterTest {

    @Test
    @DisplayName("从外置插件子上下文注册队列操作并按 owner 精准注销")
    void registersChildContextOperationsAndUnregistersOnlyTheirOwner() {
        QueueOperations parent = operations("parent");
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of(parent));
        QueueOperationsCapabilityAdapter adapter = new QueueOperationsCapabilityAdapter(registry);
        PluginCapabilityContributionRegistrar registrar = new PluginCapabilityContributionRegistrar(
                List.<PluginCapabilityContributionAdapter<?>>of(adapter));

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.registerBean("externalQueueOperations", QueueOperations.class, () -> operations("external"));
            child.refresh();
            registrar.register("external-plugin", child);
        }

        assertThat(registry.resolve("external")).isPresent();
        assertThat(registry.resolve("parent")).containsSame(parent);

        registrar.unregister("external-plugin");

        assertThat(registry.resolve("external")).isEmpty();
        assertThat(registry.resolve("parent")).containsSame(parent);
    }

    @Test
    @DisplayName("同一 owner 重新注册时原子替换旧队列类型")
    void replacesOwnedOperationsAtomically() {
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of());
        registry.register("external-plugin", List.of(operations("old")));

        registry.register("external-plugin", List.of(operations("new")));

        assertThat(registry.resolve("old")).isEmpty();
        assertThat(registry.resolve("new")).isPresent();
    }

    private static QueueOperations operations(String type) {
        return new QueueOperations() {
            private final QueueTaskTracker tracker = new QueueTaskTracker(type);

            @Override
            public String queueType() {
                return type;
            }

            @Override
            public QueueGenerationDrain prepareQuiesce() {
                return tracker.prepareQuiesce();
            }

            @Override
            public void cancelQuiescedTasks() {
                tracker.cancelQuiescedTasks();
            }

            @Override
            public int clearAll() {
                return 0;
            }

            @Override
            public int clearForOwner(String ownerUuid) {
                return 0;
            }
        };
    }
}
