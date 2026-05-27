package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.download.ArtworkDownloader;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.novel.NovelDownloader;
import top.sywyar.pixivdownload.novel.NovelMergeService;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.schedule.db.ScheduledTaskDatabase;
import top.sywyar.pixivdownload.schedule.db.ScheduledTaskMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleExecutor 运行计时")
class ScheduleExecutorRunTimingTest {

    @Mock
    private ScheduledTaskDatabase database;
    @Mock
    private ScheduledTaskMapper mapper;
    @Mock
    private PixivFetchService pixivFetchService;
    @Mock
    private PixivDatabase pixivDatabase;
    @Mock
    private ArtworkDownloader artworkDownloader;
    @Mock
    private NovelDownloader novelDownloader;
    @Mock
    private NovelDatabase novelDatabase;
    @Mock
    private NovelMergeService novelMergeService;

    private ScheduleExecutor executor;

    @BeforeEach
    void setUp() {
        ScheduleConfig config = new ScheduleConfig();
        config.setFetchDelayMs(0);
        executor = new ScheduleExecutor(database, pixivFetchService, pixivDatabase,
                artworkDownloader, novelDownloader, novelDatabase, novelMergeService,
                config, new ScheduleRunState(), new ScheduleRunQueue(), new ObjectMapper());
        when(database.mapper()).thenReturn(mapper);
    }

    @Test
    @DisplayName("固定周期：等待真实下载完成后才记录上次运行与下次运行时间")
    void shouldRecordNextRunAfterBlockingDownloadCompletes() throws Exception {
        ScheduledTask task = new ScheduledTask(
                1L, "画师计划", true, ScheduledTaskType.USER_NEW,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, 0L, null, null, null, null, null, 0L);
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of("123"));
        when(pixivDatabase.hasArtwork(123L)).thenReturn(false);
        when(pixivFetchService.fetchArtworkMeta("123", null)).thenReturn(
                new PixivFetchService.ArtworkMeta(
                        0, "标题", 0, false, 10L, "作者",
                        null, null, -1, 1, List.of()));
        when(pixivFetchService.resolveImageUrls("123", null)).thenReturn(
                List.of("https://i.pximg.net/img-original/img/123.jpg"));

        AtomicLong downloadCompletedAt = new AtomicLong();
        when(artworkDownloader.downloadImagesBlocking(
                eq(123L), eq("标题"), anyList(), eq("https://www.pixiv.net/artworks/123"),
                any(DownloadRequest.Other.class), isNull(), isNull()))
                .thenAnswer(inv -> {
                    Thread.sleep(20);
                    downloadCompletedAt.set(System.currentTimeMillis());
                    return true;
                });

        executor.runTaskAndRecord(task);

