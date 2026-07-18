package top.sywyar.pixivdownload.novel.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxProxyClient;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistryTestAccess;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleExecutionLease;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.core.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.novel.NovelPluginConfiguration;
import top.sywyar.pixivdownload.novel.download.NovelDownloadExecutionLane;
import top.sywyar.pixivdownload.novel.download.NovelDownloader;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardBinding;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardPoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("novel 生产计划能力装配")
class PixivScheduledNovelCapabilityAssemblyTest {

    private static final ScheduleCapabilityOwner WORKBENCH_OWNER =
            new ScheduleCapabilityOwner("download-workbench", "download-workbench", 11L);
    private static final ScheduleCapabilityOwner NOVEL_OWNER =
            new ScheduleCapabilityOwner("novel", "novel", 7L);

    @Test
    @DisplayName("生产工厂贡献的小说执行器按真实下载池上限参与跨 owner 复合租约")
    void productionNovelExecutorExpandsWithCollectionSourceOwner() {
        NovelPluginConfiguration configuration = new NovelPluginConfiguration();
        PixivScheduledNovelWorkExecutor novelExecutor =
                configuration.pixivScheduledNovelWorkExecutor(
                        new ObjectMapper(),
                        mock(PixivAjaxProxyClient.class),
                        mock(WorkQueryService.class),
                        mock(WorkMetaCaptureService.class),
                        mock(NovelDownloader.class),
                        mock(NovelMergeService.class),
                        mock(NovelAutoTranslateService.class),
                        new NovelDownloadExecutionLane(new SyncTaskExecutor(), 3));
        assertThat(novelExecutor.workType()).isEqualTo("novel");
        assertThat(novelExecutor.maxConcurrency()).isEqualTo(3);

        ScheduledSourceExecutor collectionExecutor = mock(ScheduledSourceExecutor.class);
        when(collectionExecutor.sourceType()).thenReturn("collection");
        ScheduledWorkExecutor illustExecutor = mock(ScheduledWorkExecutor.class);
        when(illustExecutor.workType()).thenReturn("illust");
        ScheduledCredentialPolicy credentialPolicy = mock(ScheduledCredentialPolicy.class);
        when(credentialPolicy.policyId()).thenReturn("pixiv-cookie");
        ScheduledExecutionGuard guard = mock(ScheduledExecutionGuard.class);
        when(guard.guardId()).thenReturn("pixiv-overuse");

        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityRegistryTestAccess.publish(registry, ScheduleOwnerBundle.prepare(
                WORKBENCH_OWNER,
                List.of(),
                List.of(),
                List.of(collectionDescriptor()),
                List.of(collectionExecutor),
                List.of(illustExecutor),
                List.of(credentialPolicy),
                List.of(guard)));
        ScheduleCapabilityRegistryTestAccess.publish(registry, ScheduleOwnerBundle.prepare(
                NOVEL_OWNER,
                List.of(), List.of(), List.of(), List.of(),
                List.of(novelExecutor), List.of(), List.of()));

        SchedulePlanningLease planning = registry.prepareSource("collection").orElseThrow();
        try (planning) {
            assertThat(registry.activate(planning)).isTrue();
            ScheduleExecutionLease execution = registry.prepareExpansion(planning, collectionPlan()).orElseThrow();
            try (execution) {
                assertThat(registry.activate(execution)).isTrue();
                assertThat(execution.owners()).containsExactlyInAnyOrder(WORKBENCH_OWNER, NOVEL_OWNER);
                assertThat(execution.sourceExecutor()).containsSame(collectionExecutor);
                assertThat(execution.workExecutor("illust")).containsSame(illustExecutor);
                assertThat(execution.workExecutor("novel")).containsSame(novelExecutor);
                assertThat(execution.workExecutorOwner("novel")).contains(NOVEL_OWNER);
                assertThat(execution.credentialPolicyOwner()).contains(WORKBENCH_OWNER);
                assertThat(execution.guardOwner("pixiv-overuse")).contains(WORKBENCH_OWNER);
            }
        }
    }

    private static ScheduledSourceDescriptor collectionDescriptor() {
        return new ScheduledSourceDescriptor(
                "collection",
                Set.of("COLLECTION"),
                "pixiv.schedule.definition",
                1,
                new ScheduledSourcePresentation(
                        "batch", "schedule.type.collection", "schedule.type.collection",
                        "schedule", "pixiv"),
                Set.of("quick"),
                Set.of("illust", "novel"),
                Set.of("pixiv-cookie"),
                Set.of("pixiv-overuse"),
                null);
    }

    private static ScheduledExecutionPlan collectionPlan() {
        return new ScheduledExecutionPlan(
                Set.of("illust", "novel"),
                "pixiv-cookie",
                ScheduledCredentialRequirement.REQUIRED,
                false,
                List.of(new ScheduledGuardBinding(
                        "pixiv-overuse",
                        Set.of(
                                ScheduledGuardPoint.RUN_START,
                                ScheduledGuardPoint.WORK_BATCH,
                                ScheduledGuardPoint.RUN_END,
                                ScheduledGuardPoint.RUN_FAILURE),
                        500)),
                null,
                0,
                2,
                0L);
    }
}
