package top.sywyar.pixivdownload.schedule;

import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistryTestAccess;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;

import java.util.List;
import java.util.Optional;

/** 测试专用的统一计划任务能力发布夹具。 */
public final class ScheduleCapabilityTestFixture {

    static final ScheduleCapabilityOwner DOWNLOAD_WORKBENCH_OWNER =
            new ScheduleCapabilityOwner("download-workbench", "download-workbench", 1L);

    private ScheduleCapabilityTestFixture() {
    }

    public static ScheduleCapabilityPublication publishDownloadWorkbench(
            ScheduleCapabilityRegistry registry, List<? extends ScheduledWorkRunner> runners) {
        return publish(registry, DOWNLOAD_WORKBENCH_OWNER,
                new DownloadWorkbenchPlugin().scheduledSources(), runners);
    }

    public static ScheduleCapabilityPublication publish(
            ScheduleCapabilityRegistry registry,
            ScheduleCapabilityOwner owner,
            List<? extends ScheduledSourceProvider> sources,
            List<? extends ScheduledWorkRunner> runners) {
        return publish(registry, ScheduleOwnerBundle.prepare(
                owner,
                sources,
                runners,
                List.of(),
                List.of(),
                List.of(),
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
