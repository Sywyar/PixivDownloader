package top.sywyar.pixivdownload.schedule;

import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;

import java.util.List;
import java.util.Optional;

/** 测试专用的统一计划能力稳定端口夹具。 */
public final class ScheduleCapabilityTestFixture {

    public record CapabilityBundle(
            ScheduleCapabilityOwner owner,
            List<? extends ScheduledSourceDescriptor> sourceDescriptors,
            List<? extends ScheduledSourceExecutor> sourceExecutors,
            List<? extends ScheduledWorkExecutor> workExecutors,
            List<? extends ScheduledCredentialPolicy> credentialPolicies,
            List<? extends ScheduledExecutionGuard> guards
    ) {
    }

    static final ScheduleCapabilityOwner DOWNLOAD_WORKBENCH_OWNER =
            new ScheduleCapabilityOwner("download-workbench", "download-workbench", 1L);
    private static final ScheduledWorkExecutor HOST_MARKER_EXECUTOR = new ScheduledWorkExecutor() {
        @Override
        public String workType() {
            return "test.host-marker";
        }

        @Override
        public ScheduledWorkResult execute(ScheduledWork work, ScheduledWorkContext context) {
            return ScheduledWorkResult.completed();
        }
    };

    private ScheduleCapabilityTestFixture() {
    }

    public static CapabilityBundle bundle(
            ScheduleCapabilityOwner owner,
            List<? extends ScheduledSourceDescriptor> sourceDescriptors,
            List<? extends ScheduledSourceExecutor> sourceExecutors,
            List<? extends ScheduledWorkExecutor> workExecutors,
            List<? extends ScheduledCredentialPolicy> credentialPolicies,
            List<? extends ScheduledExecutionGuard> guards) {
        return new CapabilityBundle(
                owner, sourceDescriptors, sourceExecutors, workExecutors, credentialPolicies, guards);
    }

    public static FakeScheduleCapabilityAccess.Publication publishDownloadWorkbench(
            FakeScheduleCapabilityAccess access) {
        return publishDownloadWorkbench(access, List.of());
    }

    public static FakeScheduleCapabilityAccess.Publication publishDownloadWorkbench(
            FakeScheduleCapabilityAccess access,
            List<? extends ScheduledWorkExecutor> workExecutors) {
        List<? extends ScheduledWorkExecutor> effectiveExecutors = workExecutors.isEmpty()
                ? List.of(HOST_MARKER_EXECUTOR)
                : workExecutors;
        return publish(access, bundle(
                DOWNLOAD_WORKBENCH_OWNER,
                List.of(), List.of(), effectiveExecutors, List.of(), List.of()));
    }

    public static FakeScheduleCapabilityAccess.Publication publish(
            FakeScheduleCapabilityAccess access,
            ScheduleCapabilityOwner owner,
            List<? extends ScheduledSourceDescriptor> sourceDescriptors,
            List<? extends ScheduledSourceExecutor> sourceExecutors,
            List<? extends ScheduledWorkExecutor> workExecutors) {
        return publish(access, bundle(
                owner,
                sourceDescriptors,
                sourceExecutors,
                workExecutors,
                List.of(),
                List.of()));
    }

    public static FakeScheduleCapabilityAccess.Publication publish(
            FakeScheduleCapabilityAccess access,
            CapabilityBundle bundle) {
        return access.publish(
                bundle.owner(),
                bundle.sourceDescriptors(),
                bundle.sourceExecutors(),
                bundle.workExecutors(),
                bundle.credentialPolicies(),
                bundle.guards());
    }

    public static Optional<FakeScheduleCapabilityAccess.Drain> withdraw(
            FakeScheduleCapabilityAccess access,
            FakeScheduleCapabilityAccess.Publication publication) {
        return access.withdraw(publication);
    }
}
