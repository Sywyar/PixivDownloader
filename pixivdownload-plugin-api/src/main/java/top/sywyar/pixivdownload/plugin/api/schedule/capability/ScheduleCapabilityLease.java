package top.sywyar.pixivdownload.plugin.api.schedule.capability;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;

/**
 * 单项计划能力的短租约。调用方必须先把返回对象置于 {@code try/finally}，再请求宿主激活。
 *
 * <p>租约不绑定创建线程；激活后可移交执行线程。能力只可在 {@link #isActive()} 为 {@code true} 时读取。
 * {@link #close()} 幂等，关闭未激活、已转移或已经关闭的租约均安全。
 *
 * @param <T> 行为能力类型
 */
public interface ScheduleCapabilityLease<T> extends AutoCloseable {

    /**
     * 返回所有者。
     *
     * @return 方法返回的 {@code ScheduleCapabilityOwner} 实例
     */
    ScheduleCapabilityOwner owner();

    /**
     * 宿主盖章的精确 publication；同一 owner 重新发布时该值也会变化。
     *
     * @return 方法返回的数值
     */
    long publicationId();

    /**
     * 返回对应值。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    T capability();

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
