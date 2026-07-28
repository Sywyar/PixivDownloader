package top.sywyar.pixivdownload.plugin.api.schedule.capability;

import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 一轮计划执行所需来源、作品、凭证与 Guard 的原子复合租约。
 *
 * <p>租约不绑定创建线程；激活后可移交执行线程。能力只可在 {@link #isActive()} 为 {@code true} 时读取。
 * {@link #close()} 幂等，并一次释放来源及全部附加 owner。
 */
public interface ScheduleExecutionLease extends AutoCloseable {

    String sourceType();

    Set<ScheduleCapabilityOwner> owners();

    Optional<ScheduledSourceDescriptor> descriptor();

    Optional<ScheduledSourceExecutor> sourceExecutor();

    Optional<ScheduledWorkExecutor> workExecutor(String workType);

    Map<String, ScheduledWorkExecutor> workExecutors();

    Optional<ScheduleCapabilityOwner> workExecutorOwner(String workType);

    Map<String, ScheduleCapabilityOwner> workExecutorOwners();

    Optional<ScheduledCredentialPolicy> credentialPolicy();

    Optional<ScheduleCapabilityOwner> credentialPolicyOwner();

    Optional<ScheduledExecutionGuard> guard(String guardId);

    Map<String, ScheduledExecutionGuard> guards();

    Optional<ScheduleCapabilityOwner> guardOwner(String guardId);

    Map<String, ScheduleCapabilityOwner> guardOwners();

    ScheduledCancellation cancellation();

    boolean isActive();

    @Override
    void close();
}
