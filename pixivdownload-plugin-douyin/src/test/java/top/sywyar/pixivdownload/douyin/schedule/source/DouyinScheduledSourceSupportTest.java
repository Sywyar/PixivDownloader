package top.sywyar.pixivdownload.douyin.schedule.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.douyin.client.DouyinClient;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.model.DouyinAccount;
import top.sywyar.pixivdownload.douyin.model.DouyinAccountSource;
import top.sywyar.pixivdownload.douyin.model.DouyinCanonicalDownload;
import top.sywyar.pixivdownload.douyin.model.DouyinListing;
import top.sywyar.pixivdownload.douyin.model.DouyinParsedInput;
import top.sywyar.pixivdownload.douyin.model.DouyinWork;
import top.sywyar.pixivdownload.douyin.schedule.codec.DouyinScheduleCodec;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceTypes;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDraft;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledWorkSink;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("抖音计划来源发现执行")
class DouyinScheduledSourceSupportTest {

    private static final int FAVORITE_FOLDER_ITEM_COUNT = 3;
    private static final String VALID_COOKIE =
            "ttwid=tt; passport_csrf_token=csrf; sessionid=sid; sid_tt=sid";

    @Test
    @DisplayName("八类来源均声明完整计划并提交同路由同凭证的字符串作品")
    void discoversAllEightSourcesWithExactRelations() throws Exception {
        Map<String, String> definitions = definitions(10);
        Map<String, String> expectedRelationIds = Map.of(
                DouyinSourceTypes.USER, "MS4w.LjAB-user",
                DouyinSourceTypes.SEARCH, "风景",
                DouyinSourceTypes.COLLECTION, "collection-1",
                DouyinSourceTypes.MUSIC, "music-1",
                DouyinSourceTypes.ACCOUNT_OWN_WORKS, "account-1",
                DouyinSourceTypes.ACCOUNT_LIKED_WORKS, "liked",
                DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS, "favorites",
                DouyinSourceTypes.ACCOUNT_FAVORITE_COLLECTION, "favorite-collection-1");

        for (Map.Entry<String, String> entry : definitions.entrySet()) {
            RecordingClient client = new RecordingClient(List.of(work("7351234567890123456")));
            DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
            DouyinScheduledSourceSupport support = new DouyinScheduledSourceSupport(client, codec);
            DouyinScheduledSourceExecutor executor =
                    new DouyinScheduledSourceExecutor(entry.getKey(), support);
            ScheduledTaskDefinition task = executor.prepare(draft(entry.getKey(), entry.getValue()));
            CapturingContext context = new CapturingContext(task, null, Set.of());

            var plan = executor.plan(task);
            ScheduledDiscoveryResult result = executor.discover(context);

            assertThat(plan.requiredWorkTypes()).containsExactly("douyin");
            assertThat(plan.credentialPolicyId()).isEqualTo("douyin.cookie");
            assertThat(plan.credentialRequirement())
                    .isEqualTo(ScheduledCredentialRequirement.REQUIRED);
            assertThat(plan.anonymousFallbackAllowed()).isFalse();
            assertThat(plan.checkpointSchema())
                    .isEqualTo(DouyinScheduleCodec.CHECKPOINT_SCHEMA);
            assertThat(plan.guards()).singleElement()
                    .satisfies(binding -> assertThat(binding.guardId()).isEqualTo("douyin.risk"));
            assertThat(context.works).singleElement().satisfies(scheduled -> {
                assertThat(scheduled.key().workType()).isEqualTo("douyin");
                assertThat(scheduled.key().id()).isEqualTo("7351234567890123456");
                assertThat(scheduled.payloadJson()).doesNotContain("http", "sessionid");
                assertThat(scheduled.relations()).singleElement().satisfies(relation -> {
                    assertThat(relation.relationType()).isEqualTo(entry.getKey());
                    assertThat(relation.relationId())
                            .isEqualTo(expectedRelationIds.get(entry.getKey()));
                });
            });
            assertThat(result.candidateCheckpoint()).isNotNull();
            assertThat(client.routeOverrideObserved).isTrue();
            assertThat(client.lastCookie).isEqualTo(VALID_COOKIE);
            assertThat(OutboundProxyOverride.isActive()).isFalse();
        }
    }

