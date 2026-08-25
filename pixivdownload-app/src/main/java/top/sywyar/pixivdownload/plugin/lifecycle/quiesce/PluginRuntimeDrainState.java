package top.sywyar.pixivdownload.plugin.lifecycle.quiesce;

import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueDrain;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleException;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestGenerationDrain;

import java.util.ArrayList;
import java.util.List;

/**
 * 保存单个插件停止过程中的精确清退凭据，并统一负责等待、归零断言和下一代重置。
 */
public final class PluginRuntimeDrainState {

    private PluginRequestGenerationDrain requestDrain;
    private boolean requestWithdrawalComplete;
    private ExternalCapabilityDrain capabilityDrain;
    private boolean capabilityWithdrawalComplete;
    private ScheduleGenerationDrain scheduleDrain;
    private boolean scheduleWithdrawalComplete;
    private PluginRuntimeTaskDrain runtimeTaskDrain;
    private boolean taskPreparationComplete;
    private final List<QueueDrain> queueDrains = new ArrayList<>();
    private boolean queuePreparationComplete;
    private boolean runtimeTasksQuiesced;

    public boolean requestWithdrawalComplete() {
        return requestWithdrawalComplete;
    }

    public void completeRequestWithdrawal(PluginRequestGenerationDrain drain) {
        requestDrain = drain;
        requestWithdrawalComplete = true;
    }

    public boolean capabilityWithdrawalComplete() {
        return capabilityWithdrawalComplete;
    }

    public ExternalCapabilityDrain capabilityDrain() {
        return capabilityDrain;
    }

    public void completeCapabilityWithdrawal(ExternalCapabilityDrain drain) {
        capabilityDrain = drain;
        capabilityWithdrawalComplete = true;
    }

    public boolean scheduleWithdrawalComplete() {
        return scheduleWithdrawalComplete;
    }

    public ScheduleGenerationDrain scheduleDrain() {
        return scheduleDrain;
    }

    public void completeScheduleWithdrawal(ScheduleGenerationDrain drain) {
        scheduleDrain = drain;
        scheduleWithdrawalComplete = true;
    }

    public boolean taskPreparationComplete() {
        return taskPreparationComplete;
    }

    public PluginRuntimeTaskDrain runtimeTaskDrain() {
        return runtimeTaskDrain;
    }

    public void rememberRuntimeTaskDrain(PluginRuntimeTaskDrain drain) {
        runtimeTaskDrain = drain;
    }

    public void markTaskPreparationComplete() {
        taskPreparationComplete = true;
    }

    public boolean queuePreparationComplete() {
        return queuePreparationComplete;
    }

    public List<QueueDrain> queueDrains() {
        return List.copyOf(queueDrains);
    }

    public void rememberQueueDrain(QueueDrain drain) {
        queueDrains.add(drain);
    }

    public void markQueuePreparationComplete() {
        queuePreparationComplete = true;
    }

    public boolean runtimeTasksQuiesced() {
        return runtimeTasksQuiesced;
    }

    public void markRuntimeTasksQuiesced() {
        runtimeTasksQuiesced = true;
    }

    public boolean preparationsComplete() {
        return requestWithdrawalComplete
                && capabilityWithdrawalComplete
                && scheduleWithdrawalComplete
                && taskPreparationComplete
                && queuePreparationComplete;
    }

    public boolean quiesceComplete() {
        return preparationsComplete() && runtimeTasksQuiesced;
    }

    public void reset() {
        requestDrain = null;
        requestWithdrawalComplete = false;
        capabilityDrain = null;
        capabilityWithdrawalComplete = false;
        scheduleDrain = null;
        scheduleWithdrawalComplete = false;
        runtimeTaskDrain = null;
        taskPreparationComplete = false;
        queueDrains.clear();
        queuePreparationComplete = false;
        runtimeTasksQuiesced = false;
    }

    /** 使用同一个绝对截止时间等待全部清退，返回首个仍活动的纯值诊断。 */
    public String awaitDrained(long deadlineNanos) {
        if (requestDrain != null && !requestDrain.awaitDrained(deadlineNanos)) {
            return "request lease(s)=" + requestDrain.activeLeaseCount();
        }
        if (capabilityDrain != null && !capabilityDrain.awaitDrained(deadlineNanos)) {
            return "capability invocation(s)=" + capabilityDrain.activeLeaseCount();
        }
        if (scheduleDrain != null && !scheduleDrain.awaitDrained(deadlineNanos)) {
            return "schedule lease(s)=" + scheduleDrain.activeLeaseCount();
        }
        if (runtimeTaskDrain != null && !runtimeTaskDrain.awaitDrained(deadlineNanos)) {
            return "background task(s)=" + runtimeTaskDrain.activeCount();
        }
        for (QueueDrain queueDrain : queueDrains) {
            if (!queueDrain.awaitDrained(deadlineNanos)) {
                return "queue task(s) " + queueDrain.queueType() + "=" + queueDrain.activeCount();
            }
        }
        return null;
    }

    /** 清除等待过程中观察到的中断并持续等待，返回调用方最终是否需要恢复中断标记。 */
    public boolean awaitDrainedUninterruptibly() {
        boolean interrupted = false;
        while (requestDrain != null && !requestDrain.isDrained()) {
            if (!requestDrain.awaitDrained()) {
                interrupted |= Thread.interrupted();
            }
        }
        while (capabilityDrain != null && !capabilityDrain.isDrained()) {
            if (!capabilityDrain.awaitDrained()) {
                interrupted |= Thread.interrupted();
            }
        }
        while (scheduleDrain != null && !scheduleDrain.isDrained()) {
            if (!scheduleDrain.awaitDrained()) {
                interrupted |= Thread.interrupted();
            }
        }
        while (runtimeTaskDrain != null && !runtimeTaskDrain.isDrained()) {
            if (!runtimeTaskDrain.awaitDrained()) {
                interrupted |= Thread.interrupted();
            }
        }
        for (QueueDrain queueDrain : queueDrains) {
            while (!queueDrain.isDrained()) {
                if (!queueDrain.awaitDrained()) {
                    interrupted |= Thread.interrupted();
                }
            }
        }
        return interrupted;
    }

    public void assertDrained(String pluginId) {
        if (requestDrain != null && !requestDrain.isDrained()) {
            throw new PluginLifecycleException("refusing to close child context with active request leases: "
                    + pluginId + " (active=" + requestDrain.activeLeaseCount() + ")");
        }
        if (capabilityDrain != null && !capabilityDrain.isDrained()) {
            throw new PluginLifecycleException("refusing to close child context with active capability invocations: "
                    + pluginId + " (active=" + capabilityDrain.activeLeaseCount() + ")");
        }
        if (scheduleDrain != null && !scheduleDrain.isDrained()) {
            throw new PluginLifecycleException("refusing to close child context with active schedule leases: "
                    + pluginId + " (active=" + scheduleDrain.activeLeaseCount() + ")");
        }
        if (runtimeTaskDrain != null && !runtimeTaskDrain.isDrained()) {
            throw new PluginLifecycleException("refusing to close child context with active background tasks: "
                    + pluginId + " (active=" + runtimeTaskDrain.activeCount() + ")");
        }
        for (QueueDrain queueDrain : queueDrains) {
            if (!queueDrain.isDrained()) {
                throw new PluginLifecycleException("refusing to close child context with active queue tasks: "
                        + pluginId + "/" + queueDrain.queueType()
                        + " (active=" + queueDrain.activeCount() + ")");
            }
        }
    }
}
