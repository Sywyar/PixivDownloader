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

    ScheduleCapabilityOwner owner();

    T capability();

    ScheduledCancellation cancellation();

    boolean isActive();

    @Override
    void close();
}