    @Test
    @DisplayName("小于 known streak 的每轮上限可多轮抽干且空闲轮不重复提交")
    void smallFetchLimitDrainsAcrossRunsWithoutReplayingCompletedBacklog() throws Exception {
        for (String sourceType : List.of(
                DouyinSourceTypes.USER,
                DouyinSourceTypes.SEARCH,
                DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS)) {
            List<DouyinWork> works = List.of(
                    work("73510001"), work("73510002"), work("73510003"),
                    work("73510004"), work("73510005"), work("73510006"));
            RecordingClient client = new RecordingClient(works);
            DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
            DouyinScheduledSourceExecutor executor = new DouyinScheduledSourceExecutor(
                    sourceType, new DouyinScheduledSourceSupport(client, codec));
            ScheduledTaskDefinition task = executor.prepare(draft(
                    sourceType, definitions(2).get(sourceType)));
            ScheduledCheckpoint checkpoint = null;
            List<List<String>> submittedByRun = new ArrayList<>();

            for (int run = 0; run < 5; run++) {
                CapturingContext context = new CapturingContext(task, checkpoint, Set.of());
                ScheduledDiscoveryResult result = executor.discover(context);
                submittedByRun.add(context.works.stream().map(work -> work.key().id()).toList());
                checkpoint = result.candidateCheckpoint();
            }

            assertThat(submittedByRun).containsExactly(
                    List.of("73510001", "73510002"),
                    List.of("73510003", "73510004"),
                    List.of("73510005", "73510006"),
                    List.of(),
                    List.of());
            assertThat(codec.decodeCheckpoint(checkpoint).resumeAfter()).isNull();
        }
    }

    @Test
    @DisplayName("单项上限排空后旧项之后插入的新作品仍会发现且不重复")
    void reorderedNewWorkAfterKnownIdentityIsNotMissed() throws Exception {
        for (String sourceType : List.of(DouyinSourceTypes.USER, DouyinSourceTypes.SEARCH)) {
            RecordingClient client = new RecordingClient(
                    List.of(work("known-a"), work("known-b")));
            DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
            DouyinScheduledSourceExecutor executor = new DouyinScheduledSourceExecutor(
                    sourceType, new DouyinScheduledSourceSupport(client, codec));
            ScheduledTaskDefinition task = executor.prepare(draft(
                    sourceType, definitions(1).get(sourceType)));
            ScheduledCheckpoint checkpoint = null;
            List<List<String>> submittedByRun = new ArrayList<>();

            for (int run = 0; run < 3; run++) {
                CapturingContext context = new CapturingContext(task, checkpoint, Set.of());
                ScheduledDiscoveryResult result = executor.discover(context);
                submittedByRun.add(context.works.stream().map(work -> work.key().id()).toList());
                checkpoint = result.candidateCheckpoint();
            }
            client.works = List.of(work("known-a"), work("new-after-known"), work("known-b"));
            for (int run = 0; run < 3; run++) {
                CapturingContext context = new CapturingContext(task, checkpoint, Set.of());
                ScheduledDiscoveryResult result = executor.discover(context);
                submittedByRun.add(context.works.stream().map(work -> work.key().id()).toList());
                checkpoint = result.candidateCheckpoint();
            }

            assertThat(submittedByRun).as(sourceType).containsExactly(
                    List.of("known-a"),
                    List.of("known-b"),
                    List.of(),
                    List.of("new-after-known"),
                    List.of(),
                    List.of());
            assertThat(codec.decodeCheckpoint(checkpoint).resumeAfter()).isNull();
            assertThat(codec.decodeCheckpoint(checkpoint).frontier()).containsExactly(
                    codec.identityHash("known-a"),
                    codec.identityHash("new-after-known"),
                    codec.identityHash("known-b"));
        }
    }

    @Test
    @DisplayName("收藏作品不会因首个大收藏夹命中旧锚点而漏掉后续收藏夹新作品")
    void favoriteWorksScansPastKnownItemsIntoLaterFolder() throws Exception {
        List<DouyinWork> knownWorks = java.util.stream.IntStream.range(0, 300)
                .mapToObj(index -> favoriteWork("known-" + index, "folder-a"))
                .toList();
        RecordingClient client = new RecordingClient(knownWorks);
        DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
        DouyinScheduledSourceExecutor executor = new DouyinScheduledSourceExecutor(
                DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS,
                new DouyinScheduledSourceSupport(client, codec));
        ScheduledTaskDefinition task = executor.prepare(draft(
                DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS,
                definitions(0).get(DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS)));

        CapturingContext initialContext = new CapturingContext(task, null, Set.of());
        ScheduledCheckpoint checkpoint = executor.discover(initialContext).candidateCheckpoint();

        client.pageCall = 0;
        client.seenCursors.clear();
        client.pageSequence = List.of(
                new DouyinListing(
                        knownWorks, knownWorks.size() + 1, 1, 20, false,
                        "首个收藏夹", "owner", "作者", "fw1.next", true),
                new DouyinListing(
                        List.of(favoriteWork("new-in-later-folder", "folder-b")),
                        knownWorks.size() + 1, 2, 20, true,
                        "后续收藏夹", "owner", "作者", "", false));
        CapturingContext nextContext = new CapturingContext(task, checkpoint, Set.of());

        executor.discover(nextContext);

        assertThat(client.seenCursors).containsExactly("0", "fw1.next");
        assertThat(nextContext.works).extracting(work -> work.key().id())
                .containsExactly("new-in-later-folder");
    }

