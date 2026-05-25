package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.download.ArtworkDownloader;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.schedule.db.ScheduledTaskDatabase;

import java.util.List;

/**
 * 计划任务的执行核心：按任务类型在服务端发现作品 ID、跳过已下载、逐个抓取元数据并派发下载。
 *
 * <p>调度以管理员身份运行（{@code userUuid=null}，不计配额 / 限流）。本类只依赖 {@code download/} 的窄接缝
 * （{@link ArtworkDownloader} / {@link PixivFetchService} / {@link PixivDatabase}）与本包的 {@code db/}，
 * 不反向依赖 {@link ScheduleService} / {@link ScheduleRunner}，因此调度包内无环。
 *
 * <p>健壮性：单个作品抓取 / 下载失败仅记日志、不挂整轮；
 * 捕获 {@link PixivFetchService.PixivFetchException}（鉴权失效）则立即停止本轮、标记
 * {@link #STATUS_AUTH_EXPIRED}，等待管理员重新授权，不空跑重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleExecutor {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_AUTH_EXPIRED = "AUTH_EXPIRED";
    public static final String STATUS_ERROR = "ERROR";

    private static final String PIXIV_REFERER = "https://www.pixiv.net/";

    private final ScheduledTaskDatabase database;
    private final PixivFetchService pixivFetchService;
    private final PixivDatabase pixivDatabase;
    private final ArtworkDownloader artworkDownloader;
    private final ScheduleConfig scheduleConfig;
    private final ObjectMapper objectMapper;

    /** 后台异步运行一次（供「立即运行」端点用，避免阻塞 HTTP 请求线程）。 */
    @Async
    public void runTaskAsync(long taskId) {
        ScheduledTask task = database.mapper().findById(taskId);
        if (task != null) {
            runTaskAndRecord(task);
        }
    }

    /**
     * 同步运行一个任务并把结果写回（last_run_time / last_status / next_run_time）。
     * 调度 tick 串行调用本方法。
     */
    public void runTaskAndRecord(ScheduledTask task) {
        long now = System.currentTimeMillis();
        String status;
        try {
            int dispatched = runTask(task);
            status = STATUS_OK;
            log.info("Scheduled task {} ({}) dispatched {} new download(s)", task.id(), task.name(), dispatched);
        } catch (PixivFetchService.PixivFetchException e) {
            // 鉴权失效：不写 cookie 到日志，仅记任务标识与提示
            status = STATUS_AUTH_EXPIRED;
            log.warn("Scheduled task {} ({}) auth expired, awaiting re-authorization", task.id(), task.name());
        } catch (Exception e) {
            status = STATUS_ERROR;
            log.error("Scheduled task {} ({}) failed: {}", task.id(), task.name(), e.getMessage(), e);
        }
        Long nextRun = ScheduleTiming.computeNextRun(
                task.triggerKind(), task.intervalMinutes(), task.cronExpr(), now);
        database.mapper().updateRunResult(task.id(), now, status, nextRun);
    }

    /**
     * 发现 + 下载，返回派发的新下载数。
     *
     * @throws PixivFetchService.PixivFetchException Cookie 失效 / 受限需登录时上抛，供 {@link #runTaskAndRecord} 标记鉴权失效
     */
    private int runTask(ScheduledTask task) throws Exception {
        String cookie = ScheduledTask.COOKIE_BOUND.equals(task.cookieMode())
                ? database.mapper().findCookieSnapshot(task.id())
                : null;

        List<String> ids = discoverIds(task, cookie);
        int dispatched = 0;
        for (String id : ids) {
            long artworkId;
            try {
                artworkId = Long.parseLong(id);
            } catch (NumberFormatException e) {
                continue;
            }
            if (pixivDatabase.hasArtwork(artworkId)) {
                continue;
            }
            try {
                dispatchDownload(artworkId, id, cookie);
                dispatched++;
            } catch (PixivFetchService.PixivFetchException e) {
                throw e; // 鉴权失效：让整轮停下
            } catch (Exception e) {
                // 单作品失败隔离：仅记日志，继续后续作品
                log.warn("Scheduled task {} skip artwork {}: {}", task.id(), id, e.getMessage());
            }
            politeDelay();
        }
        return dispatched;
    }

    private List<String> discoverIds(ScheduledTask task, String cookie) throws Exception {
        JsonNode params = objectMapper.readTree(task.paramsJson() == null ? "{}" : task.paramsJson());
        return switch (task.type()) {
            case USER_NEW -> pixivFetchService.discoverUserArtworkIds(
                    params.path("userId").asText(""), cookie);
            case SEARCH -> pixivFetchService.discoverSearchArtworkIds(
                    params.path("word").asText(""),
                    params.path("order").asText("date_d"),
                    params.path("mode").asText("all"),
                    params.path("sMode").asText("s_tag"),
                    params.path("maxPages").asInt(3),
                    cookie);
            case SERIES -> pixivFetchService.discoverSeriesArtworkIds(
                    params.path("seriesId").asText(""), cookie);
        };
    }

    private void dispatchDownload(long artworkId, String id, String cookie) throws Exception {
        PixivFetchService.ArtworkMeta meta = pixivFetchService.fetchArtworkMeta(id, cookie);
        DownloadRequest.Other other = new DownloadRequest.Other();
        other.setAuthorId(meta.authorId());
        other.setAuthorName(meta.authorName());
        other.setXRestrict(meta.xRestrict());
        other.setAi(meta.ai());
        other.setSeriesId(meta.seriesId());
        other.setSeriesOrder(meta.seriesOrder());
        other.setIllustType(meta.illustType());
        other.setBookmark(false);

        List<String> imageUrls;
        if (meta.isUgoira()) {
            PixivFetchService.UgoiraInfo ugoira = pixivFetchService.resolveUgoira(id, cookie);
            if (ugoira.zipUrl() == null || ugoira.zipUrl().isEmpty()) {
                throw new IllegalStateException("empty ugoira zip url");
            }
            other.setUgoira(true);
            other.setUgoiraZipUrl(ugoira.zipUrl());
            other.setUgoiraDelays(ugoira.delays());
            imageUrls = List.of(ugoira.zipUrl());
        } else {
            imageUrls = pixivFetchService.resolveImageUrls(id, cookie);
            if (imageUrls.isEmpty()) {
                throw new IllegalStateException("no image urls resolved");
            }
        }

        artworkDownloader.downloadImages(
                artworkId, meta.title(), imageUrls,
                PIXIV_REFERER + "artworks/" + id, other, cookie, null);
    }

    private void politeDelay() {
        long delay = scheduleConfig.getFetchDelayMs();
        if (delay <= 0) return;
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
