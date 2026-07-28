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

    ScheduleCapabilityOwner owner();

    long publicationId();

    String activationToken();

    String sourceType();

    Optional<ScheduledSourceDescriptor> descriptor();

    Optional<ScheduledSourceExecutor> sourceExecutor();

    ScheduledCancellation cancellation();

    boolean isActive();

    @Override
    void close();
}