    @Test
    @DisplayName("同一收藏夹首个旧锚点之后插入的新作品仍会发现且不会提前写入检查点")
    void favoriteWorksDoesNotCompleteFolderAtFirstKnownAnchor() throws Exception {
        RecordingClient client = new RecordingClient(List.of(
                favoriteWork("old-a", "folder-a"),
                favoriteWork("old-b", "folder-a"),
                favoriteWork("old-c", "folder-a")));
        DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
        DouyinScheduledSourceExecutor executor = new DouyinScheduledSourceExecutor(
                DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS,
                new DouyinScheduledSourceSupport(client, codec));
        ScheduledTaskDefinition task = executor.prepare(draft(
                DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS,
                definitions(0).get(DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS)));

        CapturingContext initial = new CapturingContext(task, null, Set.of());
        ScheduledCheckpoint checkpoint = executor.discover(initial).candidateCheckpoint();
        client.works = List.of(
                favoriteWork("old-a", "folder-a"),
                favoriteWork("new-x", "folder-a"),
                favoriteWork("old-b", "folder-a"),
                favoriteWork("old-c", "folder-a"));
        CapturingContext changed = new CapturingContext(task, checkpoint, Set.of());

        ScheduledCheckpoint changedCheckpoint = executor.discover(changed).candidateCheckpoint();

        assertThat(changed.works).extracting(work -> work.key().id())
                .containsExactly("new-x");
        assertThat(codec.decodeCheckpoint(changedCheckpoint).frontier())
                .contains(codec.identityHash("new-x"));
    }

    @Test
    @DisplayName("单个收藏夹包含三百件作品时重启后的空闲轮不会重复发现尾部旧作品")
    void favoriteWorksLargeFolderIsNotRediscoveredAfterRestart() throws Exception {
        List<DouyinWork> works = java.util.stream.IntStream.range(0, 300)
                .mapToObj(index -> favoriteWork("favorite-bulk-" + index, "folder-a"))
                .toList();
        RecordingClient client = new RecordingClient(works);
        DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
        DouyinScheduledSourceExecutor executor = new DouyinScheduledSourceExecutor(
                DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS,
                new DouyinScheduledSourceSupport(client, codec));
        ScheduledTaskDefinition task = executor.prepare(draft(
                DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS,
                definitions(0).get(DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS)));

        CapturingContext initial = new CapturingContext(task, null, Set.of());
        ScheduledCheckpoint checkpoint = executor.discover(initial).candidateCheckpoint();
        CapturingContext restarted = new CapturingContext(task, checkpoint, Set.of());

        executor.discover(restarted);

        assertThat(initial.works).hasSize(works.size());
        assertThat(restarted.works).isEmpty();
    }

    @Test
    @DisplayName("续传锚点被移除时保留已观察前沿且后续轮不重复发现旧作品")
    void missingResumeAnchorPreservesObservedFrontier() throws Exception {
        RecordingClient client = new RecordingClient(List.of(
                work("resume-a"), work("resume-b"), work("resume-c"), work("resume-d")));
        DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
        DouyinScheduledSourceExecutor executor = new DouyinScheduledSourceExecutor(
                DouyinSourceTypes.USER, new DouyinScheduledSourceSupport(client, codec));
        ScheduledTaskDefinition task = executor.prepare(draft(
                DouyinSourceTypes.USER, definitions(2).get(DouyinSourceTypes.USER)));

        CapturingContext initial = new CapturingContext(task, null, Set.of());
        ScheduledCheckpoint checkpoint = executor.discover(initial).candidateCheckpoint();
        client.works = List.of(work("resume-a"), work("resume-c"), work("resume-d"));
        CapturingContext anchorMissing = new CapturingContext(task, checkpoint, Set.of());
        checkpoint = executor.discover(anchorMissing).candidateCheckpoint();
        CapturingContext resumed = new CapturingContext(task, checkpoint, Set.of());

        executor.discover(resumed);

        assertThat(initial.works).extracting(work -> work.key().id())
                .containsExactly("resume-a", "resume-b");
        assertThat(anchorMissing.works).isEmpty();
        assertThat(resumed.works).extracting(work -> work.key().id())
                .containsExactly("resume-c", "resume-d");
    }

