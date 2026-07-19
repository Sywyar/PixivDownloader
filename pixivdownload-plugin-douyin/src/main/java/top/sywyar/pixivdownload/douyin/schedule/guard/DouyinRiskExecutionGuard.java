package top.sywyar.pixivdownload.douyin.schedule.guard;

import top.sywyar.pixivdownload.douyin.schedule.failure.DouyinScheduledFailureMapper;
import top.sywyar.pixivdownload.douyin.schedule.source.DouyinScheduledSourceDescriptors;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardContext;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardPoint;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardResult;

/** 把轮内凭证、挑战、限流与访问不可用失败映射为宿主统一执行的稳定动作。 */
@PluginManagedBean
public final class DouyinRiskExecutionGuard implements ScheduledExecutionGuard {

    @Override
    public String guardId() {
        return DouyinScheduledSourceDescriptors.GUARD_ID;
    }

    @Override
    public ScheduledGuardResult evaluate(ScheduledGuardContext context)
            throws ScheduledExecutionException {
        if (context == null) {
            throw new IllegalArgumentException("Douyin guard context must not be null");
        }
        if (context.point() != ScheduledGuardPoint.RUN_FAILURE) {
            return ScheduledGuardResult.decision(ScheduledGuardDecision.proceed());
        }
        return ScheduledGuardResult.decision(
                DouyinScheduledFailureMapper.guardDecision(context.failure()));
    }
}
