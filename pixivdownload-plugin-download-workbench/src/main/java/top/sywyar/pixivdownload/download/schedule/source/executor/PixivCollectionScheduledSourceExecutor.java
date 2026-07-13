package top.sywyar.pixivdownload.download.schedule.source.executor;

import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledPendingReplayPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;

import java.util.Objects;

/** Pixiv 珍藏集混合作品来源执行适配器。 */
@PluginManagedBean
public final class PixivCollectionScheduledSourceExecutor implements ScheduledSourceExecutor {

    private final PixivScheduledSourceSupport support;

    public PixivCollectionScheduledSourceExecutor(PixivScheduledSourceSupport support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    public String sourceType() {
        return "collection";
    }

    @Override
    public ScheduledPendingReplayPolicy pendingReplayPolicy() {
        return ScheduledPendingReplayPolicy.REDISCOVERED_ONLY;
    }

    @Override
    public ScheduledExecutionPlan plan(ScheduledTaskDefinition task) throws ScheduledExecutionException {
        return support.planCollection(task);
    }

    @Override
    public ScheduledDiscoveryResult discover(ScheduledSourceContext context) throws ScheduledExecutionException {
        return support.discoverCollection(context);
    }
}
