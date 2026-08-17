package top.sywyar.pixivdownload.plugin.api.schedule.capability;

import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 计划任务宿主访问当前插件能力的稳定端口。
 *
 * <p>本端口只允许读取不可变快照与取得短租约；owner publication 的注册、撤回和 drain 仍由宿主运行时管理。
 * 准备得到的租约不绑定创建线程，可在激活前后移交给执行线程，但调用方必须串行化同一租约的激活、使用和关闭。
 * 本端口只接受自己准备的租约；传入其它实现、已经关闭或已经失效的 publication 租约时，激活返回 {@code false}，
 * currentness 操作与复合扩展返回空。
 */
public interface ScheduleCapabilityAccess {

    /**
     * 返回快照。
     *
     * @return 方法返回的 {@code ScheduleCapabilitySnapshot} 实例
     */
    ScheduleCapabilitySnapshot snapshot();

    /**
     * 执行对应操作并返回结果。
     *
     * @param featurePluginId {@code featurePluginId} 对应的值
     * @return 匹配的可选值
     */
    Optional<? extends ScheduleCapabilityLease<ScheduleCapabilityOwner>> prepareOwner(
            String featurePluginId);

    /**
     * 执行对应操作并返回结果。
     *
     * @param workType 工作类型
     * @return 匹配的可选值
     */
    Optional<? extends ScheduleCapabilityLease<ScheduledWorkExecutor>> prepareWorkExecutor(
            String workType);

    /**
     * 执行对应操作并返回结果。
     *
     * @param policyId 策略标识
     * @return 匹配的可选值
     */
    Optional<? extends ScheduleCapabilityLease<ScheduledCredentialPolicy>> prepareCredentialPolicy(
            String policyId);

    /**
     * 执行凭证策略所有者并返回结果。
     *
     * @param policyId 策略标识
     * @return 匹配的可选值
     */
    Optional<ScheduleCapabilityOwner> credentialPolicyOwner(String policyId);

    /**
     * 执行对应操作并返回结果。
     *
     * @param sourceTypeOrAlias {@code sourceTypeOrAlias} 对应的值
     * @return 匹配的可选值
     */
    Optional<? extends SchedulePlanningLease> prepareSource(String sourceTypeOrAlias);

    /**
     * 激活单项租约。只有本端口准备且仍属于当前 publication 的开放租约才返回 {@code true}。
     *
     * @param lease 租约
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean activate(ScheduleCapabilityLease<?> lease);

    /**
     * 激活来源 planning 租约。只有本端口准备且仍属于当前 publication 的开放租约才返回 {@code true}。
     *
     * @param lease 租约
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean activate(SchedulePlanningLease lease);

    /**
     * 仅在 planning 租约仍活动且 publication 仍当前时执行宿主操作。
     *
     * <p>操作不得回调插件行为；宿主会把 currentness 复核、操作执行与 publication 撤回串行化。
     *
     * @param <T> 类型参数
     * @param planning 规划租约
     * @param operation 操作
     * @return 匹配的可选值
     */
    <T> Optional<T> whileCurrentPublication(
            SchedulePlanningLease planning,
            Supplier<T> operation);

    /**
     * 仅在单项租约仍活动且精确 publication 仍当前时执行宿主操作。
     *
     * <p>插件行为回调必须在进入本方法前完成；操作不得调用租约能力。宿主会把 currentness 复核、
     * 操作执行与 publication 撤回串行化。
     *
     * @param <T> 类型参数
     * @param lease 租约
     * @param operation 操作
     * @return 匹配的可选值
     */
    <T> Optional<T> whileCurrentPublication(
            ScheduleCapabilityLease<?> lease,
            Supplier<T> operation);

    /**
     * 仅在复合执行租约仍活动且其中每个 owner 的精确 publication 都仍当前时执行宿主操作。
     *
     * <p>全部插件行为回调必须在进入本方法前完成；操作不得调用租约能力。宿主会把所有 owner 的
     * currentness 复核、操作执行与 publication 撤回串行化。旧宿主实现默认 fail-closed，避免把
     * 未受保护的写入误当成成功。
     *
     * @param <T> 类型参数
     * @param execution 执行租约
     * @param operation 操作
     * @return 匹配的可选值
     */
    default <T> Optional<T> whileCurrentPublication(
            ScheduleExecutionLease execution,
            Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        return Optional.empty();
    }

    /**
     * 准备一轮执行所需的原子复合租约。准备本身不转移 planning 租约的所有权。
     *
     * @param planning 规划租约
     * @param plan 执行计划
     * @return 匹配的可选值
     */
    Optional<? extends ScheduleExecutionLease> prepareExpansion(
            SchedulePlanningLease planning,
            ScheduledExecutionPlan plan);

    /**
     * 原子激活复合租约并把来源租约从 planning 转移到 execution。
     *
     * <p>成功后 planning 租约不再活动；失败时不得留下部分激活的附加 owner。
     *
     * @param execution 执行租约
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean activate(ScheduleExecutionLease execution);
}
