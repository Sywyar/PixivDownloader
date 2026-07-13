package top.sywyar.pixivdownload.plugin.lifecycle.quiesce;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.download.queue.QueueOperations;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.core.schedule.capability.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.ScheduleContributionLifecycleAuthority;
import top.sywyar.pixivdownload.plugin.lifecycle.ScheduleContributionLifecycleAuthorityTestAccess;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginStreamRegistry;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("插件运行期任务清退器")
class PluginRuntimeTaskQuiescerTest {

    private static final ScheduleContributionLifecycleAuthority AUTHORITY =
            ScheduleContributionLifecycleAuthorityTestAccess.create();

    @Test
    @DisplayName("按 publication 撤回、关闭 SSE、排空队列的固定顺序清退并返回精确 drain")
    void withdrawsThenClosesStreamsThenDrainsQueues() {
        PluginScheduleContributionRegistrar registrar = mock(PluginScheduleContributionRegistrar.class);
        PluginStreamRegistry streams = mock(PluginStreamRegistry.class);
        QueueOperationRegistry queues = mock(QueueOperationRegistry.class);
        QueueOperations operations = mock(QueueOperations.class);
        ScheduleCapabilityPublication publication = mock(ScheduleCapabilityPublication.class);
        ScheduleGenerationDrain drain = mock(ScheduleGenerationDrain.class);
        when(registrar.withdraw(AUTHORITY, publication)).thenReturn(Optional.of(drain));
        when(queues.resolve("ext-illust")).thenReturn(Optional.of(operations));
        PluginRuntimeTaskQuiescer quiescer = new PluginRuntimeTaskQuiescer(registrar, streams, queues);

        PluginRuntimeTaskQuiescer.QuiesceResult result = quiescer.quiesce(
                AUTHORITY, "ext-demo", publication, Optional.of(pluginWithQueueTypes("ext-illust")));

        assertThat(result.scheduleDrain()).contains(drain);
        InOrder order = inOrder(registrar, streams, queues, operations);
        order.verify(registrar).withdraw(AUTHORITY, publication);
        order.verify(streams).closeForPlugin("ext-demo");
        order.verify(queues).resolve("ext-illust");
        order.verify(operations).clearAll();
    }

    @Test
    @DisplayName("publication 撤回异常不可吞且不得越过安全门关闭 SSE 或排空队列")
    void withdrawalFailureIsSafetyCritical() {
        PluginScheduleContributionRegistrar registrar = mock(PluginScheduleContributionRegistrar.class);
        PluginStreamRegistry streams = mock(PluginStreamRegistry.class);
        QueueOperationRegistry queues = mock(QueueOperationRegistry.class);
        ScheduleCapabilityPublication publication = mock(ScheduleCapabilityPublication.class);
        doThrow(new IllegalStateException("withdraw failed"))
                .when(registrar).withdraw(AUTHORITY, publication);
        PluginRuntimeTaskQuiescer quiescer = new PluginRuntimeTaskQuiescer(registrar, streams, queues);

        assertThatThrownBy(() -> quiescer.quiesce(
                AUTHORITY, "ext-demo", publication, Optional.of(pluginWithQueueTypes("ext-illust"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("withdraw failed");

        verifyNoInteractions(streams, queues);
    }

    @Test
    @DisplayName("已过期 publication token 被明确拒绝，不能伪装成安全清退")
    void stalePublicationIsRejected() {
        PluginScheduleContributionRegistrar registrar = mock(PluginScheduleContributionRegistrar.class);
        PluginStreamRegistry streams = mock(PluginStreamRegistry.class);
        QueueOperationRegistry queues = mock(QueueOperationRegistry.class);
        ScheduleCapabilityPublication publication = mock(ScheduleCapabilityPublication.class);
        when(registrar.withdraw(AUTHORITY, publication)).thenReturn(Optional.empty());
        PluginRuntimeTaskQuiescer quiescer = new PluginRuntimeTaskQuiescer(registrar, streams, queues);

        assertThatThrownBy(() -> quiescer.quiesce(
                AUTHORITY, "ext-demo", publication, Optional.of(pluginWithQueueTypes())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer active");
        verifyNoInteractions(streams, queues);
    }

    @Test
    @DisplayName("撤回成功后 SSE 与各队列清退失败彼此隔离，后续队列仍会排空")
    void bestEffortStepsRemainIsolated() {
        PluginScheduleContributionRegistrar registrar = mock(PluginScheduleContributionRegistrar.class);
        PluginStreamRegistry streams = mock(PluginStreamRegistry.class);
        QueueOperationRegistry queues = mock(QueueOperationRegistry.class);
        QueueOperations broken = mock(QueueOperations.class);
        QueueOperations healthy = mock(QueueOperations.class);
        ScheduleCapabilityPublication publication = mock(ScheduleCapabilityPublication.class);
        ScheduleGenerationDrain drain = mock(ScheduleGenerationDrain.class);
        when(registrar.withdraw(AUTHORITY, publication)).thenReturn(Optional.of(drain));
        doThrow(new IllegalStateException("stream failed")).when(streams).closeForPlugin("ext-demo");
        when(queues.resolve("broken")).thenReturn(Optional.of(broken));
        when(queues.resolve("healthy")).thenReturn(Optional.of(healthy));
        when(broken.clearAll()).thenThrow(new IllegalStateException("queue failed"));
        PluginRuntimeTaskQuiescer quiescer = new PluginRuntimeTaskQuiescer(registrar, streams, queues);

        assertThatCode(() -> quiescer.quiesce(
                AUTHORITY, "ext-demo", publication,
                Optional.of(pluginWithQueueTypes("broken", "healthy"))))
                .doesNotThrowAnyException();

        verify(healthy).clearAll();
    }

    @Test
    @DisplayName("无 schedule publication 时仍关闭 SSE 并排空队列，返回空 drain")
    void pluginWithoutSchedulePublicationStillDrainsRuntimeTasks() {
        PluginScheduleContributionRegistrar registrar = mock(PluginScheduleContributionRegistrar.class);
        PluginStreamRegistry streams = mock(PluginStreamRegistry.class);
        QueueOperationRegistry queues = mock(QueueOperationRegistry.class);
        QueueOperations operations = mock(QueueOperations.class);
        when(queues.resolve("ext-illust")).thenReturn(Optional.of(operations));
        PluginRuntimeTaskQuiescer quiescer = new PluginRuntimeTaskQuiescer(registrar, streams, queues);

        PluginRuntimeTaskQuiescer.QuiesceResult result = quiescer.quiesce(
                AUTHORITY, "ext-demo", null, Optional.of(pluginWithQueueTypes("ext-illust")));

        assertThat(result.scheduleDrain()).isEmpty();
        verify(registrar, never()).withdraw(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(streams).closeForPlugin("ext-demo");
        verify(operations).clearAll();
    }

    private static PixivFeaturePlugin pluginWithQueueTypes(String... queueTypes) {
        List<QueueTypeContribution> contributions = Arrays.stream(queueTypes)
                .map(type -> new QueueTypeContribution(
                        "ext-demo", type, "test", "queue." + type, 0, null))
                .toList();
        return new PixivFeaturePlugin() {
            @Override public String id() { return "ext-demo"; }
            @Override public String displayName() { return "plugin.name"; }
            @Override public String description() { return "plugin.description"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override public List<QueueTypeContribution> queueTypes() { return contributions; }
        };
    }
}
