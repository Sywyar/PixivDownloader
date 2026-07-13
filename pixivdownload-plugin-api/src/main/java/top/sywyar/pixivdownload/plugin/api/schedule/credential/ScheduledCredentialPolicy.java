package top.sywyar.pixivdownload.plugin.api.schedule.credential;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;

/**
 * 插件贡献的凭证格式校验、主动探活和非敏感账号识别策略。策略只返回结果，不直接写宿主任务状态或凭证表。
 */
public interface ScheduledCredentialPolicy {

    /** 全局唯一、建议带插件命名空间的策略 id。 */
    String policyId();

    ScheduledCredentialProbeResult probe(ScheduledCredentialContext context)
            throws ScheduledExecutionException;

    /**
     * 绑定时的一次性探活。旧策略默认复用 {@link #probe(ScheduledCredentialContext)}，新策略可在一次网络调用中
     * 同时返回安全 evidence 与绑定后的任务级策略决定。
     */
    default ScheduledCredentialBindResult probeForBinding(ScheduledCredentialContext context)
            throws ScheduledExecutionException {
        if (context == null || context.purpose() != ScheduledCredentialContext.Purpose.BIND) {
            throw new IllegalArgumentException("credential binding probe requires BIND purpose");
        }
        return ScheduledCredentialBindResult.fromProbe(probe(context));
    }
}
