package top.sywyar.pixivdownload.schedule.execution;

import top.sywyar.pixivdownload.core.schedule.capability.ScheduleExecutionLease;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialBindResult;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;

/**
 * 一次凭证绑定探活持有的复合 generation 租约。它只暴露安全 policy 身份与单次探活入口，关闭后不再持有插件 Bean。
 */
public final class ScheduleCredentialBindingLease implements AutoCloseable {

    private final ScheduleExecutionEngine engine;
    private final long taskId;
    private final String policyOwnerPluginId;
    private final String policyId;

    private ScheduleExecutionLease execution;
    private ScheduledTaskDefinition definition;
    private ScheduledNetworkRoute route;
    private boolean probed;

    ScheduleCredentialBindingLease(
            ScheduleExecutionEngine engine,
            long taskId,
            String policyOwnerPluginId,
            String policyId,
            ScheduleExecutionLease execution,
            ScheduledTaskDefinition definition,
            ScheduledNetworkRoute route) {
        this.engine = engine;
        this.taskId = taskId;
        this.policyOwnerPluginId = policyOwnerPluginId;
        this.policyId = policyId;
        this.execution = execution;
        this.definition = definition;
        this.route = route;
    }

    public String policyOwnerPluginId() {
        return policyOwnerPluginId;
    }

    public String policyId() {
        return policyId;
    }

    /** 每个 lease 只允许一次插件探活，失败后重试必须重新取得完整复合租约与 route。 */
    public synchronized ScheduledCredentialBindResult probe(String candidateSecret)
            throws ScheduledExecutionException {
        ensureActive();
        if (probed) {
            throw new IllegalStateException("schedule credential binding lease was already probed");
        }
        probed = true;
        return engine.probeCredentialForBinding(
                taskId, definition, route, execution, candidateSecret);
    }

    public synchronized void throwIfCancellationRequested() throws ScheduledExecutionException {
        ensureActive();
        execution.cancellation().throwIfCancellationRequested();
    }

    @Override
    public void close() {
        ScheduleExecutionLease closing = null;
        synchronized (this) {
            if (execution != null) {
                closing = execution;
                execution = null;
                definition = null;
                route = null;
            }
        }
        if (closing != null) {
            closing.close();
        }
    }

    private void ensureActive() {
        if (execution == null) {
            throw new IllegalStateException("schedule credential binding lease is closed");
        }
    }
}
