package top.sywyar.pixivdownload.plugin.lifecycle.quiesce;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.download.queue.QueueOperations;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginStreamRegistry;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("插件运行期任务清退器")
class PluginRuntimeTaskQuiescerTest {

    @Test
    @DisplayName("按固定顺序屏蔽计划派发、关闭 SSE、排空插件声明的队列")
    void shieldsScheduleThenClosesStreamsThenDrainsQueues() {
        PluginScheduleContributionRegistrar scheduleRegistrar = mock(PluginScheduleContributionRegistrar.class);
        PluginStreamRegistry streamRegistry = mock(PluginStreamRegistry.class);
        QueueOperationRegistry queueRegistry = mock(QueueOperationRegistry.class);
        QueueOperations operations = mock(QueueOperations.class);
        when(queueRegistry.resolve("ext-illust")).thenReturn(Optional.of(operations));
        when(operations.clearAll()).thenReturn(2);
        PluginRuntimeTaskQuiescer quiescer =
                new PluginRuntimeTaskQuiescer(scheduleRegistrar, streamRegistry, queueRegistry);

        quiescer.quiesce("ext-demo", Optional.of(pluginWithQueueTypes("ext-illust")));

        InOrder order = inOrder(scheduleRegistrar, streamRegistry, queueRegistry, operations);
        order.verify(scheduleRegistrar).unregister("ext-demo");
        order.verify(streamRegistry).closeForPlugin("ext-demo");
        order.verify(queueRegistry).resolve("ext-illust");
        order.verify(operations).clearAll();
    }

    @Test
    @DisplayName("schedule、SSE 与单个队列清退失败彼此隔离，后续队列仍会排空")
    void stepFailuresDoNotBlockLaterCleanup() {
        PluginScheduleContributionRegistrar scheduleRegistrar = mock(PluginScheduleContributionRegistrar.class);
        PluginStreamRegistry streamRegistry = mock(PluginStreamRegistry.class);
        QueueOperationRegistry queueRegistry = mock(QueueOperationRegistry.class);
        QueueOperations broken = mock(QueueOperations.class);
        QueueOperations healthy = mock(QueueOperations.class);
        doThrow(new IllegalStateException("schedule failed"))
                .when(scheduleRegistrar).unregister("ext-demo");
        doThrow(new IllegalStateException("stream failed"))
                .when(streamRegistry).closeForPlugin("ext-demo");
        when(queueRegistry.resolve("broken")).thenReturn(Optional.of(broken));
        when(queueRegistry.resolve("healthy")).thenReturn(Optional.of(healthy));
        when(broken.clearAll()).thenThrow(new IllegalStateException("queue failed"));
        PluginRuntimeTaskQuiescer quiescer =
                new PluginRuntimeTaskQuiescer(scheduleRegistrar, streamRegistry, queueRegistry);

        assertThatCode(() -> quiescer.quiesce(
                "ext-demo", Optional.of(pluginWithQueueTypes("broken", "healthy"))))
                .doesNotThrowAnyException();

        verify(healthy).clearAll();
    }

    @Test
    @DisplayName("读取插件 queue type 失败只跳过队列清退，schedule 与 SSE 已完成处理")
    void queueTypeDiscoveryFailureIsIsolated() {
        PluginScheduleContributionRegistrar scheduleRegistrar = mock(PluginScheduleContributionRegistrar.class);
        PluginStreamRegistry streamRegistry = mock(PluginStreamRegistry.class);
        QueueOperationRegistry queueRegistry = mock(QueueOperationRegistry.class);
        PixivFeaturePlugin plugin = pluginWithQueueTypes();
        PixivFeaturePlugin failingPlugin = new DelegatingPlugin(plugin) {
            @Override
            public List<QueueTypeContribution> queueTypes() {
                throw new IllegalStateException("metadata failed");
            }
        };
        PluginRuntimeTaskQuiescer quiescer =
                new PluginRuntimeTaskQuiescer(scheduleRegistrar, streamRegistry, queueRegistry);

        assertThatCode(() -> quiescer.quiesce("ext-demo", Optional.of(failingPlugin)))
                .doesNotThrowAnyException();

        verify(scheduleRegistrar).unregister("ext-demo");
        verify(streamRegistry).closeForPlugin("ext-demo");
        verifyNoInteractions(queueRegistry);
    }

    @Test
    @DisplayName("缺少核心注册条目时仍屏蔽 schedule 并关闭 SSE，不访问队列注册中心")
    void missingPluginDescriptorStillShieldsAndClosesStreams() {
        PluginScheduleContributionRegistrar scheduleRegistrar = mock(PluginScheduleContributionRegistrar.class);
        PluginStreamRegistry streamRegistry = mock(PluginStreamRegistry.class);
        QueueOperationRegistry queueRegistry = mock(QueueOperationRegistry.class);
        PluginRuntimeTaskQuiescer quiescer =
                new PluginRuntimeTaskQuiescer(scheduleRegistrar, streamRegistry, queueRegistry);

        quiescer.quiesce("ext-demo", Optional.empty());

        verify(scheduleRegistrar).unregister("ext-demo");
        verify(streamRegistry).closeForPlugin("ext-demo");
        verify(queueRegistry, never()).resolve(org.mockito.ArgumentMatchers.anyString());
    }

    private static PixivFeaturePlugin pluginWithQueueTypes(String... queueTypes) {
        List<QueueTypeContribution> contributions = Arrays.stream(queueTypes)
                .map(type -> new QueueTypeContribution(
                        "ext-demo", type, "test", "queue." + type, 0, null))
                .toList();
        return new PixivFeaturePlugin() {
            @Override
            public String id() {
                return "ext-demo";
            }

            @Override
            public String displayName() {
                return "plugin.name";
            }

            @Override
            public String description() {
                return "plugin.description";
            }

            @Override
            public PluginKind kind() {
                return PluginKind.FEATURE;
            }

            @Override
            public List<QueueTypeContribution> queueTypes() {
                return contributions;
            }
        };
    }

    private abstract static class DelegatingPlugin implements PixivFeaturePlugin {
        private final PixivFeaturePlugin delegate;

        private DelegatingPlugin(PixivFeaturePlugin delegate) {
            this.delegate = delegate;
        }

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public String displayName() {
            return delegate.displayName();
        }

        @Override
        public String description() {
            return delegate.description();
        }

        @Override
        public PluginKind kind() {
            return delegate.kind();
        }
    }
}