    @Test
    @DisplayName("普通来源续传锚点被移除后会越过二十个旧项恢复未处理尾部")
    void missingResumeAnchorRecoversPastKnownStreak() throws Exception {
        List<DouyinWork> initialWorks = java.util.stream.IntStream.range(0, 23)
                .mapToObj(index -> work("known-prefix-" + index))
                .toList();
        RecordingClient client = new RecordingClient(initialWorks);
        DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
        DouyinScheduledSourceExecutor executor = new DouyinScheduledSourceExecutor(
                DouyinSourceTypes.USER, new DouyinScheduledSourceSupport(client, codec));
        ScheduledTaskDefinition task = executor.prepare(draft(
                DouyinSourceTypes.USER, definitions(21).get(DouyinSourceTypes.USER)));

        CapturingContext initial = new CapturingContext(task, null, Set.of());
        ScheduledCheckpoint checkpoint = executor.discover(initial).candidateCheckpoint();
        client.works = java.util.stream.IntStream.range(0, 23)
                .filter(index -> index != 20)
                .mapToObj(index -> work("known-prefix-" + index))
                .toList();
        CapturingContext anchorMissing = new CapturingContext(task, checkpoint, Set.of());
        checkpoint = executor.discover(anchorMissing).candidateCheckpoint();
        CapturingContext recovered = new CapturingContext(task, checkpoint, Set.of());

        executor.discover(recovered);

        assertThat(initial.works).hasSize(21);
        assertThat(anchorMissing.works).isEmpty();
        assertThat(recovered.works).extracting(work -> work.key().id())
                .containsExactly("known-prefix-21", "known-prefix-22");
    }

    @Test
    @DisplayName("收藏作品续传锚点被移除后不会把同收藏夹未处理尾部标成已完成")
    void favoriteWorksMissingResumeAnchorRecoversFolderTail() throws Exception {
        RecordingClient client = new RecordingClient(List.of(
                favoriteWork("favorite-resume-a", "folder-a"),
                favoriteWork("favorite-resume-b", "folder-a"),
                favoriteWork("favorite-resume-c", "folder-a"),
                favoriteWork("favorite-resume-d", "folder-a")));
        DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
        DouyinScheduledSourceExecutor executor = new DouyinScheduledSourceExecutor(
                DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS,
                new DouyinScheduledSourceSupport(client, codec));
        ScheduledTaskDefinition task = executor.prepare(draft(
                DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS,
                definitions(2).get(DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS)));

        CapturingContext initial = new CapturingContext(task, null, Set.of());
        ScheduledCheckpoint checkpoint = executor.discover(initial).candidateCheckpoint();
        client.works = List.of(
                favoriteWork("favorite-resume-a", "folder-a"),
                favoriteWork("favorite-resume-c", "folder-a"),
                favoriteWork("favorite-resume-d", "folder-a"));
        CapturingContext anchorMissing = new CapturingContext(task, checkpoint, Set.of());
        checkpoint = executor.discover(anchorMissing).candidateCheckpoint();
        CapturingContext recovered = new CapturingContext(task, checkpoint, Set.of());

        executor.discover(recovered);

        assertThat(initial.works).extracting(work -> work.key().id())
                .containsExactly("favorite-resume-a", "favorite-resume-b");
        assertThat(anchorMissing.works).isEmpty();
        assertThat(recovered.works).extracting(work -> work.key().id())
                .containsExactly("favorite-resume-c", "favorite-resume-d");
    }

