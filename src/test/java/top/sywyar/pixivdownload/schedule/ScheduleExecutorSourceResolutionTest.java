package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.download.ArtworkDownloader;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.download.meta.WorkMetaCaptureService;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.notification.NotificationService;
import top.sywyar.pixivdownload.novel.download.NovelDownloader;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.plugin.PluginRegistry;
import top.sywyar.pixivdownload.plugin.ScheduledSourceRegistry;
import top.sywyar.pixivdownload.schedule.db.ScheduledTaskStore;
import top.sywyar.pixivdownload.setup.SetupService;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ScheduleExecutor 的来源解析门测试。覆盖两件事：
 * <ol>
 *   <li><b>来源不可解析</b>：任务 {@code type} 在 {@link ScheduledSourceRegistry} 解析不到 provider
 *       （模拟来源插件被禁 / 卸载、或类型已移除）时，调度器在 {@code runTask} 顶部即标记
 *       {@link ScheduledTask#STATUS_SOURCE_UNAVAILABLE} 干净挂起，<b>绝不</b>读 cookie / 探站内信 /
 *       发现 / 派发；该终态生产路径不可达（当前 7 个内置来源恒在），仅以单测钉死其正确性。</li>
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
    private NovelDownloader novelDownloader;
    @Mock
    private NovelMetadataRepository novelMetadataRepository;
    @Mock
    private NovelMergeService novelMergeService;
    @Mock
    private OveruseWarningService overuseWarningService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AppMessages appMessages;
    @Mock
    private SetupService setupService;
    @Mock
    private WorkMetaCaptureService workMetaCaptureService;

    private ScheduleRunState runState;

    /** 同步执行器：把提交的下载就地跑完，便于在调用线程上观察代理覆盖。 */
    private static final TaskExecutor SYNC_EXECUTOR = Runnable::run;

    @BeforeEach
    void setUp() {
        runState = new ScheduleRunState();
    }

    @AfterEach
    void tearDown() {
        // 防御：即便断言失败也清掉可能残留的线程级覆盖，避免污染同线程后续测试。
        OutboundProxyOverride.clear();
    }

    /** 用指定来源注册中心构造被测执行器（同步下载池，默认 DownloadConfig）。 */
    private ScheduleExecutor newExecutor(ScheduledSourceRegistry registry) {
        return new ScheduleExecutor(store, registry, pixivFetchService, pixivDatabase,
                workMetaCaptureService, artworkDownloader, novelDownloader, novelMetadataRepository,
                novelMergeService, new ScheduleConfig(), runState, new ScheduleRunQueue(),
                new ObjectMapper(), overuseWarningService, notificationService, appMessages, setupService,
                new DownloadConfig(), SYNC_EXECUTOR, SYNC_EXECUTOR);
    }

    private static ScheduledTask userNewTask(String cookieMode, String proxy) {
        return new ScheduledTask(
                1L, "画师计划", true, ScheduledTaskType.USER_NEW,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                cookieMode, proxy, 0L, null, null, null, null, null, null, null, 0, 0L);
    }

    @Test
    @DisplayName("来源解析不到 provider：标记 SOURCE_UNAVAILABLE 暂停，绝不读 cookie / 探站内信 / 发现 / 派发")
    void unresolvableSourcePausesWithoutAnyWork() throws Exception {
        // 空注册中心：USER_NEW 解析不到任何 provider（模拟来源插件被禁 / 卸载、或类型已移除）。
        ScheduledSourceRegistry emptyRegistry = new ScheduledSourceRegistry(new PluginRegistry(List.of()));
        ScheduleExecutor executor = newExecutor(emptyRegistry);

        // cookie-bound：若解析门未短路，轮首会读 cookie 快照并探站内信——以此反证解析门确实在它们之前生效。
        executor.runTaskAndRecord(userNewTask(ScheduledTask.COOKIE_BOUND, null));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(store).updateRunResult(
                eq(1L), anyLong(), eq(ScheduledTask.STATUS_SOURCE_UNAVAILABLE), message.capture(), anyLong());
        // 诊断原因写入未解析的 type（仅类型名、无凭证）
        assertThat(message.getValue()).contains("USER_NEW");
        // 解析门在读 cookie / 探站内信 / 发现 / 派发之前短路：以上一概不发生
        verify(store, never()).findCookieSnapshot(anyLong());
        verify(overuseWarningService, never()).check(any(), any(), anyLong());
        verify(pixivFetchService, never()).discoverUserArtworkIds(anyString(), any());
        verify(artworkDownloader, never()).downloadImagesBlocking(
                anyLong(), any(), anyList(), any(), any(DownloadRequest.Other.class), any(), any());
        // 不发任何挂起 / 失败通知（presentation 由真正可触发该状态的功能路径补齐）
        verify(notificationService, never()).notify(any(), any(), any());
        // 进入执行即落库开始时刻；干净挂起时 updateRunResult 一并清空（不残留中断哨兵）
        verify(store).updateRunStarted(eq(1L), anyLong());
        // 来源不可用绝不推进水位线
        verify(store, never()).updateWatermark(anyLong(), any());
    }

    @Test
    @DisplayName("来源可解析：加解析门后既有发现 + 派发仍全程在任务级代理覆盖内，运行结束清除覆盖")
    void resolvableSourceRunsEntirelyWithinProxyOverride() throws Exception {
        ScheduleExecutor executor = newExecutor(ScheduledSourceRegistry.forBuiltInPlugins());
        String proxy = "10.1.2.3:1080";

        AtomicReference<HttpHost> proxyDuringDiscovery = new AtomicReference<>();
        AtomicReference<HttpHost> proxyDuringDownload = new AtomicReference<>();

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

        executor.runTaskAndRecord(userNewTask(ScheduledTask.COOKIE_RESTRICTED, proxy));

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
        // 解析成功 → 正常完成
        verify(store).updateRunResult(eq(1L), anyLong(), eq(ScheduleExecutor.STATUS_OK), isNull(), anyLong());
    }
}