        ArgumentCaptor<Long> lastRun = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> nextRun = ArgumentCaptor.forClass(Long.class);
        verify(mapper).updateRunResult(eq(1L), lastRun.capture(), eq(ScheduleExecutor.STATUS_OK), isNull(), nextRun.capture());
        assertThat(lastRun.getValue()).isGreaterThanOrEqualTo(downloadCompletedAt.get());
        assertThat(nextRun.getValue()).isEqualTo(lastRun.getValue() + 60_000L);
    }

    @Test
    @DisplayName("USER_NEW：进入即落库开始时刻，完整跑完把水位线推进到本轮发现的最新作品 ID")
    void shouldWriteRunStartedAndAdvanceWatermark() throws Exception {
        ScheduledTask task = new ScheduledTask(
                1L, "画师计划", true, ScheduledTaskType.USER_NEW,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, 0L, null, null, null, null, null, 0L);
        // 发现两个作品（最新在前），均已下载 → 不取 meta、不下载，但水位线推进到本轮最新 ID 200
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of("200", "150"));
        when(pixivDatabase.hasArtwork(200L)).thenReturn(true);
        when(pixivDatabase.hasArtwork(150L)).thenReturn(true);

        executor.runTaskAndRecord(task);

        verify(mapper).updateRunStarted(eq(1L), anyLong());
        verify(mapper).updateWatermark(eq(1L), eq(200L));
        verify(mapper).updateRunResult(eq(1L), anyLong(), eq(ScheduleExecutor.STATUS_OK), isNull(), anyLong());
    }

    @Test
    @DisplayName("SERIES 不走水位线：完整跑完不更新 watermark，但仍落库开始时刻")
    void seriesDoesNotUseWatermark() throws Exception {
        ScheduledTask task = new ScheduledTask(
                2L, "系列计划", true, ScheduledTaskType.SERIES,
                "{\"kind\":\"illust\",\"source\":{\"seriesId\":\"9\"}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, 0L, null, null, null, null, null, 0L);
        when(pixivFetchService.discoverSeriesArtworkIds("9", null)).thenReturn(List.of());

        executor.runTaskAndRecord(task);

        verify(mapper, never()).updateWatermark(anyLong(), any());
        verify(mapper).updateRunStarted(eq(2L), anyLong());
    }

    @Test
    @DisplayName("SEARCH popular_d + maxPages=-1 逐页处理，直到命中已下载边界")
    void popularIncrementalSearchUsesPagedWatermarkScan() throws Exception {
        ScheduledTask task = new ScheduledTask(
                5L, "热门计划", true, ScheduledTaskType.SEARCH,
                "{\"kind\":\"illust\",\"source\":{\"word\":\"tag\",\"order\":\"popular_d\",\"mode\":\"all\",\"sMode\":\"s_tag\",\"maxPages\":-1}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, 0L, null, null, null, null, null, 0L);
        when(pixivFetchService.discoverSearchArtworkIdsPage("tag", "popular_d", "all", "s_tag", 1, null))
                .thenReturn(List.of("300", "200"));
        when(pixivDatabase.hasArtwork(300L)).thenReturn(false);
        when(pixivDatabase.hasArtwork(200L)).thenReturn(true);
        when(pixivFetchService.fetchArtworkMeta("300", null)).thenReturn(
                new PixivFetchService.ArtworkMeta(
                        0, "热门新作", 0, false, 10L, "作者",
                        null, null, -1, 1, List.of()));
        when(pixivFetchService.resolveImageUrls("300", null)).thenReturn(
                List.of("https://i.pximg.net/img-original/img/300.jpg"));
        when(artworkDownloader.downloadImagesBlocking(
                eq(300L), eq("热门新作"), anyList(), eq("https://www.pixiv.net/artworks/300"),
                any(DownloadRequest.Other.class), isNull(), isNull()))
                .thenReturn(true);

        executor.runTaskAndRecord(task);

        verify(pixivFetchService).discoverSearchArtworkIdsPage("tag", "popular_d", "all", "s_tag", 1, null);
        verify(pixivFetchService, never()).discoverSearchArtworkIds(
                eq("tag"), eq("popular_d"), eq("all"), eq("s_tag"), eq(-1), isNull());
        verify(mapper, never()).updateWatermark(anyLong(), any());
        verify(artworkDownloader).downloadImagesBlocking(
                eq(300L), eq("热门新作"), anyList(), eq("https://www.pixiv.net/artworks/300"),
                any(DownloadRequest.Other.class), isNull(), isNull());
    }

    @Test
    @DisplayName("水位线扫描中单作品失败时不推进 watermark，留给下一轮重试补齐")
    void shouldNotAdvanceWatermarkWhenSingleWorkFails() throws Exception {
        ScheduledTask task = new ScheduledTask(
                6L, "失败计划", true, ScheduledTaskType.USER_NEW,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, 0L, null, null, null, null, null, 0L);
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of("200"));
        when(pixivDatabase.hasArtwork(200L)).thenReturn(false);
        when(pixivFetchService.fetchArtworkMeta("200", null))
                .thenThrow(new IllegalStateException("temporary"));

        executor.runTaskAndRecord(task);

        verify(mapper, never()).updateWatermark(anyLong(), any());
        verify(mapper).updateRunResult(eq(6L), anyLong(), eq(ScheduleExecutor.STATUS_OK), isNull(), anyLong());
    }

    @Test
    @DisplayName("失败原因：写入 last_message 前脱敏 Pixiv Cookie")
    void shouldSanitizeCookieBeforePersistingFailureMessage() throws Exception {
        ScheduledTask task = new ScheduledTask(
                3L, "脱敏计划", true, ScheduledTaskType.USER_NEW,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, 0L, null, null, null, null, null, 0L);
        when(pixivFetchService.discoverUserArtworkIds("100", null))
                .thenThrow(new IllegalStateException("request failed Cookie: PHPSESSID=secret; foo=bar"));

        executor.runTaskAndRecord(task);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateRunResult(
                eq(3L), anyLong(), eq(ScheduleExecutor.STATUS_ERROR), message.capture(), anyLong());
        assertThat(message.getValue()).contains("[redacted]");
        assertThat(message.getValue()).doesNotContain("secret").doesNotContain("foo=bar");
    }

    @Test
    @DisplayName("失败原因：无 Cookie 前缀时也脱敏整段 Cookie 对")
    void shouldSanitizeCookiePairsWithoutCookieHeader() throws Exception {
        ScheduledTask task = new ScheduledTask(
                4L, "脱敏计划", true, ScheduledTaskType.USER_NEW,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, 0L, null, null, null, null, null, 0L);
        when(pixivFetchService.discoverUserArtworkIds("100", null))
                .thenThrow(new IllegalStateException("bad auth PHPSESSID=secret; foo=bar"));

        executor.runTaskAndRecord(task);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateRunResult(
                eq(4L), anyLong(), eq(ScheduleExecutor.STATUS_ERROR), message.capture(), anyLong());
        assertThat(message.getValue()).contains("PHPSESSID=[redacted]");
        assertThat(message.getValue()).doesNotContain("secret").doesNotContain("foo=bar");
    }
}