    @Test
    @DisplayName("超过八十五个收藏夹时每个收藏夹仍至少保留一个重启锚点")
    void favoriteWorksFrontierAllocatesAnchorsAcrossFolders() throws Exception {
        List<DouyinWork> works = new ArrayList<>();
        for (int folder = 0; folder < 87; folder++) {
            for (int item = 0; item < FAVORITE_FOLDER_ITEM_COUNT; item++) {
                works.add(favoriteWork(
                        "many-folders-" + folder + "-" + item,
                        "folder-" + folder));
            }
        }
        RecordingClient client = new RecordingClient(works);
        DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
        DouyinScheduledSourceExecutor executor = new DouyinScheduledSourceExecutor(
                DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS,
                new DouyinScheduledSourceSupport(client, codec));
        ScheduledTaskDefinition task = executor.prepare(draft(
                DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS,
                definitions(0).get(DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS)));

        CapturingContext initial = new CapturingContext(task, null, Set.of());
        ScheduledCheckpoint checkpoint = executor.discover(initial).candidateCheckpoint();
        CapturingContext restarted = new CapturingContext(task, checkpoint, Set.of());

        executor.discover(restarted);

        assertThat(initial.works).hasSize(works.size());
        assertThat(codec.decodeCheckpoint(checkpoint).frontier()).hasSize(works.size());
        assertThat(restarted.works).isEmpty();
    }

