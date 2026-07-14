package top.sywyar.pixivdownload.douyin.schedule.source;

import top.sywyar.pixivdownload.douyin.schedule.codec.DouyinScheduleCodec;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDraft;

import java.util.Objects;

/** 一个 canonical 抖音来源到共享发现驱动的薄适配器。 */
@PluginManagedBean
public final class DouyinScheduledSourceExecutor implements ScheduledSourceExecutor {

    private final String sourceType;
    private final DouyinScheduledSourceSupport support;

    public DouyinScheduledSourceExecutor(
            String sourceType,
            DouyinScheduledSourceSupport support) {
        if (!DouyinScheduleCodec.isSupportedSourceType(sourceType)) {
            throw new IllegalArgumentException("unsupported Douyin schedule source type");
        }
        this.sourceType = sourceType;
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    public String sourceType() {
        return sourceType;
    }

    @Override
    public ScheduledTaskDefinition prepare(ScheduledTaskDraft draft)
            throws ScheduledExecutionException {
        return support.prepare(draft);
    }

    @Override
    public ScheduledExecutionPlan plan(ScheduledTaskDefinition task)
            throws ScheduledExecutionException {
        return support.plan(task, sourceType);
    }

    @Override
    public ScheduledDiscoveryResult discover(ScheduledSourceContext context)
            throws ScheduledExecutionException {
        return support.discover(context, sourceType);
    }
}
