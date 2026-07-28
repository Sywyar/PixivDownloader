package top.sywyar.pixivdownload.plugin.api.schedule.capability;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;

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

    ScheduleCapabilitySnapshot snapshot();

    Optional<? extends ScheduleCapabilityLease<ScheduleCapabilityOwner>> prepareOwner(
            String featurePluginId);

    Optional<? extends ScheduleCapabilityLease<ScheduledWorkExecutor>> prepareWorkExecutor(
            String workType);

    Optional<ScheduleCapabilityOwner> credentialPolicyOwner(String policyId);

    Optional<? extends SchedulePlanningLease> prepareSource(String sourceTypeOrAlias);

    /**
     * 激活单项租约。只有本端口准备且仍属于当前 publication 的开放租约才返回 {@code true}。
     */
    boolean activate(ScheduleCapabilityLease<?> lease);

    /**
     * 激活来源 planning 租约。只有本端口准备且仍属于当前 publication 的开放租约才返回 {@code true}。
     */
    boolean activate(SchedulePlanningLease lease);

    /**
     * 仅在 planning 租约仍活动且 publication 仍当前时执行宿主操作。
     *
     * <p>操作不得回调插件行为；宿主会把 currentness 复核、操作执行与 publication 撤回串行化。
     */
    <T> Optional<T> whileCurrentPublication(
            SchedulePlanningLease planning,
            Supplier<T> operation);

    /**
     * 准备一轮执行所需的原子复合租约。准备本身不转移 planning 租约的所有权。
     */
    Optional<? extends ScheduleExecutionLease> prepareExpansion(
            SchedulePlanningLease planning,
            ScheduledExecutionPlan plan);

    /**
     * 原子激活复合租约并把来源租约从 planning 转移到 execution。
     *
     * <p>成功后 planning 租约不再活动；失败时不得留下部分激活的附加 owner。
     */
    boolean activate(ScheduleExecutionLease execution);
}
