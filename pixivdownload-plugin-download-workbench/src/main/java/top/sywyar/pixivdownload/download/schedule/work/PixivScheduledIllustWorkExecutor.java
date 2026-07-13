package top.sywyar.pixivdownload.download.schedule.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.HttpClientErrorException;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledIllustSettings;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledIllustWork;
import top.sywyar.pixivdownload.download.ArtworkDownloader;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.download.schedule.network.PixivScheduledRouteScope;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRunContext;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;
import top.sywyar.pixivdownload.schedule.snapshot.ScheduleTaskSnapshot;
import top.sywyar.pixivdownload.schedule.snapshot.ScheduleWorkFilter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pixiv 插画、漫画与动图的 ID 引用解析、筛选及同步下载执行器。 */
@PluginManagedBean
public final class PixivScheduledIllustWorkExecutor implements ScheduledWorkExecutor {

    private static final String PIXIV_REFERER = "https://www.pixiv.net/artworks/";

    private final PixivFetchService fetchService;
    private final PixivDatabase pixivDatabase;
    private final ArtworkDownloader artworkDownloader;
    private final WorkMetaCaptureService workMetaCaptureService;
    private final ScheduledIllustWorkRunner downloadRunner;
    private final PixivSchedulePersistenceCodec persistenceCodec;
    private final ObjectMapper objectMapper;
    private final DownloadConfig downloadConfig;
    private final Map<SeriesKey, PixivFetchService.IllustSeriesMeta> seriesCache =
            new ConcurrentHashMap<>();
    private final Map<Long, ScheduledTaskDefinition> seriesCacheRuns =
            new ConcurrentHashMap<>();

    public PixivScheduledIllustWorkExecutor(
            PixivFetchService fetchService,
            PixivDatabase pixivDatabase,
            ArtworkDownloader artworkDownloader,
            WorkMetaCaptureService workMetaCaptureService,
            ScheduledIllustWorkRunner downloadRunner,
            PixivSchedulePersistenceCodec persistenceCodec,
            ObjectMapper objectMapper,
            DownloadConfig downloadConfig) {
        this.fetchService = fetchService;
        this.pixivDatabase = pixivDatabase;
        this.artworkDownloader = artworkDownloader;
        this.workMetaCaptureService = workMetaCaptureService;
        this.downloadRunner = downloadRunner;
        this.persistenceCodec = persistenceCodec;
        this.objectMapper = objectMapper;
        this.downloadConfig = Objects.requireNonNull(downloadConfig, "downloadConfig");
    }

    @Override
    public String workType() {
        return PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST;
    }

    @Override
    public int maxConcurrency() {
        return downloadConfig.getMaxConcurrent();
    }

    @Override
    public ScheduledWorkResult execute(ScheduledWork work, ScheduledWorkContext context)
            throws ScheduledExecutionException {
        ScheduledTaskDefinition task = Objects.requireNonNull(context.task(), "task");
        try {
            return executeOnce(work, context);
        } catch (ScheduledExecutionException | RuntimeException | Error failure) {
            clearFailedRunCache(task);
            throw failure;
        }
    }

    private ScheduledWorkResult executeOnce(ScheduledWork work, ScheduledWorkContext context)
            throws ScheduledExecutionException {
        String id = decodeId(work);
        long artworkId;
        try {
            artworkId = Long.parseLong(id);
        } catch (NumberFormatException failure) {
            throw failure(ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                    "pixiv.illust.id-invalid");
        }
        ScheduleTaskSnapshot snapshot = parseSnapshot(context);
        if (alreadyDownloaded(artworkId, snapshot.download())) {
            return ScheduledWorkResult.alreadyCompleted();
        }
        String cookie = copyCookie(context);
        try {
            return PixivScheduledRouteScope.call(context.route(), () ->
                    executeScoped(id, artworkId, cookie, snapshot, context));
        } catch (ScheduledExecutionException failure) {
            throw failure;
        } catch (HttpClientErrorException failure) {
            int status = failure.getStatusCode().value();
            if (status == 403 || status == 404) {
                throw failure(ScheduledFailure.Category.NOT_FOUND, "pixiv.illust.gone");
            }
            throw failure(ScheduledFailure.Category.RETRYABLE_NETWORK,
                    "pixiv.illust.http-" + status);
        } catch (PixivFetchService.PixivFetchException failure) {
            throw failure(ScheduledFailure.Category.CREDENTIAL_INVALID,
                    "pixiv.illust.access-unavailable");
        } catch (Exception failure) {
            throw failure(ScheduledFailure.Category.RETRYABLE_NETWORK,
                    "pixiv.illust.fetch-failed");
        }
    }

