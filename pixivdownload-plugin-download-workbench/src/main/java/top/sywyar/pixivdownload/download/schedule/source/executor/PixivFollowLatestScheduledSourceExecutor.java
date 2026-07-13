package top.sywyar.pixivdownload.download.schedule.source.executor;

import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDraft;

import java.util.Objects;

/** Pixiv 已关注用户新作来源执行适配器。 */
@PluginManagedBean
public final class PixivFollowLatestScheduledSourceExecutor implements ScheduledSourceExecutor {

    private final PixivScheduledSourceSupport support;

    public PixivFollowLatestScheduledSourceExecutor(PixivScheduledSourceSupport support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    public String sourceType() {
        return "follow-latest";
    }

    @Override
    public ScheduledTaskDefinition prepare(ScheduledTaskDraft draft) throws ScheduledExecutionException {
        return support.prepare(draft);
    }

    @Override
    public ScheduledExecutionPlan plan(ScheduledTaskDefinition task) throws ScheduledExecutionException {
        return support.planFollowLatest(task);
    }

    @Override
    public ScheduledDiscoveryResult discover(ScheduledSourceContext context) throws ScheduledExecutionException {
        return support.discoverFollowLatest(context);
    }
}
