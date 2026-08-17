package top.sywyar.pixivdownload.plugin.api.schedule.capability;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;

import java.util.Optional;

/**
 * 来源 planning 的短租约；激活后才可读取来源行为。
 *
 * <p>租约不绑定创建线程；激活后可移交执行线程。复合 execution 激活成功时，来源租约的所有权原子转移，
 * 本租约随即不再活动。{@link #close()} 幂等，关闭未激活、已转移或已经关闭的租约均安全。
 */
public interface SchedulePlanningLease extends AutoCloseable {

    /**
     * 返回所有者。
     *
     * @return 方法返回的 {@code ScheduleCapabilityOwner} 实例
     */
    ScheduleCapabilityOwner owner();

    /**
     * 返回发布项标识。
     *
     * @return 方法返回的数值
     */
    long publicationId();

    /**
     * 返回对应值。
     *
     * @return 方法返回的字符串
     */
    String activationToken();

    /**
     * 返回来源类型。
     *
     * @return 方法返回的字符串
     */
    String sourceType();

    /**
     * 返回描述符。
     *
     * @return 匹配的可选值
     */
    Optional<ScheduledSourceDescriptor> descriptor();

    /**
     * 返回对应值。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    Optional<ScheduledSourceExecutor> sourceExecutor();

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