    @Test
    @DisplayName("收藏作品超过精确检查点容量时安全失败而不静默截断")
    void favoriteWorksFailsSafelyBeyondExactCheckpointCapacity() throws Exception {
        List<DouyinWork> works = java.util.stream.IntStream.rangeClosed(
                        0, DouyinScheduleCodec.MAX_FRONTIER_IDENTITIES)
                .mapToObj(index -> favoriteWork("capacity-" + index, "folder-a"))
                .toList();
        RecordingClient client = new RecordingClient(works);
        DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
        DouyinScheduledSourceExecutor executor = new DouyinScheduledSourceExecutor(
                DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS,
                new DouyinScheduledSourceSupport(client, codec));
        ScheduledTaskDefinition task = executor.prepare(draft(
                DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS,
                definitions(0).get(DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS)));
        CapturingContext context = new CapturingContext(task, null, Set.of());

        assertThatThrownBy(() -> executor.discover(context))
                .isInstanceOfSatisfying(ScheduledExecutionException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(ScheduledFailure.Category.PAYLOAD_UNSUPPORTED);
                    assertThat(failure.code())
                            .isEqualTo("douyin.schedule.checkpoint-capacity-exceeded");
                });
    }

    @Test
    @DisplayName("二百五十七个收藏夹均能进入检查点且后续空闲轮不重复发现")
    void favoriteWorksRetainsMoreThanLegacyFrontierFolderCount() throws Exception {
        List<DouyinWork> works = java.util.stream.IntStream.range(0, 257)
                .mapToObj(index -> favoriteWork(
                        "folder-work-" + index, "folder-" + index))
                .toList();
        RecordingClient client = new RecordingClient(works);
        DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
        DouyinScheduledSourceExecutor executor = new DouyinScheduledSourceExecutor(
                DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS,
                new DouyinScheduledSourceSupport(client, codec));
        ScheduledTaskDefinition task = executor.prepare(draft(
                DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS,
                definitions(0).get(DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS)));

        CapturingContext initial = new CapturingContext(task, null, Set.of());
        ScheduledCheckpoint checkpoint = executor.discover(initial).candidateCheckpoint();
        CapturingContext second = new CapturingContext(task, checkpoint, Set.of());
        checkpoint = executor.discover(second).candidateCheckpoint();
        CapturingContext third = new CapturingContext(task, checkpoint, Set.of());

        executor.discover(third);

        assertThat(initial.works).hasSize(257);
        assertThat(codec.decodeCheckpoint(checkpoint).frontier()).hasSize(257);
        assertThat(second.works).isEmpty();
        assertThat(third.works).isEmpty();
    }

    @Test
    @DisplayName("多轮续传按当前源顺序合并并保留全部已处理作品身份")
    void continuationMergesAllOrderedFrontierIdentities() throws Exception {
        List<DouyinWork> works = java.util.stream.IntStream.range(0, 260)
                .mapToObj(index -> work("bulk-" + index))
                .toList();
        RecordingClient client = new RecordingClient(works);
        DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
        DouyinScheduledSourceExecutor executor = new DouyinScheduledSourceExecutor(
                DouyinSourceTypes.USER, new DouyinScheduledSourceSupport(client, codec));
        ScheduledTaskDefinition task = executor.prepare(draft(
                DouyinSourceTypes.USER, definitions(100).get(DouyinSourceTypes.USER)));
        ScheduledCheckpoint checkpoint = null;
        List<String> submitted = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();

        for (int run = 0; run < 4; run++) {
            CapturingContext context = new CapturingContext(task, checkpoint, Set.of());
            ScheduledDiscoveryResult result = executor.discover(context);
            List<String> ids = context.works.stream().map(work -> work.key().id()).toList();
            counts.add(ids.size());
            submitted.addAll(ids);
            checkpoint = result.candidateCheckpoint();
        }

        assertThat(counts).containsExactly(100, 100, 60, 0);
        assertThat(submitted).containsExactlyElementsOf(
                works.stream().map(DouyinWork::id).toList());
        DouyinScheduleCodec.CheckpointState state = codec.decodeCheckpoint(checkpoint);
        assertThat(state.resumeAfter()).isNull();
        assertThat(state.frontier()).hasSize(works.size());
        assertThat(state.frontier()).containsExactlyElementsOf(
                works.stream()
                        .map(DouyinWork::id)
                        .map(id -> {
                            try {
                                return codec.identityHash(id);
                            } catch (ScheduledExecutionException failure) {
                                throw new AssertionError(failure);
                            }
                        })
                        .toList());
    }

    @Test
    @DisplayName("耐久 pending 由宿主重放时来源不重复提交且仍推进候选锚点")
    void pendingWorkIsNotSubmittedTwice() throws Exception {
        RecordingClient client = new RecordingClient(List.of(work("73510001"), work("73510002")));
        DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
        DouyinScheduledSourceExecutor executor = new DouyinScheduledSourceExecutor(
                DouyinSourceTypes.USER, new DouyinScheduledSourceSupport(client, codec));
        ScheduledTaskDefinition task = executor.prepare(draft(
                DouyinSourceTypes.USER, definitions(1).get(DouyinSourceTypes.USER)));
        CapturingContext context = new CapturingContext(
                task, null, Set.of(new ScheduledWorkKey("douyin", "73510001")));

        ScheduledDiscoveryResult result = executor.discover(context);

        assertThat(context.works).extracting(work -> work.key().id())
                .containsExactly("73510002");
        assertThat(codec.decodeCheckpoint(result.candidateCheckpoint()).resumeAfter())
                .isEqualTo(codec.identityHash("73510002"));
    }

    @Test
    @DisplayName("重复游标会以可重试失败终止而不会无限翻页")
    void duplicateCursorFailsSafely() throws Exception {
        RecordingClient client = new RecordingClient(List.of(work("73510001")));
        client.stalled = true;
        DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
        DouyinScheduledSourceExecutor executor = new DouyinScheduledSourceExecutor(
                DouyinSourceTypes.USER, new DouyinScheduledSourceSupport(client, codec));
        ScheduledTaskDefinition task = executor.prepare(draft(
                DouyinSourceTypes.USER, definitions(0).get(DouyinSourceTypes.USER)));

        assertThatThrownBy(() -> executor.discover(
                new CapturingContext(task, null, Set.of())))
                .isInstanceOf(ScheduledExecutionException.class)
                .extracting(failure -> ((ScheduledExecutionException) failure).code())
                .isEqualTo("douyin.schedule.pagination-stalled");
    }

    @Test
    @DisplayName("八类来源均从零游标开始并严格使用对应客户端的服务端下一游标")
    void followsTwoPageCursorProgressionForAllSources() throws Exception {
        Map<String, String> expectedLoaders = Map.of(
                DouyinSourceTypes.USER, "user:MS4w.LjAB-user",
                DouyinSourceTypes.SEARCH, "search:风景",
                DouyinSourceTypes.COLLECTION, "series:collection-1",
                DouyinSourceTypes.MUSIC, "music:music-1",
                DouyinSourceTypes.ACCOUNT_OWN_WORKS, "account:OWN_WORKS",
                DouyinSourceTypes.ACCOUNT_LIKED_WORKS, "account:LIKED_WORKS",
                DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS, "account:FAVORITE_WORKS",
                DouyinSourceTypes.ACCOUNT_FAVORITE_COLLECTION,
                "series:favorite-collection-1");

        for (Map.Entry<String, String> entry : definitions(0).entrySet()) {
            RecordingClient client = new RecordingClient(List.of());
            client.pageSequence = List.of(
                    new DouyinListing(
                            List.of(work("73510001")), 2, 1, 20, false,
                            "来源", "owner", "作者", "cursor-1", true),
                    new DouyinListing(
                            List.of(work("73510002")), 2, 2, 20, true,
                            "来源", "owner", "作者", "", false));
            DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
            DouyinScheduledSourceExecutor executor = new DouyinScheduledSourceExecutor(
                    entry.getKey(), new DouyinScheduledSourceSupport(client, codec));
            ScheduledTaskDefinition task = executor.prepare(draft(entry.getKey(), entry.getValue()));
            CapturingContext context = new CapturingContext(task, null, Set.of());

            executor.discover(context);

            assertThat(client.seenCursors).as(entry.getKey())
                    .containsExactly("0", "cursor-1");
            assertThat(client.seenLoaders).as(entry.getKey())
                    .containsExactly(expectedLoaders.get(entry.getKey()),
                            expectedLoaders.get(entry.getKey()));
            assertThat(context.works).extracting(work -> work.key().id())
                    .containsExactly("73510001", "73510002");
            assertThat(client.accountResolveCount).isEqualTo(
                    entry.getKey().startsWith("douyin.account.") ? 1 : 0);
        }
    }

    @Test
    @DisplayName("分页与作品提交期间会协作式响应取消")
    void cancellationStopsDiscoveryCooperatively() throws Exception {
        RecordingClient client = new RecordingClient(List.of(work("73510001")));
        DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
        DouyinScheduledSourceExecutor executor = new DouyinScheduledSourceExecutor(
                DouyinSourceTypes.USER, new DouyinScheduledSourceSupport(client, codec));
        ScheduledTaskDefinition task = executor.prepare(draft(
                DouyinSourceTypes.USER, definitions(0).get(DouyinSourceTypes.USER)));
        AtomicInteger checks = new AtomicInteger();
        CapturingContext context = new CapturingContext(
                task, null, Set.of(), () -> checks.incrementAndGet() >= 3);

        assertThatThrownBy(() -> executor.discover(context))
                .isInstanceOf(ScheduledExecutionException.class)
                .extracting(failure -> ((ScheduledExecutionException) failure).code())
                .isEqualTo("schedule.cancelled");
        assertThat(context.works).isEmpty();
    }

    private static Map<String, String> definitions(int fetchLimit) {
        Map<String, String> definitions = new LinkedHashMap<>();
        definitions.put(DouyinSourceTypes.USER, json("userId", "MS4w.LjAB-user", fetchLimit));
        definitions.put(DouyinSourceTypes.SEARCH, json("keyword", "风景", fetchLimit));
        definitions.put(DouyinSourceTypes.COLLECTION, json("collectionId", "collection-1", fetchLimit));
        definitions.put(DouyinSourceTypes.MUSIC, json("musicId", "music-1", fetchLimit));
        definitions.put(DouyinSourceTypes.ACCOUNT_OWN_WORKS, emptySource(fetchLimit));
        definitions.put(DouyinSourceTypes.ACCOUNT_LIKED_WORKS, emptySource(fetchLimit));
        definitions.put(DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS, emptySource(fetchLimit));
        definitions.put(DouyinSourceTypes.ACCOUNT_FAVORITE_COLLECTION,
                json("collectionId", "favorite-collection-1", fetchLimit));
        return definitions;
    }

    private static String json(String field, String value, int fetchLimit) {
        return "{\"source\":{\"" + field + "\":\"" + value
                + "\"},\"fetchLimit\":" + fetchLimit + "}";
    }

    private static String emptySource(int fetchLimit) {
        return "{\"source\":{},\"fetchLimit\":" + fetchLimit + "}";
    }

    private static ScheduledTaskDraft draft(String sourceType, String json) {
        return new ScheduledTaskDraft(
                1L, sourceType, DouyinScheduleCodec.DEFINITION_SCHEMA,
                DouyinScheduleCodec.DEFINITION_VERSION, json,
                ScheduledTaskPresentation.empty());
    }

    private static DouyinWork work(String id) {
        return new DouyinWork(
                id, "作品 " + id, "author", "作者",
                "https://www.douyin.com/video/" + id,
                null, URI.create("https://media.example/" + id + ".mp4"));
    }

    private static DouyinWork favoriteWork(String id, String folderId) {
        DouyinWork base = work(id);
        return new DouyinWork(
                base.id(), base.title(), base.description(), base.itemTitle(), base.caption(),
                base.authorId(), base.authorName(), base.pageUrl(), base.thumbnailUrl(),
                base.mediaUrl(), base.media(), base.kind(), base.publishTimeEpochSeconds(),
                folderId, "收藏夹 " + folderId);
    }

    private static final class CapturingContext implements ScheduledSourceContext {
        private final ScheduledTaskDefinition task;
        private final ScheduledCheckpoint checkpoint;
        private final Set<ScheduledWorkKey> pending;
        private final List<ScheduledWork> works = new ArrayList<>();
        private final ScheduledCredentialHandle credential = new TestCredentialHandle();
        private final ScheduledCancellation cancellation;

        private CapturingContext(
                ScheduledTaskDefinition task,
                ScheduledCheckpoint checkpoint,
                Set<ScheduledWorkKey> pending) {
            this(task, checkpoint, pending, () -> false);
        }

        private CapturingContext(
                ScheduledTaskDefinition task,
                ScheduledCheckpoint checkpoint,
                Set<ScheduledWorkKey> pending,
                ScheduledCancellation cancellation) {
            this.task = task;
            this.checkpoint = checkpoint;
            this.pending = pending;
            this.cancellation = cancellation;
        }

        @Override
        public ScheduledCheckpoint checkpoint() {
            return checkpoint;
        }

        @Override
        public ScheduledWorkSink workSink() {
            return works::add;
        }

        @Override
        public boolean isPending(ScheduledWorkKey key) {
            return pending.contains(key);
        }

        @Override
        public ScheduledTaskDefinition task() {
            return task;
        }

        @Override
        public ScheduledNetworkRoute route() {
            return ScheduledNetworkRoute.direct();
        }

        @Override
        public ScheduledCredentialHandle credential() {
            return credential;
        }

        @Override
        public ScheduledCancellation cancellation() {
            return cancellation;
        }
    }

    private static final class TestCredentialHandle implements ScheduledCredentialHandle {

        @Override
        public boolean isPresent() {
            return true;
        }

        @Override
        public String reference() {
            return "credential-ref";
        }

        @Override
        public String accountKey() {
            return "account-1";
        }

        @Override
        public char[] copySecret() {
            return VALID_COOKIE.toCharArray();
        }

        @Override
        public void close() {
        }
    }

    private static final class RecordingClient implements DouyinClient {
        private List<DouyinWork> works;
        private boolean routeOverrideObserved;
        private String lastCookie;
        private boolean stalled;
        private List<DouyinListing> pageSequence;
        private int pageCall;
        private final List<String> seenCursors = new ArrayList<>();
        private final List<String> seenLoaders = new ArrayList<>();
        private int accountResolveCount;

        private RecordingClient(List<DouyinWork> works) {
            this.works = works;
        }

        @Override
        public DouyinCanonicalDownload resolveDownload(String input, String cookie) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DouyinParsedInput resolveInput(String input, String cookie) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DouyinWork resolvePublicWork(String input, String cookie) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DouyinListing listUserWorks(String userId, int offset, int limit, String cookie) {
            return listing(cookie, "0");
        }

        @Override
        public DouyinListing listSeriesWorks(String seriesId, int page, int pageSize, String cookie) {
            return listing(cookie, Integer.toString(page));
        }

        @Override
        public DouyinListing searchPublic(String word, int page, int pageSize, String cookie) {
            return listing(cookie, Integer.toString(page));
        }

        @Override
        public DouyinListing listUserWorksPage(String userId, String cursor, int limit, String cookie) {
            seenLoaders.add("user:" + userId);
            return listing(cookie, cursor);
        }

        @Override
        public DouyinListing searchWorksPage(String word, String cursor, int limit, String cookie) {
            seenLoaders.add("search:" + word);
            return listing(cookie, cursor);
        }

        @Override
        public DouyinListing listSeriesWorksPage(String seriesId, String cursor, int limit, String cookie) {
            seenLoaders.add("series:" + seriesId);
            return listing(cookie, cursor);
        }

        @Override
        public DouyinListing listMusicWorksPage(String musicId, String cursor, int limit, String cookie) {
            seenLoaders.add("music:" + musicId);
            return listing(cookie, cursor);
        }

        @Override
        public DouyinAccount resolveAccount(String cookie) {
            observe(cookie);
            accountResolveCount++;
            return new DouyinAccount("account-1", "sec-account", "账号", "account");
        }

        @Override
        public DouyinListing listAccountWorksPage(
                DouyinAccount account,
                DouyinAccountSource source,
                String cursor,
                int limit,
                String cookie) {
            seenLoaders.add("account:" + source.name());
            return listing(cookie, cursor);
        }

        private DouyinListing listing(String cookie, String cursor) {
            observe(cookie);
            seenCursors.add(cursor == null || cursor.isBlank() ? "0" : cursor.trim());
            if (pageSequence != null && pageCall < pageSequence.size()) {
                return pageSequence.get(pageCall++);
            }
            return new DouyinListing(
                    works, works.size(), 1, 20, !stalled,
                    "来源", "owner", "作者", stalled ? "0" : "", stalled);
        }

        private void observe(String cookie) {
            routeOverrideObserved |= OutboundProxyOverride.isActive();
            lastCookie = cookie;
        }
    }
}
