package top.sywyar.pixivdownload.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistryTestAccess;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleExecutionLease;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.core.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.download.schedule.credential.PixivScheduledCredentialPolicy;
import top.sywyar.pixivdownload.download.schedule.guard.PixivOveruseExecutionGuard;
import top.sywyar.pixivdownload.download.schedule.source.executor.PixivCollectionScheduledSourceExecutor;
import top.sywyar.pixivdownload.download.schedule.source.executor.PixivScheduledSourceSupport;
import top.sywyar.pixivdownload.download.schedule.work.PixivScheduledIllustWorkExecutor;
import top.sywyar.pixivdownload.download.schedule.work.ScheduledIllustWorkRunner;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardBinding;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardPoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.schedule.OveruseWarningService;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("download-workbench 生产计划能力装配")
class DownloadWorkbenchScheduleAssemblyTest {

    private static final ScheduleCapabilityOwner WORKBENCH_OWNER =
            new ScheduleCapabilityOwner("download-workbench", "download-workbench", 11L);
    private static final ScheduleCapabilityOwner NOVEL_OWNER =
            new ScheduleCapabilityOwner("novel", "novel", 7L);

    @Test
    @DisplayName("七类来源与插画执行器凭证策略 Guard 原子发布且珍藏集可租用跨 owner 小说执行器")
    void publishesOfficialBundleAndExpandsCollectionAcrossOwners() throws Exception {
        DownloadWorkbenchPluginConfiguration configuration =
                new DownloadWorkbenchPluginConfiguration();
        DownloadWorkbenchPlugin plugin = configuration.downloadWorkbenchPlugin();
        ObjectMapper objectMapper = new ObjectMapper();
        PixivSchedulePersistenceCodec persistenceCodec =
                new PixivSchedulePersistenceCodec(objectMapper);
        ArtworkDownloader artworkDownloader = mock(ArtworkDownloader.class);
        ScheduledIllustWorkRunner legacyIllustRunner =
                configuration.scheduledIllustWorkRunner(artworkDownloader);
        DownloadSettings downloadSettings = mock(DownloadSettings.class);
        when(downloadSettings.getMaxConcurrent()).thenReturn(3, 2);
        PixivScheduledIllustWorkExecutor illustExecutor =
                configuration.pixivScheduledIllustWorkExecutor(
                        mock(PixivFetchService.class),
                        mock(PixivDatabase.class),
                        artworkDownloader,
                        mock(WorkMetaCaptureService.class),
                        legacyIllustRunner,
                        persistenceCodec,
                        objectMapper,
                        downloadSettings);
        assertThat(illustExecutor.maxConcurrency()).isEqualTo(3);
        assertThat(illustExecutor.maxConcurrency()).isEqualTo(2);
        OveruseWarningService overuseWarningService = mock(OveruseWarningService.class);
        PixivScheduledCredentialPolicy credentialPolicy =
                configuration.pixivScheduledCredentialPolicy(
                        overuseWarningService, persistenceCodec);
        PixivOveruseExecutionGuard guard = configuration.pixivOveruseExecutionGuard(
                overuseWarningService, persistenceCodec, objectMapper);

        PixivScheduledSourceSupport support = mock(PixivScheduledSourceSupport.class);
        List<ScheduledSourceExecutor> sourceExecutors = List.of(
                configuration.pixivUserNewScheduledSourceExecutor(support),
                configuration.pixivUserRequestScheduledSourceExecutor(support),
                configuration.pixivSearchScheduledSourceExecutor(support),
                configuration.pixivSeriesScheduledSourceExecutor(support),
                configuration.pixivMyBookmarksScheduledSourceExecutor(support),
                configuration.pixivFollowLatestScheduledSourceExecutor(support),
                configuration.pixivCollectionScheduledSourceExecutor(support));

        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityRegistryTestAccess.publish(registry, ScheduleOwnerBundle.prepare(
                WORKBENCH_OWNER,
                plugin.scheduledSources(),
                List.of(legacyIllustRunner),
                plugin.scheduledSourceDescriptors(),
                sourceExecutors,
                List.of(illustExecutor),
                List.of(credentialPolicy),
                List.of(guard)));

        ScheduledWorkExecutor novelExecutor = mock(ScheduledWorkExecutor.class);
        when(novelExecutor.workType()).thenReturn("novel");
        ScheduleCapabilityRegistryTestAccess.publish(registry, ScheduleOwnerBundle.prepare(
                NOVEL_OWNER,
                List.of(), List.of(), List.of(), List.of(),
                List.of(novelExecutor), List.of(), List.of()));

        assertThat(sourceExecutors)
                .extracting(ScheduledSourceExecutor::sourceType)
                .containsExactly("user-new", "user-request", "search", "series",
                        "my-bookmarks", "follow-latest", "collection");
        assertThat(registry.snapshotView().owners())
                .filteredOn(view -> view.owner().equals(WORKBENCH_OWNER))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.sourceTypes()).containsExactlyInAnyOrder(
                            "user-new", "user-request", "search", "series",
                            "my-bookmarks", "follow-latest", "collection");
                    assertThat(view.legacySourceTypes()).containsExactlyInAnyOrder(
                            "user-new", "user-request", "search", "series",
                            "my-bookmarks", "follow-latest", "collection");
                    assertThat(view.workTypes()).containsExactly("illust");
                    assertThat(view.credentialPolicyIds()).containsExactly("pixiv-cookie");
                    assertThat(view.guardIds()).containsExactly("pixiv-overuse");
                });

        ScheduledTaskDefinition definition = mock(ScheduledTaskDefinition.class);
        ScheduledExecutionPlan plan = collectionPlan();
        when(support.planCollection(definition)).thenReturn(plan);
        PixivCollectionScheduledSourceExecutor collectionExecutor = sourceExecutors.stream()
                .filter(PixivCollectionScheduledSourceExecutor.class::isInstance)
                .map(PixivCollectionScheduledSourceExecutor.class::cast)
                .findFirst()
                .orElseThrow();

        SchedulePlanningLease planning = registry.prepareSource("collection").orElseThrow();
        try (planning) {
            assertThat(registry.activate(planning)).isTrue();
            assertThat(planning.sourceExecutor()).containsSame(collectionExecutor);
            ScheduleExecutionLease execution = registry.prepareExpansion(
                    planning, collectionExecutor.plan(definition)).orElseThrow();
            try (execution) {
                assertThat(registry.activate(execution)).isTrue();
                assertThat(execution.owners()).containsExactlyInAnyOrder(WORKBENCH_OWNER, NOVEL_OWNER);
                assertThat(execution.sourceExecutor()).containsSame(collectionExecutor);
                assertThat(execution.workExecutor("illust")).containsSame(illustExecutor);
                assertThat(execution.workExecutor("novel")).containsSame(novelExecutor);
                assertThat(execution.workExecutorOwner("illust")).contains(WORKBENCH_OWNER);
                assertThat(execution.workExecutorOwner("novel")).contains(NOVEL_OWNER);
                assertThat(execution.credentialPolicy()).containsSame(credentialPolicy);
                assertThat(execution.credentialPolicyOwner()).contains(WORKBENCH_OWNER);
                assertThat(execution.guard("pixiv-overuse")).containsSame(guard);
                assertThat(execution.guardOwner("pixiv-overuse")).contains(WORKBENCH_OWNER);
            }
        }
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
