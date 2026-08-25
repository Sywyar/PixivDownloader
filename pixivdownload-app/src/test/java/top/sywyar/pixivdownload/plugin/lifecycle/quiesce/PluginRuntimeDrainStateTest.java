package top.sywyar.pixivdownload.plugin.lifecycle.quiesce;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueDrain;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleException;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestGenerationDrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("插件运行期清退状态")
class PluginRuntimeDrainStateTest {

    @Test
    @DisplayName("按固定顺序共享截止时间并返回首个活动凭据")
    void awaitsDrainsInOrderWithOneDeadline() {
        PluginRequestGenerationDrain request = mock(PluginRequestGenerationDrain.class);
        ExternalCapabilityDrain capability = mock(ExternalCapabilityDrain.class);
        ScheduleGenerationDrain schedule = mock(ScheduleGenerationDrain.class);
        PluginRuntimeTaskDrain task = mock(PluginRuntimeTaskDrain.class);
        QueueDrain queue = mock(QueueDrain.class);
        long deadline = 123L;
        when(request.awaitDrained(deadline)).thenReturn(true);
        when(capability.awaitDrained(deadline)).thenReturn(false);
        when(capability.activeLeaseCount()).thenReturn(3);
        PluginRuntimeDrainState state = new PluginRuntimeDrainState();
        state.completeRequestWithdrawal(request);
        state.completeCapabilityWithdrawal(capability);
        state.completeScheduleWithdrawal(schedule);
        state.rememberRuntimeTaskDrain(task);
        state.rememberQueueDrain(queue);

        assertThat(state.awaitDrained(deadline)).isEqualTo("capability invocation(s)=3");

        InOrder order = inOrder(request, capability);
        order.verify(request).awaitDrained(deadline);
        order.verify(capability).awaitDrained(deadline);
        verifyNoInteractions(schedule, task, queue);
    }

    @Test
    @DisplayName("完整清退可判定完成且重置后不保留上一代凭据")
    void resetClearsGenerationState() {
        PluginRuntimeDrainState state = new PluginRuntimeDrainState();
        state.completeRequestWithdrawal(null);
        state.completeCapabilityWithdrawal(mock(ExternalCapabilityDrain.class));
        state.completeScheduleWithdrawal(mock(ScheduleGenerationDrain.class));
        state.rememberRuntimeTaskDrain(mock(PluginRuntimeTaskDrain.class));
        state.markTaskPreparationComplete();
        state.rememberQueueDrain(mock(QueueDrain.class));
        state.markQueuePreparationComplete();
        state.markRuntimeTasksQuiesced();

        assertThat(state.preparationsComplete()).isTrue();
        assertThat(state.quiesceComplete()).isTrue();

        state.reset();

        assertThat(state.preparationsComplete()).isFalse();
        assertThat(state.quiesceComplete()).isFalse();
        assertThat(state.capabilityDrain()).isNull();
        assertThat(state.scheduleDrain()).isNull();
        assertThat(state.runtimeTaskDrain()).isNull();
        assertThat(state.queueDrains()).isEmpty();
    }

    @Test
    @DisplayName("仍有活动队列任务时拒绝关闭子上下文")
    void refusesToCloseWithActiveQueueTasks() {
        QueueDrain queue = mock(QueueDrain.class);
        when(queue.isDrained()).thenReturn(false);
        when(queue.queueType()).thenReturn("ext-queue");
        when(queue.activeCount()).thenReturn(2);
        PluginRuntimeDrainState state = new PluginRuntimeDrainState();
        state.rememberQueueDrain(queue);

        assertThatThrownBy(() -> state.assertDrained("ext-demo"))
                .isInstanceOf(PluginLifecycleException.class)
                .hasMessage("refusing to close child context with active queue tasks: "
                        + "ext-demo/ext-queue (active=2)");
    }
}
