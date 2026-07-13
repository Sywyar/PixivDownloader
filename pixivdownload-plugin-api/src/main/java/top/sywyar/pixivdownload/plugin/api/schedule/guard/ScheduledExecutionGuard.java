package top.sywyar.pixivdownload.plugin.api.schedule.guard;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;

/** 插件贡献的站点风险、限流、挑战和凭证状态 Guard。调用位置由宿主固定。 */
public interface ScheduledExecutionGuard {

    /** 全局唯一、建议带插件命名空间的 Guard id。 */
    String guardId();

    ScheduledGuardDecision evaluate(ScheduledGuardContext context)
            throws ScheduledExecutionException;

    /**
     * 返回稳定决定及可选的受控证据。宿主统一调用本方法；默认包装既有
     * {@link #evaluate(ScheduledGuardContext)}，保持旧 Guard 实现二进制兼容。
     */
    default ScheduledGuardResult evaluateResult(ScheduledGuardContext context)
            throws ScheduledExecutionException {
        return ScheduledGuardResult.decision(evaluate(context));
    }
}
