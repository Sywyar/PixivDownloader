package top.sywyar.pixivdownload.schedule;

import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistryTestAccess;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;

import java.util.List;
import java.util.Optional;

/** 测试专用的统一计划任务能力发布夹具。 */
public final class ScheduleCapabilityTestFixture {

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

    public static ScheduleCapabilityPublication publishDownloadWorkbench(
            ScheduleCapabilityRegistry registry) {
        return publishDownloadWorkbench(registry, List.of());
    }

    public static ScheduleCapabilityPublication publishDownloadWorkbench(
            ScheduleCapabilityRegistry registry,
            List<? extends ScheduledWorkExecutor> workExecutors) {
        List<? extends ScheduledWorkExecutor> effectiveExecutors = workExecutors.isEmpty()
                ? List.of(HOST_MARKER_EXECUTOR)
                : workExecutors;
        return publish(registry, ScheduleOwnerBundle.prepare(
                DOWNLOAD_WORKBENCH_OWNER,
                List.of(), List.of(), effectiveExecutors, List.of(), List.of()));
    }

    public static ScheduleCapabilityPublication publish(
            ScheduleCapabilityRegistry registry,
            ScheduleCapabilityOwner owner,
            List<? extends ScheduledSourceDescriptor> sourceDescriptors,
            List<? extends ScheduledSourceExecutor> sourceExecutors,
            List<? extends ScheduledWorkExecutor> workExecutors) {
        return publish(registry, ScheduleOwnerBundle.prepare(
                owner,
                sourceDescriptors,
                sourceExecutors,
                workExecutors,
                List.of(),
                List.of()));
    }

    public static ScheduleCapabilityPublication publish(
            ScheduleCapabilityRegistry registry, ScheduleOwnerBundle bundle) {
        return ScheduleCapabilityRegistryTestAccess.publish(registry, bundle);
    }

    public static Optional<ScheduleGenerationDrain> withdraw(
            ScheduleCapabilityRegistry registry, ScheduleCapabilityPublication publication) {
        return ScheduleCapabilityRegistryTestAccess.withdraw(registry, publication);
    }
}
