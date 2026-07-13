package top.sywyar.pixivdownload.schedule;

import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;

import java.util.List;

/** 测试专用的统一计划任务能力发布夹具。 */
final class ScheduleCapabilityTestFixture {

    static final ScheduleCapabilityOwner DOWNLOAD_WORKBENCH_OWNER =
            new ScheduleCapabilityOwner("download-workbench", "download-workbench", 1L);

    private ScheduleCapabilityTestFixture() {
    }

    static ScheduleCapabilityPublication publishDownloadWorkbench(
            ScheduleCapabilityRegistry registry, List<? extends ScheduledWorkRunner> runners) {
        return publish(registry, DOWNLOAD_WORKBENCH_OWNER,
                new DownloadWorkbenchPlugin().scheduledSources(), runners);
    }

    static ScheduleCapabilityPublication publish(
            ScheduleCapabilityRegistry registry,
            ScheduleCapabilityOwner owner,
            List<? extends ScheduledSourceProvider> sources,
            List<? extends ScheduledWorkRunner> runners) {
        return registry.publish(ScheduleOwnerBundle.prepare(
                owner,
                sources,
                runners,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));
    }
}
