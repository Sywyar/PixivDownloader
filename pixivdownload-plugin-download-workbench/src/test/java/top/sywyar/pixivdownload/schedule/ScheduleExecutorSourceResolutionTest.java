package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.config.OutboundProxyEndpoint;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunCompletion;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunToken;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkKind;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.download.schedule.work.ScheduledIllustWorkRunner;
import top.sywyar.pixivdownload.download.ArtworkDownloader;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.core.notification.NotificationService;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;
import top.sywyar.pixivdownload.setup.UserDisplayNameProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ScheduleExecutor 的来源解析门测试。覆盖两件事：
 * <ol>
 *   <li><b>来源不可解析</b>：任务 {@code type} 在 {@link ScheduleCapabilityRegistry} 解析不到 provider
 *       （模拟来源插件被禁 / 卸载、或类型已移除）时，调度器在 {@code runTask} 顶部即标记
 *       {@link ScheduleSuspendReason#SOURCE_UNAVAILABLE} 干净挂起，<b>绝不</b>读 cookie / 探站内信 /
 *       发现 / 派发；该终态在来源 owner 被禁用、卸载或重载撤回时真实可达。</li>
 *   <li><b>代理/Cookie 作用域</b>：加解析门后，既有发现 + 派发仍全程在任务级
 *       {@link OutboundProxyOverride} 覆盖内执行，运行结束清除覆盖（不污染后续无关请求）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleExecutor 来源解析门")
class ScheduleExecutorSourceResolutionTest {

    @Mock
    private ScheduledTaskStore store;
    @Mock
    private PixivFetchService pixivFetchService;
    @Mock
    private PixivDatabase pixivDatabase;
    @Mock
    private ArtworkDownloader artworkDownloader;
    @Mock
    private NovelMetadataRepository novelMetadataRepository;
    @Mock
    private OveruseWarningService overuseWarningService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AppMessages appMessages;
    @Mock
    private UserDisplayNameProvider userDisplayNameProvider;
    @Mock
    private DownloadSettings downloadSettings;
    @Mock
    private WorkMetaCaptureService workMetaCaptureService;

    private ScheduleRunState runState;

    /** 同步执行器：把提交的下载就地跑完，便于在调用线程上观察代理覆盖。 */
    private static final TaskExecutor SYNC_EXECUTOR = Runnable::run;

    @BeforeEach
    void setUp() {
        runState = new ScheduleRunState();
        lenient().when(downloadSettings.getMaxConcurrent()).thenReturn(10);
        lenient().when(downloadSettings.getNovelMaxConcurrent()).thenReturn(10);
    }

    @AfterEach
    void tearDown() {
        // 防御：即便断言失败也清掉可能残留的线程级覆盖，避免污染同线程后续测试。
        OutboundProxyOverride.clear();
    }

    /** 用统一能力注册中心构造被测执行器（同步下载池，使用宿主只读下载设置）。 */
    private ScheduleExecutor newExecutor(ScheduleCapabilityRegistry registry) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new ScheduleExecutor(store, registry, pixivFetchService, pixivDatabase,
                workMetaCaptureService, artworkDownloader, novelMetadataRepository,
                new ScheduleConfig(), runState, new ScheduleRunQueue(),
                objectMapper, new PixivSchedulePersistenceCodec(objectMapper),
                overuseWarningService, notificationService, appMessages, userDisplayNameProvider,
                downloadSettings, SYNC_EXECUTOR, SYNC_EXECUTOR);
    }

    private ScheduleCapabilityRegistry downloadWorkbenchCapabilities() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityTestFixture.publishDownloadWorkbench(
                registry, List.of(new ScheduledIllustWorkRunner(artworkDownloader)));
        return registry;
    }

    /** 来源与插画执行器同 owner 发布；无 novel 执行器，用于验证缺执行器行为。 */
    private ScheduleCapabilityRegistry illustOnlyCapabilities() {
        return downloadWorkbenchCapabilities();
    }

    private static ScheduledTask userNewTask(boolean credentialBound, String proxy) {
        return canonicalTask(
                1L, "画师计划", "user-new", DownloadWorkbenchPlugin.ID,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"}}",
                proxy, credentialBound);
    }

    private static ScheduledTask userNewNovelTask(boolean credentialBound) {
        return canonicalTask(
                2L, "画师小说计划", "user-new", DownloadWorkbenchPlugin.ID,
                "{\"kind\":\"novel\",\"source\":{\"userId\":\"100\"}}",
                null, credentialBound);
    }

    /** 珍藏集任务（插画 + 小说混合来源，kind=mixed）：需 illust + novel 两类执行器都在场。 */
    private static ScheduledTask collectionTask(boolean credentialBound) {
        return canonicalTask(
                3L, "珍藏集计划", "collection", DownloadWorkbenchPlugin.ID,
                "{\"kind\":\"mixed\",\"source\":{\"collectionId\":\"7777\"}}",
                null, credentialBound);
    }

    private static ScheduledTask novelSeriesMergeTask() {
        return canonicalTask(
                4L, "小说系列合订计划", "series", DownloadWorkbenchPlugin.ID,
                "{\"kind\":\"novel\",\"source\":{\"seriesId\":\"777\"},"
                        + "\"download\":{\"novelMerge\":true,\"novelMergeFormat\":\"epub\"}}",
                null, false);
    }

    private static ScheduledTask canonicalTask(
            long id,
            String name,
            String sourceType,
            String sourceOwnerPluginId,
            String definitionJson,
            String proxy,
            boolean credentialBound) {
        return new ScheduledTask(
                id, name, true, sourceType, sourceOwnerPluginId,
                PixivSchedulePersistenceCodec.DEFINITION_SCHEMA,
                PixivSchedulePersistenceCodec.DEFINITION_VERSION,
                definitionJson, "{}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                proxy, 0L, null,
                null, null, null,
                ScheduledTask.CURRENT_STORAGE_VERSION,
                null, null, ScheduleLastOutcome.NEVER,
                null, null, null, null, null, 0L,
                credentialBound ? DownloadWorkbenchPlugin.ID : null,
                credentialBound ? PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID : null,
                credentialBound ? "100" : null,
                credentialBound
                        ? "{\"schema\":\"pixiv.schedule.credential-policy-state\",\"version\":1}"
                        : null,
                credentialBound ? "scheduled-task:" + id + ":credential" : null,
                credentialBound ? 1L : null,
                0L);
    }

    private DurableRun stubDurableClaim(ScheduledTask task) {
        ScheduleRunToken queued = new ScheduleRunToken(
                "claim-" + task.id(), task.stateVersion() + 1,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED);
        ScheduleRunToken running = new ScheduleRunToken(
                queued.claimToken(), queued.stateVersion() + 1,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.RUNNING);
        when(store.tryQueueNow(eq(task.id()), eq(task.stateVersion()), anyString()))
                .thenReturn(Optional.of(queued));
        when(store.startRun(task.id(), queued)).thenReturn(Optional.of(running));
        return new DurableRun(queued, running);
    }

    private void stubNormalCompletion(ScheduledTask task, DurableRun run) {
        when(store.completeRun(eq(task.id()), eq(run.running()), any(ScheduleRunCompletion.class)))
                .thenReturn(OptionalLong.of(run.running().stateVersion() + 1));
    }

    private void stubSuspendedCompletion(
            ScheduledTask task, DurableRun run, ScheduleSuspendReason reason) {
        when(store.suspend(
                eq(task.id()), eq(run.running().stateVersion()), eq(reason), any(), any()))
                .thenReturn(OptionalLong.of(run.running().stateVersion() + 1));
        when(store.finishCancelled(
                eq(task.id()), eq(run.running()), any(ScheduleLastOutcome.class), anyLong(),
                any(), any(), any()))
                .thenReturn(OptionalLong.of(run.running().stateVersion() + 2));
    }

    private record DurableRun(ScheduleRunToken queued, ScheduleRunToken running) {
    }

    @Test
    @DisplayName("来源解析不到 provider：标记 SOURCE_UNAVAILABLE 暂停，绝不读 cookie / 探站内信 / 发现 / 派发")
    void unresolvableSourcePausesWithoutAnyWork() throws Exception {
        // 空注册中心：USER_NEW 解析不到任何 provider（模拟来源插件被禁 / 卸载、或类型已移除）。
        ScheduleCapabilityRegistry emptyRegistry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityTestFixture.publish(
                emptyRegistry,
                new top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner(
                        "download-workbench", "download-workbench", 1L),
                List.of(),
                List.of(new ScheduledIllustWorkRunner(artworkDownloader)));
        ScheduleExecutor executor = newExecutor(emptyRegistry);
        ScheduledTask task = userNewTask(true, null);
        DurableRun run = stubDurableClaim(task);
        stubSuspendedCompletion(task, run, ScheduleSuspendReason.SOURCE_UNAVAILABLE);

        // cookie-bound：若解析门未短路，轮首会读 cookie 快照并探站内信——以此反证解析门确实在它们之前生效。
        executor.runTaskAndRecord(task);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(store).suspend(
                eq(1L), eq(run.running().stateVersion()),
                eq(ScheduleSuspendReason.SOURCE_UNAVAILABLE), eq("SOURCE_UNAVAILABLE"), isNull());
        verify(store).finishCancelled(
                eq(1L), eq(run.running()), eq(ScheduleLastOutcome.ERROR), anyLong(),
                eq("SOURCE_UNAVAILABLE"), message.capture(), any());
        // 诊断原因写入未解析的 type（仅类型名、无凭证）
        assertThat(message.getValue()).contains("user-new");
        // 解析门在读 cookie / 探站内信 / 发现 / 派发之前短路：以上一概不发生
        verify(store, never()).findCredentialSecret(anyLong(), anyString(), anyString());
        verify(overuseWarningService, never()).check(any(), any(), anyLong());
        verify(pixivFetchService, never()).discoverUserArtworkIds(anyString(), any());
        verify(artworkDownloader, never()).downloadImagesBlocking(
                anyLong(), any(), anyList(), any(), any(DownloadRequest.Other.class), any(), any());
        // 不发任何挂起 / 失败通知（presentation 由真正可触发该状态的功能路径补齐）
        verify(notificationService, never()).notify(any(), any(), any());
        // 已完成 durable claim/start，但来源不可用不走正常完成，因而绝不提交 checkpoint。
        verify(store).tryQueueNow(eq(1L), eq(task.stateVersion()), anyString());
        verify(store).startRun(1L, run.queued());
        verify(store, never()).completeRun(eq(1L), eq(run.running()), any(ScheduleRunCompletion.class));
    }

    @Test
    @DisplayName("canonical 来源 owner 不匹配：按 SOURCE_UNAVAILABLE 收尾且不读取凭证、不发现、不派发")
    void mismatchedSourceOwnerPausesWithoutExecutingPluginCapabilities() throws Exception {
        ScheduleCapabilityRegistry registry = downloadWorkbenchCapabilities();
        ScheduleExecutor executor = newExecutor(registry);
        ScheduledTask task = canonicalTask(
                5L, "owner 错配计划", "user-new", "other-owner",
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"}}",
                null, true);
        DurableRun run = stubDurableClaim(task);
        stubSuspendedCompletion(task, run, ScheduleSuspendReason.SOURCE_UNAVAILABLE);

        executor.runTaskAndRecord(task);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(store).suspend(
                eq(task.id()), eq(run.running().stateVersion()),
                eq(ScheduleSuspendReason.SOURCE_UNAVAILABLE), eq("SOURCE_UNAVAILABLE"), isNull());
        verify(store).finishCancelled(
                eq(task.id()), eq(run.running()), eq(ScheduleLastOutcome.ERROR), anyLong(),
                eq("SOURCE_UNAVAILABLE"), message.capture(), any());
        assertThat(message.getValue()).contains("owner mismatch");
        verify(store, never()).findCredentialSecret(anyLong(), anyString(), anyString());
        verify(overuseWarningService, never()).check(any(), any(), anyLong());
        verify(pixivFetchService, never()).discoverUserArtworkIds(anyString(), any());
        verify(artworkDownloader, never()).downloadImagesBlocking(
                anyLong(), any(), anyList(), any(), any(DownloadRequest.Other.class), any(), any());
        verify(store, never()).completeRun(
                eq(task.id()), eq(run.running()), any(ScheduleRunCompletion.class));
    }

    @Test
    @DisplayName("宿主 owner 撤回后拒绝新的执行入口且不读取任务、cookie 或网络")
    void withdrawnHostOwnerRejectsNewInvocationBeforeAnyWork() throws Exception {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        var publication = ScheduleCapabilityTestFixture.publishDownloadWorkbench(
                registry, List.of(new ScheduledIllustWorkRunner(artworkDownloader)));
        assertThat(registry.resolveLegacySource("user-new")).isPresent();
        assertThat(ScheduleCapabilityTestFixture.withdraw(registry, publication)).isPresent();
        assertThat(registry.resolveLegacySource("user-new")).isEmpty();

        newExecutor(registry).runTaskAndRecord(userNewTask(true, null));

        verify(store, never()).tryQueueNow(anyLong(), anyLong(), anyString());
        verify(store, never()).startRun(anyLong(), any(ScheduleRunToken.class));
        verify(store, never()).completeRun(anyLong(), any(ScheduleRunToken.class), any(ScheduleRunCompletion.class));
        verify(store, never()).findCredentialSecret(anyLong(), anyString(), anyString());
        verify(overuseWarningService, never()).check(any(), any(), anyLong());
        verify(pixivFetchService, never()).discoverUserArtworkIds(anyString(), any());
        verify(artworkDownloader, never()).downloadImagesBlocking(
                anyLong(), any(), anyList(), any(), any(DownloadRequest.Other.class), any(), any());
    }

    @Test
    @DisplayName("来源可解析：加解析门后既有发现 + 派发仍全程在任务级代理覆盖内，运行结束清除覆盖")
    void resolvableSourceRunsEntirelyWithinProxyOverride() throws Exception {
        ScheduleExecutor executor = newExecutor(downloadWorkbenchCapabilities());
        String proxy = "10.1.2.3:1080";
        ScheduledTask task = userNewTask(false, proxy);
        DurableRun durableRun = stubDurableClaim(task);
        stubNormalCompletion(task, durableRun);

        AtomicReference<OutboundProxyEndpoint> proxyDuringDiscovery = new AtomicReference<>();
        AtomicReference<OutboundProxyEndpoint> proxyDuringDownload = new AtomicReference<>();

        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenAnswer(inv -> {
            proxyDuringDiscovery.set(OutboundProxyOverride.current());
            return List.of("200");
        });
        when(pixivDatabase.hasArtwork(200L)).thenReturn(false);
        when(pixivFetchService.fetchArtworkMetaCapture("200", null)).thenReturn(
                new PixivFetchService.ArtworkMetaCapture(new PixivFetchService.ArtworkMeta(
                        0, "标题", 0, false, 10L, "作者", null, null, -1, 1, List.of(), "", null), null));
        when(pixivFetchService.resolveArtworkPages("200", null)).thenReturn(
                new PixivFetchService.ArtworkPages(
                        List.of("https://i.pximg.net/img-original/img/200.jpg"), null));
        when(artworkDownloader.downloadImagesBlocking(
                eq(200L), eq("标题"), anyList(), eq("https://www.pixiv.net/artworks/200"),
                any(DownloadRequest.Other.class), isNull(), isNull()))
                .thenAnswer(inv -> {
                    proxyDuringDownload.set(OutboundProxyOverride.current());
                    return true;
                });

        executor.runTaskAndRecord(task);

        // 发现（调度主线程）在任务级代理覆盖内执行
        assertThat(proxyDuringDiscovery.get()).isNotNull();
        assertThat(proxyDuringDiscovery.get().getHostName()).isEqualTo("10.1.2.3");
        assertThat(proxyDuringDiscovery.get().getPort()).isEqualTo(1080);
        // 派发（下载池线程，本测试为同步执行器）同样在覆盖内执行
        assertThat(proxyDuringDownload.get()).isNotNull();
        assertThat(proxyDuringDownload.get().getHostName()).isEqualTo("10.1.2.3");
        assertThat(proxyDuringDownload.get().getPort()).isEqualTo(1080);
        // 运行结束：调用线程上的覆盖已被 finally 清除（不污染后续无关请求）
        assertThat(OutboundProxyOverride.current()).isNull();
        // 解析成功 → durable claim/start 后以 OK 与同一个 checkpoint 原子完成。
        ArgumentCaptor<ScheduleRunCompletion> completion =
                ArgumentCaptor.forClass(ScheduleRunCompletion.class);
        verify(store).tryQueueNow(eq(1L), eq(task.stateVersion()), anyString());
        verify(store).startRun(1L, durableRun.queued());
        verify(store).completeRun(eq(1L), eq(durableRun.running()), completion.capture());
        assertThat(completion.getValue().outcome()).isEqualTo(ScheduleLastOutcome.OK);
        assertThat(completion.getValue().checkpointSchema())
                .isEqualTo(PixivSchedulePersistenceCodec.CHECKPOINT_SCHEMA);
        assertThat(completion.getValue().checkpointVersion())
                .isEqualTo(PixivSchedulePersistenceCodec.CHECKPOINT_VERSION);
        assertThat(completion.getValue().checkpointJson())
                .contains("\"watermarkId\":\"200\"");
    }

    @Test
    @DisplayName("作品类型执行器缺席（无 novel 执行器）：小说任务标记 EXECUTOR_UNAVAILABLE 挂起，绝不读 cookie / 发现 / 下载")
    void missingNovelRunnerPausesNovelTaskWithoutAnyWork() throws Exception {
        // 来源可解析（USER_NEW 内置在场），但作品类型执行器注册中心只含插画执行器、无 novel 执行器
        //（模拟小说插件被禁 / 卸载）。
        ScheduleExecutor executor = newExecutor(illustOnlyCapabilities());
        ScheduledTask task = userNewNovelTask(true);
        DurableRun run = stubDurableClaim(task);
        stubSuspendedCompletion(task, run, ScheduleSuspendReason.EXECUTOR_UNAVAILABLE);

        executor.runTaskAndRecord(task);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(store).suspend(
                eq(2L), eq(run.running().stateVersion()),
                eq(ScheduleSuspendReason.EXECUTOR_UNAVAILABLE),
                eq("EXECUTOR_UNAVAILABLE"), isNull());
        verify(store).finishCancelled(
                eq(2L), eq(run.running()), eq(ScheduleLastOutcome.ERROR), anyLong(),
                eq("EXECUTOR_UNAVAILABLE"), message.capture(), any());
        // 诊断原因标明是「作品类型执行器」不可用（含 kind: novel）
        assertThat(message.getValue()).contains("novel");
        // 执行器解析门在读 cookie / 探站内信 / 发现 / 下载之前短路
        verify(store, never()).findCredentialSecret(anyLong(), anyString(), anyString());
        verify(overuseWarningService, never()).check(any(), any(), anyLong());
        verify(pixivFetchService, never()).discoverUserNovelIds(anyString(), any());
        verify(store, never()).completeRun(
                eq(task.id()), eq(run.running()), any(ScheduleRunCompletion.class));
    }

    @Test
    @DisplayName("novel 执行器缺席不影响插画任务：插画执行器在场时插画计划任务照常发现 + 下载")
    void illustTaskRunsWhenNovelRunnerMissing() throws Exception {
        // 仅插画执行器在场（无 novel 执行器）；插画任务不受影响。
        ScheduleExecutor executor = newExecutor(illustOnlyCapabilities());
        ScheduledTask task = userNewTask(false, null);
        DurableRun run = stubDurableClaim(task);
        stubNormalCompletion(task, run);

        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of("200"));
        when(pixivDatabase.hasArtwork(200L)).thenReturn(false);
        when(pixivFetchService.fetchArtworkMetaCapture("200", null)).thenReturn(
                new PixivFetchService.ArtworkMetaCapture(new PixivFetchService.ArtworkMeta(
                        0, "标题", 0, false, 10L, "作者", null, null, -1, 1, List.of(), "", null), null));
        when(pixivFetchService.resolveArtworkPages("200", null)).thenReturn(
                new PixivFetchService.ArtworkPages(
                        List.of("https://i.pximg.net/img-original/img/200.jpg"), null));
        when(artworkDownloader.downloadImagesBlocking(
                eq(200L), eq("标题"), anyList(), eq("https://www.pixiv.net/artworks/200"),
                any(DownloadRequest.Other.class), isNull(), isNull())).thenReturn(true);

        executor.runTaskAndRecord(task);

        // 插画执行器在场 → 正常完成（经执行器调用 ArtworkDownloader）
        verify(artworkDownloader).downloadImagesBlocking(
                eq(200L), eq("标题"), anyList(), eq("https://www.pixiv.net/artworks/200"),
                any(DownloadRequest.Other.class), isNull(), isNull());
        ArgumentCaptor<ScheduleRunCompletion> completion =
                ArgumentCaptor.forClass(ScheduleRunCompletion.class);
        verify(store).completeRun(eq(1L), eq(run.running()), completion.capture());
        assertThat(completion.getValue().outcome()).isEqualTo(ScheduleLastOutcome.OK);
        assertThat(completion.getValue().checkpointJson())
                .contains("\"watermarkId\":\"200\"");
    }

    @Test
    @DisplayName("珍藏集缺 novel 执行器：整份 COLLECTION 标记 EXECUTOR_UNAVAILABLE 挂起，绝不读 cookie / 珍藏集发现 / 下载（不退化为只跑插画）")
    void missingNovelRunnerPausesEntireCollectionTask() throws Exception {
        // 来源可解析（COLLECTION 内置在场），但作品类型执行器注册中心只含插画执行器、无 novel 执行器
        //（模拟小说插件被禁 / 卸载）。珍藏集是插画 + 小说混合来源，需 illust + novel 两类执行器都在场——
        // 当前安全策略是缺任一即把整份 collection 标不可用、绝不退化为「只跑插画」，且在读 cookie /
        // 珍藏集发现 / 下载之前就短路。
        ScheduleExecutor executor = newExecutor(illustOnlyCapabilities());
        ScheduledTask task = collectionTask(true);
        DurableRun run = stubDurableClaim(task);
        stubSuspendedCompletion(task, run, ScheduleSuspendReason.EXECUTOR_UNAVAILABLE);

        // cookie-bound：若解析门未短路，轮首会读 cookie 快照并探站内信——以此反证解析门确实在它们之前生效。
        executor.runTaskAndRecord(task);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(store).suspend(
                eq(3L), eq(run.running().stateVersion()),
                eq(ScheduleSuspendReason.EXECUTOR_UNAVAILABLE),
                eq("EXECUTOR_UNAVAILABLE"), isNull());
        verify(store).finishCancelled(
                eq(3L), eq(run.running()), eq(ScheduleLastOutcome.ERROR), anyLong(),
                eq("EXECUTOR_UNAVAILABLE"), message.capture(), any());
        // 诊断原因标明是 COLLECTION 的「作品类型执行器」缺失（含 work kind: novel）
        assertThat(message.getValue()).contains("collection").contains("novel");
        // 执行器解析门在读 cookie / 珍藏集发现 / 下载之前短路：以下一概不发生
        verify(store, never()).findCredentialSecret(anyLong(), anyString(), anyString());
        verify(overuseWarningService, never()).check(any(), any(), anyLong());
        verify(pixivFetchService, never()).discoverCollectionWorkIds(anyString(), any());
        verify(artworkDownloader, never()).downloadImagesBlocking(
                anyLong(), any(), anyList(), any(), any(DownloadRequest.Other.class), any(), any());
        // 不发任何挂起 / 失败通知（presentation 由真正可触发该状态的功能路径补齐）
        verify(notificationService, never()).notify(any(), any(), any());
        // 来源不可用绝不走正常完成，因而不会提交 checkpoint。
        verify(store, never()).completeRun(
                eq(task.id()), eq(run.running()), any(ScheduleRunCompletion.class));
    }

    @Test
    @DisplayName("小说合订阻塞期间撤回 novel owner：drain 等待旧代租约且任务最终标记来源不可用")
    void withdrawnNovelOwnerDuringSeriesMergeWaitsForLeaseAndDoesNotWriteOk() throws Exception {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityTestFixture.publishDownloadWorkbench(
                registry, List.of(new ScheduledIllustWorkRunner(artworkDownloader)));
        ScheduledTask task = novelSeriesMergeTask();
        DurableRun durableRun = stubDurableClaim(task);
        stubSuspendedCompletion(task, durableRun, ScheduleSuspendReason.SOURCE_UNAVAILABLE);

        CountDownLatch mergeEntered = new CountDownLatch(1);
        CountDownLatch allowMergeReturn = new CountDownLatch(1);
        ScheduledWorkRunner novelRunner = mock(ScheduledWorkRunner.class);
        when(novelRunner.kind()).thenReturn(ScheduledWorkKind.NOVEL);
        when(novelRunner.download(any(), any(), isNull())).thenReturn(true);
        doAnswer(invocation -> {
            mergeEntered.countDown();
            assertThat(allowMergeReturn.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(novelRunner).mergeSeries(777L, "epub");

        ScheduleCapabilityOwner novelOwner = new ScheduleCapabilityOwner("novel", "novel", 1L);
        var novelPublication = ScheduleCapabilityTestFixture.publish(
                registry, novelOwner, List.of(), List.of(novelRunner));
        assertThat(novelOwner).isNotEqualTo(ScheduleCapabilityTestFixture.DOWNLOAD_WORKBENCH_OWNER);

        when(pixivFetchService.discoverNovelSeriesIds("777", null)).thenReturn(List.of("300"));
        when(pixivFetchService.fetchNovelDetailCapture("300", null)).thenReturn(
                new PixivFetchService.NovelDetailCapture(
                        new PixivFetchService.NovelDetail(
                                300L, "章节", 0, false, 0,
                                10L, "作者", "", List.of(),
                                777L, 1L, "系列", "正文", 2, 2,
                                1, 1, true, "ja", null, 1L, Map.of()),
                        null));

        ExecutorService taskThread = Executors.newSingleThreadExecutor();
        try {
            Future<?> run = taskThread.submit(() -> newExecutor(registry).runTaskAndRecord(task));
            assertThat(mergeEntered.await(5, TimeUnit.SECONDS)).isTrue();

            ScheduleGenerationDrain drain =
                    ScheduleCapabilityTestFixture.withdraw(registry, novelPublication).orElseThrow();
            assertThat(drain.owner()).isEqualTo(novelOwner);
            assertThat(drain.activeLeaseCount()).isEqualTo(1);
            assertThat(drain.awaitDrained(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200))).isFalse();

            allowMergeReturn.countDown();
            run.get(5, TimeUnit.SECONDS);

            assertThat(drain.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            verify(store).suspend(
                    eq(4L), eq(durableRun.running().stateVersion()),
                    eq(ScheduleSuspendReason.SOURCE_UNAVAILABLE), eq("SOURCE_UNAVAILABLE"), isNull());
            verify(store).finishCancelled(
                    eq(4L), eq(durableRun.running()), eq(ScheduleLastOutcome.ERROR), anyLong(),
                    eq("SOURCE_UNAVAILABLE"), anyString(), any());
            verify(store, never()).completeRun(
                    eq(4L), eq(durableRun.running()), any(ScheduleRunCompletion.class));
        } finally {
            allowMergeReturn.countDown();
            taskThread.shutdownNow();
        }
    }
}
