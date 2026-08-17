package top.sywyar.pixivdownload.plugin.api.schedule.credential;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;

import java.util.Objects;
import java.util.Optional;

/**
 * 插件贡献的凭证格式校验、主动探活和非敏感账号识别策略。策略只返回结果，不直接写宿主任务状态或凭证表。
 * 探活结果、绑定结果及任何抛出异常均不得包含原始凭据或可逆派生材料。
 */
public interface ScheduledCredentialPolicy {

    /**
     * 全局唯一、建议带插件命名空间的策略 id。
     *
     * @return 方法返回的字符串
     */
    String policyId();

    /**
     * 执行探测并返回结果。
     *
     * @param context 上下文
     * @return 方法返回的 {@code ScheduledCredentialProbeResult} 实例
     * @throws ScheduledExecutionException 执行失败时抛出
     */
    ScheduledCredentialProbeResult probe(ScheduledCredentialContext context)
            throws ScheduledExecutionException;

    /**
     * 绑定时的一次性探活。默认复用 {@link #probe(ScheduledCredentialContext)}；需要绑定后任务级策略状态时，
     * 实现可在同一次网络调用中返回安全 evidence 与绑定后的任务级策略决定。
     *
     * @param context 上下文
     * @return 方法返回的 {@code ScheduledCredentialBindResult} 实例
     * @throws top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException 执行失败时抛出
     */
    default ScheduledCredentialBindResult probeForBinding(ScheduledCredentialContext context)
            throws ScheduledExecutionException {
        if (context == null || context.purpose() != ScheduledCredentialContext.Purpose.BIND) {
            throw new IllegalArgumentException("credential binding probe requires BIND purpose");
        }
        return ScheduledCredentialBindResult.fromProbe(probe(context));
    }

    /**
     * 把策略自有状态与挂起机器态投影为宿主可直接返回的安全状态。实现必须是无网络、无 secret 的纯函数。
     *
     * @param task 任务
     * @return 方法返回的 {@code ScheduledCredentialTaskPresentation} 实例
     */
    default ScheduledCredentialTaskPresentation taskPresentation(
            ScheduledCredentialTaskSnapshot task) {
        Objects.requireNonNull(task, "task");
        return ScheduledCredentialTaskPresentation.empty();
    }

    /**
     * 规划账号级策略恢复。实现只返回纯值计划，不得直接写宿主持久化、读取凭证或访问网络。
     *
     * @param request 请求
     * @return 匹配的可选值
     */
    default Optional<ScheduledCredentialAccountActionPlan> prepareAccountAction(
            ScheduledCredentialAccountActionRequest request) {
        Objects.requireNonNull(request, "request");
        return Optional.empty();
    }

    /**
     * 把已经持久化的账号级策略事件投影为安全通知场景与参数。实现必须是无网络、无 secret 的纯函数。
     *
     * @param incident 事件
     * @return 方法返回的 {@code ScheduledCredentialIncidentPresentation} 实例
     */
    default ScheduledCredentialIncidentPresentation incidentPresentation(
            ScheduledCredentialAccountIncident incident) {
        Objects.requireNonNull(incident, "incident");
        return ScheduledCredentialIncidentPresentation.empty();
    }
}
