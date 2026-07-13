package top.sywyar.pixivdownload.plugin.lifecycle.quiesce;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import top.sywyar.pixivdownload.core.download.queue.QueueGenerationDrain;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry.OwnedQueueOperations;
import top.sywyar.pixivdownload.core.download.queue.QueueOperations;
import top.sywyar.pixivdownload.core.download.queue.QueueTaskTracker;
import top.sywyar.pixivdownload.core.schedule.capability.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.ScheduleContributionLifecycleAuthority;
import top.sywyar.pixivdownload.plugin.lifecycle.ScheduleContributionLifecycleAuthorityTestAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

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
    @DisplayName("先保存 owner 精确队列 drain 再关闭 SSE 并发送取消")
    void savesExactQueueDrainBeforeCallbacks() {
        PluginScheduleContributionRegistrar registrar = mock(PluginScheduleContributionRegistrar.class);
        PluginStreamRegistry streams = mock(PluginStreamRegistry.class);
        QueueOperationRegistry queues = mock(QueueOperationRegistry.class);
        QueueOperations operations = queueOperations("ext-illust");
        QueueGenerationDrain queueDrain = queueDrain("ext-illust", 7L);
        ScheduleCapabilityPublication publication = mock(ScheduleCapabilityPublication.class);
        ScheduleGenerationDrain scheduleDrain = mock(ScheduleGenerationDrain.class);
        when(registrar.withdraw(AUTHORITY, publication)).thenReturn(Optional.of(scheduleDrain));
        when(queues.operationsForOwner("ext-demo"))
                .thenReturn(List.of(owned("ext-illust", operations)));
        when(operations.prepareQuiesce()).thenReturn(queueDrain);
        PluginRuntimeTaskQuiescer quiescer = new PluginRuntimeTaskQuiescer(registrar, streams, queues);
        List<QueueGenerationDrain> persisted = new ArrayList<>();

        PluginRuntimeTaskQuiescer.QuiesceResult result = quiescer.withdrawSchedule(AUTHORITY, publication);
        quiescer.prepareQueueDrains("ext-demo", persisted, persisted::add);
        assertThat(persisted).containsExactly(queueDrain);
        quiescer.quiesceAfterScheduleWithdrawal("ext-demo", List.copyOf(persisted));

        assertThat(result.scheduleDrain()).contains(scheduleDrain);
        InOrder order = inOrder(registrar, queues, operations, streams);
        order.verify(registrar).withdraw(AUTHORITY, publication);
        order.verify(queues).operationsForOwner("ext-demo");
        order.verify(operations).prepareQuiesce();
        order.verify(streams).closeForPlugin("ext-demo");
        order.verify(queues).operationsForOwner("ext-demo");
        order.verify(operations).prepareQuiesce();
        order.verify(operations).cancelQuiescedTasks();
        verify(operations, never()).clearAll();
    }

    @Test
    @DisplayName("publication 撤回异常不可吞且不得越过安全门")
    void withdrawalFailureIsSafetyCritical() {
        PluginScheduleContributionRegistrar registrar = mock(PluginScheduleContributionRegistrar.class);
        PluginStreamRegistry streams = mock(PluginStreamRegistry.class);
        QueueOperationRegistry queues = mock(QueueOperationRegistry.class);
        ScheduleCapabilityPublication publication = mock(ScheduleCapabilityPublication.class);
        doThrow(new IllegalStateException("withdraw failed"))
                .when(registrar).withdraw(AUTHORITY, publication);
        PluginRuntimeTaskQuiescer quiescer = new PluginRuntimeTaskQuiescer(registrar, streams, queues);

        assertThatThrownBy(() -> quiescer.withdrawSchedule(AUTHORITY, publication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("withdraw failed");

        verifyNoInteractions(streams, queues);
    }

    @Test
    @DisplayName("已过期 publication token 被明确拒绝")
    void stalePublicationIsRejected() {
        PluginScheduleContributionRegistrar registrar = mock(PluginScheduleContributionRegistrar.class);
        ScheduleCapabilityPublication publication = mock(ScheduleCapabilityPublication.class);
        when(registrar.withdraw(AUTHORITY, publication)).thenReturn(Optional.empty());
        PluginRuntimeTaskQuiescer quiescer = new PluginRuntimeTaskQuiescer(
                registrar, mock(PluginStreamRegistry.class), mock(QueueOperationRegistry.class));

        assertThatThrownBy(() -> quiescer.withdrawSchedule(AUTHORITY, publication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer active");
    }

    @Test
    @DisplayName("队列取消失败会阻断 teardown 但不妨碍其余队列收到取消")
    void queueCancellationFailuresBlockAfterBestEffortCleanup() {
        PluginStreamRegistry streams = mock(PluginStreamRegistry.class);
        QueueOperationRegistry queues = mock(QueueOperationRegistry.class);
        QueueOperations broken = queueOperations("broken");
        QueueOperations healthy = queueOperations("healthy");
        QueueGenerationDrain brokenDrain = queueDrain("broken", 11L);
        QueueGenerationDrain healthyDrain = queueDrain("healthy", 12L);
        when(queues.operationsForOwner("ext-demo"))
                .thenReturn(List.of(owned("broken", broken), owned("healthy", healthy)));
        when(broken.prepareQuiesce()).thenReturn(brokenDrain);
        when(healthy.prepareQuiesce()).thenReturn(healthyDrain);
        doThrow(new IllegalStateException("queue failed")).when(broken).cancelQuiescedTasks();
        PluginRuntimeTaskQuiescer quiescer = new PluginRuntimeTaskQuiescer(
                mock(PluginScheduleContributionRegistrar.class), streams, queues);
        List<QueueGenerationDrain> persisted = new ArrayList<>();
        quiescer.prepareQueueDrains("ext-demo", persisted, persisted::add);

        assertThatThrownBy(() -> quiescer.quiesceAfterScheduleWithdrawal("ext-demo", persisted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("queue failed");

        verify(healthy).cancelQuiescedTasks();
    }

    @Test
    @DisplayName("准备中出现致命错误时已取得的 drain 已保存且可按同代重试")
    void partialPreparationSurvivesFatalRetry() {
        QueueOperationRegistry queues = mock(QueueOperationRegistry.class);
        QueueOperations first = queueOperations("first");
        QueueOperations second = queueOperations("second");
        QueueGenerationDrain firstDrain = queueDrain("first", 21L);
        QueueGenerationDrain secondDrain = queueDrain("second", 22L);
        OutOfMemoryError fatal = new OutOfMemoryError("prepare-fatal");
        when(queues.operationsForOwner("ext-demo"))
                .thenReturn(List.of(owned("first", first), owned("second", second)));
        when(first.prepareQuiesce()).thenReturn(firstDrain);
        when(second.prepareQuiesce()).thenThrow(fatal).thenReturn(secondDrain);
        PluginRuntimeTaskQuiescer quiescer = new PluginRuntimeTaskQuiescer(
                mock(PluginScheduleContributionRegistrar.class), mock(PluginStreamRegistry.class), queues);
        List<QueueGenerationDrain> persisted = new ArrayList<>();

        assertThatThrownBy(() -> quiescer.prepareQueueDrains("ext-demo", persisted, persisted::add))
                .isSameAs(fatal);
        assertThat(persisted).containsExactly(firstDrain);

        assertThatCode(() -> quiescer.prepareQueueDrains("ext-demo", persisted, persisted::add))
                .doesNotThrowAnyException();
        assertThat(persisted).containsExactly(firstDrain, secondDrain);
    }

    @Test
    @DisplayName("取消前队列 generation 被替换时拒绝触达新代 callback")
    void replacementGenerationIsRejected() {
        QueueOperationRegistry queues = mock(QueueOperationRegistry.class);
        QueueOperations operations = queueOperations("ext-illust");
        QueueGenerationDrain expected = queueDrain("ext-illust", 31L);
        QueueGenerationDrain replacement = queueDrain("ext-illust", 32L);
        when(queues.operationsForOwner("ext-demo"))
                .thenReturn(List.of(owned("ext-illust", operations)));
        when(operations.prepareQuiesce()).thenReturn(replacement);
        PluginRuntimeTaskQuiescer quiescer = new PluginRuntimeTaskQuiescer(
                mock(PluginScheduleContributionRegistrar.class), mock(PluginStreamRegistry.class), queues);

        assertThatThrownBy(() -> quiescer.quiesceAfterScheduleWithdrawal("ext-demo", List.of(expected)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generation changed");

        verify(operations, never()).cancelQuiescedTasks();
    }

    @Test
    @DisplayName("SSE 致命错误延后到队列取消完成后按原对象重抛")
    void fatalStreamFailureIsDeferredWithoutLosingIdentity() {
        PluginStreamRegistry streams = mock(PluginStreamRegistry.class);
        QueueOperationRegistry queues = mock(QueueOperationRegistry.class);
        QueueOperations operations = queueOperations("ext-illust");
        QueueGenerationDrain drain = queueDrain("ext-illust", 41L);
        ThreadDeath fatal = new ThreadDeath();
        doThrow(fatal).when(streams).closeForPlugin("ext-demo");
        when(queues.operationsForOwner("ext-demo"))
                .thenReturn(List.of(owned("ext-illust", operations)));
        when(operations.prepareQuiesce()).thenReturn(drain);
        PluginRuntimeTaskQuiescer quiescer = new PluginRuntimeTaskQuiescer(
                mock(PluginScheduleContributionRegistrar.class), streams, queues);

        assertThatThrownBy(() -> quiescer.quiesceAfterScheduleWithdrawal("ext-demo", List.of(drain)))
                .isSameAs(fatal);

        verify(operations).cancelQuiescedTasks();
    }

    @Test
    @DisplayName("SSE 普通关闭失败会阻断 teardown 且队列仍收到取消")
    void nonFatalStreamFailureBlocksAfterQueueCancellation() {
        PluginStreamRegistry streams = mock(PluginStreamRegistry.class);
        QueueOperationRegistry queues = mock(QueueOperationRegistry.class);
        QueueOperations operations = queueOperations("ext-illust");
        QueueGenerationDrain drain = queueDrain("ext-illust", 51L);
        IllegalStateException failure = new IllegalStateException("stream-close-failed");
        doThrow(failure).when(streams).closeForPlugin("ext-demo");
        when(queues.operationsForOwner("ext-demo"))
                .thenReturn(List.of(owned("ext-illust", operations)));
        when(operations.prepareQuiesce()).thenReturn(drain);
        PluginRuntimeTaskQuiescer quiescer = new PluginRuntimeTaskQuiescer(
                mock(PluginScheduleContributionRegistrar.class), streams, queues);

        assertThatThrownBy(() -> quiescer.quiesceAfterScheduleWithdrawal("ext-demo", List.of(drain)))
                .isSameAs(failure);

        verify(operations).cancelQuiescedTasks();
    }

    @Test
    @DisplayName("SSE 普通失败后队列致命错误升级为主失败且保留原诊断")
    void laterQueueFatalTakesPriorityOverStreamFailure() {
        PluginStreamRegistry streams = mock(PluginStreamRegistry.class);
        QueueOperationRegistry queues = mock(QueueOperationRegistry.class);
        QueueOperations operations = queueOperations("ext-illust");
        QueueGenerationDrain drain = queueDrain("ext-illust", 52L);
        IllegalStateException streamFailure = new IllegalStateException("stream-nonfatal");
        OutOfMemoryError queueFatal = new OutOfMemoryError("queue-fatal");
        doThrow(streamFailure).when(streams).closeForPlugin("ext-demo");
        when(queues.operationsForOwner("ext-demo"))
                .thenReturn(List.of(owned("ext-illust", operations)));
        when(operations.prepareQuiesce()).thenReturn(drain);
        doThrow(queueFatal).when(operations).cancelQuiescedTasks();
        PluginRuntimeTaskQuiescer quiescer = new PluginRuntimeTaskQuiescer(
                mock(PluginScheduleContributionRegistrar.class), streams, queues);

        assertThatThrownBy(() -> quiescer.quiesceAfterScheduleWithdrawal("ext-demo", List.of(drain)))
                .isSameAs(queueFatal);

        assertThat(queueFatal.getSuppressed()).contains(streamFailure);
    }

    @Test
    @DisplayName("teardown 使用注册时捕获的 queueType 且不重读可变插件 getter")
    void teardownUsesCapturedQueueTypeWithoutPluginGetter() {
        AtomicBoolean getterUnavailable = new AtomicBoolean();
        QueueTaskTracker tracker = new QueueTaskTracker("stable-type");
        QueueOperations operations = new QueueOperations() {
            @Override
            public String queueType() {
                if (getterUnavailable.get()) {
                    throw new AssertionError("queueType getter is no longer safe");
                }
                return "stable-type";
            }

            @Override
            public QueueGenerationDrain prepareQuiesce() {
                return tracker.prepareQuiesce();
            }

            @Override
            public void cancelQuiescedTasks() {
                tracker.cancelQuiescedTasks();
            }

            @Override public int clearAll() { return 0; }
            @Override public int clearForOwner(String ownerUuid) { return 0; }
        };
        QueueOperationRegistry queues = new QueueOperationRegistry(List.of());
        queues.register("ext-demo", List.of(operations));
        getterUnavailable.set(true);
        PluginRuntimeTaskQuiescer quiescer = new PluginRuntimeTaskQuiescer(
                mock(PluginScheduleContributionRegistrar.class), mock(PluginStreamRegistry.class), queues);
        List<QueueGenerationDrain> drains = new ArrayList<>();

        assertThatCode(() -> {
            quiescer.prepareQueueDrains("ext-demo", drains, drains::add);
            quiescer.quiesceAfterScheduleWithdrawal("ext-demo", drains);
        }).doesNotThrowAnyException();

        assertThat(drains).singleElement().satisfies(drain ->
                assertThat(drain.queueType()).isEqualTo("stable-type"));
    }

    @Test
    @DisplayName("无 schedule 与队列时仍关闭 SSE 并返回空 drain")
    void pluginWithoutRuntimePublicationsStillClosesStreams() {
        PluginScheduleContributionRegistrar registrar = mock(PluginScheduleContributionRegistrar.class);
        PluginStreamRegistry streams = mock(PluginStreamRegistry.class);
        QueueOperationRegistry queues = mock(QueueOperationRegistry.class);
        when(queues.operationsForOwner("ext-demo")).thenReturn(List.of());
        PluginRuntimeTaskQuiescer quiescer = new PluginRuntimeTaskQuiescer(registrar, streams, queues);
        List<QueueGenerationDrain> persisted = new ArrayList<>();

        assertThat(quiescer.withdrawSchedule(AUTHORITY, null).scheduleDrain()).isEmpty();
        quiescer.prepareQueueDrains("ext-demo", persisted, persisted::add);
        quiescer.quiesceAfterScheduleWithdrawal("ext-demo", persisted);

        assertThat(persisted).isEmpty();
        verify(registrar, never()).withdraw(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(streams).closeForPlugin("ext-demo");
    }

    private static QueueOperations queueOperations(String queueType) {
        QueueOperations operations = mock(QueueOperations.class);
        when(operations.queueType()).thenReturn(queueType);
        return operations;
    }

    private static OwnedQueueOperations owned(String queueType, QueueOperations operations) {
        return new OwnedQueueOperations(queueType, operations);
    }

    private static QueueGenerationDrain queueDrain(String queueType, long generation) {
        QueueGenerationDrain drain = mock(QueueGenerationDrain.class);
        when(drain.queueType()).thenReturn(queueType);
        when(drain.generation()).thenReturn(generation);
        return drain;
    }
}