    private ScheduledWorkResult executeScoped(
            String id,
            long artworkId,
            String cookie,
            ScheduleTaskSnapshot snapshot,
            ScheduledWorkContext context) throws Exception {
        context.cancellation().throwIfCancellationRequested();
        PixivFetchService.ArtworkMetaCapture capture =
                fetchService.fetchArtworkMetaCapture(id, cookie);
        PixivFetchService.ArtworkMeta meta = capture.meta();
        Map<String, String> attributes = Map.of(
                "title", meta.title(),
                "xRestrict", Integer.toString(meta.xRestrict()),
                "ai", Boolean.toString(meta.ai()));
        if (!ScheduleWorkFilter.artworkMatches(meta, snapshot.filters())) {
            return new ScheduledWorkResult(
                    ScheduledWorkResult.Outcome.SKIPPED,
                    "pixiv.illust.filtered", attributes);
        }

        String seriesTitle = null;
        String seriesDescription = null;
        String seriesCoverUrl = null;
        if (meta.seriesId() != null && meta.seriesId() > 0) {
            seriesTitle = meta.seriesTitle();
            PixivFetchService.IllustSeriesMeta series = seriesMeta(
                    context.task(), meta.seriesId(), cookie);
            if (series != null) {
                seriesDescription = blankToNull(series.caption());
                seriesCoverUrl = blankToNull(series.coverUrl());
            }
        }

        boolean ugoira = false;
        String ugoiraZipUrl = null;
        List<Integer> ugoiraDelays = null;
        List<String> imageUrls;
        JsonNode pagesBody = null;
        if (meta.isUgoira()) {
            PixivFetchService.UgoiraInfo info = fetchService.resolveUgoira(id, cookie);
            if (info.zipUrl() == null || info.zipUrl().isBlank()) {
                throw failure(ScheduledFailure.Category.RETRYABLE_NETWORK,
                        "pixiv.illust.ugoira-empty");
            }
            ugoira = true;
            ugoiraZipUrl = info.zipUrl();
            ugoiraDelays = info.delays();
            imageUrls = List.of(info.zipUrl());
        } else {
            PixivFetchService.ArtworkPages pages = fetchService.resolveArtworkPages(id, cookie);
            imageUrls = pages.urls();
            pagesBody = pages.body();
            if (imageUrls.isEmpty()) {
                throw failure(ScheduledFailure.Category.RETRYABLE_NETWORK,
                        "pixiv.illust.images-empty");
            }
        }

        ScheduledIllustWork prepared = new ScheduledIllustWork(
                artworkId, meta.title(), meta.authorId(), meta.authorName(), meta.xRestrict(), meta.ai(),
                meta.description(), meta.tags(), meta.seriesId(), meta.seriesOrder(), seriesTitle,
                meta.illustType(), seriesDescription, seriesCoverUrl,
                ugoira, ugoiraZipUrl, ugoiraDelays, imageUrls, PIXIV_REFERER + id);
        ScheduleTaskSnapshot.Download download = snapshot.download();
        ScheduledIllustSettings settings = new ScheduledIllustSettings(
                download.fileNameTemplate(), download.bookmark(), download.collectionId(),
                download.imageDelayMs());
        context.cancellation().throwIfCancellationRequested();
        boolean downloaded = downloadRunner.download(prepared, settings, cookie);
        if (!downloaded) {
            throw failure(ScheduledFailure.Category.RETRYABLE_NETWORK,
                    "pixiv.illust.download-failed");
        }
        workMetaCaptureService.captureArtwork(artworkId, capture.body(), pagesBody, "schedule");
        return new ScheduledWorkResult(
                ScheduledWorkResult.Outcome.COMPLETED,
                "pixiv.illust.completed", attributes);
    }

