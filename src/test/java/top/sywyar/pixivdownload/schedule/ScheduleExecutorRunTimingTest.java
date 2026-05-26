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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
                config, new ScheduleRunState(), new ObjectMapper());
        when(database.mapper()).thenReturn(mapper);
    }

    @Test
    @DisplayName("固定周期：等待真实下载完成后才记录上次运行与下次运行时间")
    void shouldRecordNextRunAfterBlockingDownloadCompletes() throws Exception {
        ScheduledTask task = new ScheduledTask(
                1L, "画师计划", true, ScheduledTaskType.USER_NEW,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, 0L, null, null, null, 0L);
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
}
