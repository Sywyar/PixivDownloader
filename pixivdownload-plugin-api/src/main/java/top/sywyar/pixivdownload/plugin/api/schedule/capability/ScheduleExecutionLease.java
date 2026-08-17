package top.sywyar.pixivdownload.plugin.api.schedule.capability;

import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 一轮计划执行所需来源、作品、凭证与 Guard 的原子复合租约。
 *
 * <p>租约不绑定创建线程；激活后可移交执行线程。能力只可在 {@link #isActive()} 为 {@code true} 时读取。
 * {@link #close()} 幂等，并一次释放来源及全部附加 owner。
 */
public interface ScheduleExecutionLease extends AutoCloseable {

    /**
     * 返回来源类型。
     *
     * @return 方法返回的字符串
     */
    String sourceType();

    /**
     * 返回所有者集合。
     *
     * @return 方法返回的集合
     */
    Set<ScheduleCapabilityOwner> owners();

    /**
     * 返回描述符。
     *
     * @return 匹配的可选值
     */
    Optional<ScheduledSourceDescriptor> descriptor();

    /**
     * 返回对应值。
     *
     * @return 匹配的可选值
     */
    Optional<ScheduledSourceExecutor> sourceExecutor();

    /**
     * 执行对应操作并返回结果。
     *
     * @param workType 工作类型
     * @return 匹配的可选值
     */
    Optional<ScheduledWorkExecutor> workExecutor(String workType);

    /**
     * 返回对应值。
     *
     * @return 方法返回的映射
     */
    Map<String, ScheduledWorkExecutor> workExecutors();

    /**
     * 执行对应操作并返回结果。
     *
     * @param workType 工作类型
     * @return 匹配的可选值
     */
    Optional<ScheduleCapabilityOwner> workExecutorOwner(String workType);

    /**
     * 返回对应值。
     *
     * @return 方法返回的映射
     */
    Map<String, ScheduleCapabilityOwner> workExecutorOwners();

    /**
     * 执行对应操作并返回结果。
     *
     * @param workType 工作类型
     * @return 匹配的可选值
     */
    OptionalLong workExecutorPublicationId(String workType);

    /**
     * 返回对应值。
     *
     * @return 方法返回的映射
     */
    Map<String, Long> workExecutorPublicationIds();

    /**
     * 返回凭证策略。
     *
     * @return 匹配的可选值
     */
    Optional<ScheduledCredentialPolicy> credentialPolicy();

    /**
     * 返回凭证策略所有者。
     *
     * @return 匹配的可选值
     */
    Optional<ScheduleCapabilityOwner> credentialPolicyOwner();

    /**
     * 执行守卫并返回结果。
     *
     * @param guardId 守卫标识
     * @return 匹配的可选值
     */
    Optional<ScheduledExecutionGuard> guard(String guardId);

    /**
     * 返回守卫列表。
     *
     * @return 方法返回的映射
     */
    Map<String, ScheduledExecutionGuard> guards();

    /**
     * 执行守卫所有者并返回结果。
     *
     * @param guardId 守卫标识
     * @return 匹配的可选值
     */
    Optional<ScheduleCapabilityOwner> guardOwner(String guardId);

    /**
     * 返回守卫所有者集合。
     *
     * @return 方法返回的映射
     */
    Map<String, ScheduleCapabilityOwner> guardOwners();

    /**
     * 返回取消状态。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    ScheduledCancellation cancellation();

    /**
     * 判断激活状态是否满足条件。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean isActive();

    @Override
    void close();
}