    @Override
    public void finishRun(ScheduledWorkRunContext context) {
        ScheduledTaskDefinition task = Objects.requireNonNull(context.task(), "task");
        clearRunCache(task);
    }

    @Override
    public void abortRun(ScheduledTaskDefinition task) {
        clearRunCache(Objects.requireNonNull(task, "task"));
    }

    private void clearRunCache(ScheduledTaskDefinition task) {
        detachRun(task);
        clearSeriesEntries(task.taskId());
    }

    private PixivFetchService.IllustSeriesMeta seriesMeta(
            ScheduledTaskDefinition task,
            long seriesId,
            String cookie) {
        prepareRunCache(task);
        SeriesKey key = new SeriesKey(task.taskId(), seriesId);
        return seriesCache.computeIfAbsent(key, ignored -> {
            try {
                return fetchService.fetchIllustSeriesMeta(seriesId, cookie);
            } catch (Exception failure) {
                return new PixivFetchService.IllustSeriesMeta("", "");
            }
        });
    }

    private void prepareRunCache(ScheduledTaskDefinition task) {
        seriesCacheRuns.compute(task.taskId(), (taskId, current) -> {
            if (current != task) {
                clearSeriesEntries(taskId);
            }
            return task;
        });
    }

    private void clearFailedRunCache(ScheduledTaskDefinition task) {
        if (detachRun(task)) {
            clearSeriesEntries(task.taskId());
        }
    }

    private boolean detachRun(ScheduledTaskDefinition task) {
        AtomicBoolean detached = new AtomicBoolean(false);
        seriesCacheRuns.computeIfPresent(task.taskId(), (taskId, current) -> {
            if (current == task) {
                detached.set(true);
                return null;
            }
            return current;
        });
        return detached.get();
    }

    private void clearSeriesEntries(long taskId) {
        seriesCache.keySet().removeIf(key -> key.taskId() == taskId);
    }

    private boolean alreadyDownloaded(long artworkId, ScheduleTaskSnapshot.Download download) {
        if (download.redownloadDeleted()) {
            return download.verifyFiles()
                    ? !pixivDatabase.isArtworkDeleted(artworkId)
                    && artworkDownloader.isArtworkDownloaded(artworkId, true)
                    : pixivDatabase.hasActiveArtwork(artworkId);
        }
        return download.verifyFiles()
                ? artworkDownloader.isArtworkDownloaded(artworkId, true)
                : pixivDatabase.hasArtwork(artworkId);
    }

    private ScheduleTaskSnapshot parseSnapshot(ScheduledWorkContext context)
            throws ScheduledExecutionException {
        try {
            return ScheduleTaskSnapshot.parse(objectMapper, context.task().definitionJson());
        } catch (Exception failure) {
            throw failure(ScheduledFailure.Category.INVALID_DEFINITION,
                    "pixiv.schedule.definition-invalid");
        }
    }

    private String decodeId(ScheduledWork work) throws ScheduledExecutionException {
        try {
            return persistenceCodec.decodeWorkId(work);
        } catch (IllegalArgumentException failure) {
            throw failure(ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                    "pixiv.illust.payload-invalid");
        }
    }

    private static String copyCookie(ScheduledWorkContext context) {
        char[] secret = context.credential().copySecret();
        try {
            return new String(secret);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private static ScheduledExecutionException failure(
            ScheduledFailure.Category category, String code) {
        return new ScheduledExecutionException(category, code);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record SeriesKey(long taskId, long seriesId) {
    }
}
