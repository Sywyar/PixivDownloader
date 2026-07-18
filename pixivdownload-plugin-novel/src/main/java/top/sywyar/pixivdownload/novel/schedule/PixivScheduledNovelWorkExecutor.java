package top.sywyar.pixivdownload.novel.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxProxyClient;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService;
import top.sywyar.pixivdownload.novel.download.NovelDownloadExecutionLane;
import top.sywyar.pixivdownload.novel.download.NovelDownloader;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;
import top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRunContext;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 基于插件 API 的 Pixiv 小说计划作品执行器。 */
@PluginManagedBean
public final class PixivScheduledNovelWorkExecutor implements ScheduledWorkExecutor {

    static final String WORK_TYPE = "novel";
    static final String WORK_PAYLOAD_SCHEMA = "pixiv.schedule.work-reference";
    static final int WORK_PAYLOAD_VERSION = 1;
    private static final String SERIES_SOURCE_TYPE = "series";

    private static final Logger log = LoggerFactory.getLogger(PixivScheduledNovelWorkExecutor.class);

    private final ObjectMapper objectMapper;
    private final PixivAjaxProxyClient pixivAjaxProxyClient;
    private final WorkQueryService workQueryService;
    private final WorkMetaCaptureService workMetaCaptureService;
    private final NovelDownloader novelDownloader;
    private final NovelMergeService novelMergeService;
    private final NovelAutoTranslateService novelAutoTranslateService;
    private final NovelDownloadExecutionLane downloadExecutionLane;
    private final ConcurrentHashMap<SeriesCacheKey, Optional<PixivScheduledNovelMetadata.SeriesMetadata>>
            seriesMetadataCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ScheduledTaskDefinition> seriesCacheRuns = new ConcurrentHashMap<>();

    public PixivScheduledNovelWorkExecutor(
            ObjectMapper objectMapper,
            PixivAjaxProxyClient pixivAjaxProxyClient,
            WorkQueryService workQueryService,
            WorkMetaCaptureService workMetaCaptureService,
            NovelDownloader novelDownloader,
            NovelMergeService novelMergeService,
            NovelAutoTranslateService novelAutoTranslateService,
            NovelDownloadExecutionLane downloadExecutionLane) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.pixivAjaxProxyClient = Objects.requireNonNull(pixivAjaxProxyClient, "pixivAjaxProxyClient");
        this.workQueryService = Objects.requireNonNull(workQueryService, "workQueryService");
        this.workMetaCaptureService = Objects.requireNonNull(workMetaCaptureService, "workMetaCaptureService");
        this.novelDownloader = Objects.requireNonNull(novelDownloader, "novelDownloader");
        this.novelMergeService = Objects.requireNonNull(novelMergeService, "novelMergeService");
        this.novelAutoTranslateService = Objects.requireNonNull(
                novelAutoTranslateService, "novelAutoTranslateService");
        this.downloadExecutionLane = Objects.requireNonNull(
                downloadExecutionLane, "downloadExecutionLane");
    }

    @Override
    public String workType() {
        return WORK_TYPE;
    }

    @Override
    public int maxConcurrency() {
        return downloadExecutionLane.capacity();
    }

    @Override
    public ScheduledWorkResult execute(ScheduledWork work, ScheduledWorkContext context)
            throws ScheduledExecutionException {
        try {
            return downloadExecutionLane.executeAndWait(() -> executeInLane(work, context));
        } catch (ScheduledExecutionException e) {
            throw e;
        } catch (InterruptedException e) {
            throw ScheduledExecutionException.cancelled();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw failure(ScheduledFailure.Category.INTERNAL, "pixiv.novel.execution-lane-failed");
        }
    }

    private ScheduledWorkResult executeInLane(ScheduledWork work, ScheduledWorkContext context)
            throws ScheduledExecutionException {
        Objects.requireNonNull(context, "context");
        ScheduledTaskDefinition task = context.task();
        try {
            long novelId = decodeWorkId(work);
            Objects.requireNonNull(task, "task");
            return executePrepared(novelId, context);
        } catch (ScheduledExecutionException e) {
            if (task != null) {
                clearFailedRunCache(task);
            }
            throw e;
        } catch (RuntimeException | Error e) {
            if (task != null) {
                clearFailedRunCache(task);
            }
            throw e;
        }
    }

    private ScheduledWorkResult executePrepared(long novelId, ScheduledWorkContext context)
            throws ScheduledExecutionException {
        PixivScheduledNovelDefinition definition = parseDefinition(context);
        context.cancellation().throwIfCancellationRequested();
        boolean alreadyDownloaded = definition.download().redownloadDeleted()
                ? workQueryService.hasActiveWork(WorkType.NOVEL, novelId)
                : workQueryService.hasWork(WorkType.NOVEL, novelId);
        if (alreadyDownloaded) {
            return ScheduledWorkResult.alreadyCompleted();
        }

        ScheduledCredentialHandle credential = Objects.requireNonNull(context.credential(), "credential");
        char[] secret = credential.isPresent() ? credential.copySecret() : new char[0];
        try {
            String cookie = secret.length == 0 ? null : new String(secret);
            return executeWithRoute(novelId, cookie, definition, context);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private ScheduledWorkResult executeWithRoute(
            long novelId,
            String cookie,
            PixivScheduledNovelDefinition definition,
            ScheduledWorkContext context) throws ScheduledExecutionException {
        ScheduledNetworkRoute route = Objects.requireNonNull(context.route(), "route");
        if (!route.isResolved()) {
            throw failure(ScheduledFailure.Category.INVALID_DEFINITION, "pixiv.schedule.route-unresolved");
        }
        ScopedWorkExecution execution = new ScopedWorkExecution(
                () -> executeScoped(novelId, cookie, definition, context));
        if (route.mode() == ScheduledNetworkRoute.Mode.DIRECT) {
            OutboundProxyOverride.runDirectScoped(execution);
        } else {
            String proxy = route.proxyHost() + ":" + route.proxyPort();
            if (OutboundProxyOverride.parse(proxy) == null) {
                throw failure(ScheduledFailure.Category.INVALID_DEFINITION, "pixiv.schedule.proxy-unsupported");
            }
            OutboundProxyOverride.runScoped(proxy, execution);
        }
        return execution.result();
    }

    private ScheduledWorkResult executeScoped(
            long novelId,
            String cookie,
            PixivScheduledNovelDefinition definition,
            ScheduledWorkContext context) throws ScheduledExecutionException {
        JsonNode body;
        try {
            context.cancellation().throwIfCancellationRequested();
            URI uri = UriComponentsBuilder
                    .fromUriString("https://www.pixiv.net/ajax/novel/{id}")
                    .queryParam("lang", "zh")
                    .buildAndExpand(Map.of("id", novelId))
                    .encode()
                    .toUri();
            body = requireAjaxBody(pixivAjaxProxyClient.proxyGetUri(uri, cookie));
        } catch (ScheduledExecutionException e) {
            throw e;
        } catch (HttpClientErrorException e) {
            throw classifyClientError(e);
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw failure(ScheduledFailure.Category.RETRYABLE_NETWORK, "pixiv.novel.fetch-retryable");
        } catch (RestClientException e) {
            throw failure(ScheduledFailure.Category.RETRYABLE_NETWORK, "pixiv.novel.fetch-retryable");
        } catch (RuntimeException e) {
            throw failure(ScheduledFailure.Category.RETRYABLE_NETWORK, "pixiv.novel.fetch-retryable");
        }

        PixivScheduledNovelMetadata metadata;
        try {
            metadata = PixivScheduledNovelMetadata.parse(novelId, body);
        } catch (RuntimeException e) {
            throw failure(ScheduledFailure.Category.RETRYABLE_NETWORK, "pixiv.novel.response-invalid");
        }
        if (!metadata.matches(definition.filters())) {
            return new ScheduledWorkResult(
                    ScheduledWorkResult.Outcome.SKIPPED,
                    "pixiv.novel.filtered",
                    resultAttributes(metadata, false));
        }

        PixivScheduledNovelMetadata.SeriesMetadata series = fetchSeriesBestEffort(
                context.task(), metadata.seriesId(), cookie);
        NovelDownloadRequest request = createRequest(metadata, series, definition.download(), cookie);
        context.cancellation().throwIfCancellationRequested();
        boolean downloaded;
        try {
            downloaded = novelDownloader.downloadBlocking(request, null);
        } catch (CancellationException e) {
            throw ScheduledExecutionException.cancelled();
        } catch (RestClientException e) {
            throw failure(ScheduledFailure.Category.RETRYABLE_NETWORK, "pixiv.novel.download-retryable");
        } catch (RuntimeException e) {
            throw failure(ScheduledFailure.Category.RETRYABLE_NETWORK, "pixiv.novel.download-retryable");
        }
        if (!downloaded) {
            throw failure(ScheduledFailure.Category.RETRYABLE_NETWORK, "pixiv.novel.download-incomplete");
        }
        try {
            workMetaCaptureService.captureNovel(novelId, body, "schedule");
        } catch (RuntimeException e) {
            log.warn("Scheduled novel sidecar capture failed: novelId={}, errorType={}",
                    novelId, e.getClass().getSimpleName());
        }
        return new ScheduledWorkResult(
                ScheduledWorkResult.Outcome.COMPLETED,
                "work.completed",
                resultAttributes(metadata, definition.download().novelAutoTranslate()));
    }

    private PixivScheduledNovelMetadata.SeriesMetadata fetchSeriesBestEffort(
            ScheduledTaskDefinition task,
            Long seriesId,
            String cookie) {
        if (seriesId == null || seriesId <= 0L) {
            return null;
        }
        prepareRunCache(task);
        SeriesCacheKey key = new SeriesCacheKey(task.taskId(), seriesId);
        return seriesMetadataCache.computeIfAbsent(
                key,
                ignored -> Optional.ofNullable(fetchSeriesUncached(seriesId, cookie)))
                .orElse(null);
    }

    private PixivScheduledNovelMetadata.SeriesMetadata fetchSeriesUncached(long seriesId, String cookie) {
        try {
            URI uri = UriComponentsBuilder
                    .fromUriString("https://www.pixiv.net/ajax/novel/series/{id}")
                    .queryParam("lang", "zh")
                    .buildAndExpand(Map.of("id", seriesId))
                    .encode()
                    .toUri();
            return PixivScheduledNovelMetadata.parseSeries(
                    requireAjaxBody(pixivAjaxProxyClient.proxyGetUri(uri, cookie)));
        } catch (Exception e) {
            log.debug("Scheduled novel series enrichment skipped: seriesId={}, errorType={}",
                    seriesId, e.getClass().getSimpleName());
            return null;
        }
    }

    private NovelDownloadRequest createRequest(
            PixivScheduledNovelMetadata metadata,
            PixivScheduledNovelMetadata.SeriesMetadata series,
            PixivScheduledNovelDefinition.Download download,
            String cookie) {
        NovelDownloadRequest request = new NovelDownloadRequest();
        request.setNovelId(metadata.novelId());
        request.setTitle(metadata.title());
        request.setCookie(cookie);
        request.setContent(metadata.content());

        NovelDownloadRequest.Other other = new NovelDownloadRequest.Other();
        other.setAuthorId(metadata.authorId());
        other.setAuthorName(metadata.authorName());
        other.setXRestrict(metadata.xRestrict());
        other.setAi(metadata.ai());
        other.setOriginal(metadata.original());
        other.setLanguage(metadata.language());
        other.setWordCount(metadata.wordCount());
        other.setTextLength(metadata.textLength());
        other.setReadingTimeSeconds(metadata.readingTimeSeconds());
        other.setPageCount(metadata.pageCount());
        other.setDescription(metadata.description());
        other.setTags(metadata.tags());
        other.setSeriesId(metadata.seriesId());
        other.setSeriesOrder(metadata.seriesOrder());
        other.setSeriesTitle(metadata.seriesTitle());
        other.setUploadTimestamp(metadata.uploadTimestamp());
        other.setCoverUrl(metadata.coverUrl());
        other.setEmbeddedImages(metadata.embeddedImages());
        other.setFileNameTemplate(download.fileNameTemplate());
        other.setBookmark(download.bookmark());
        other.setCollectionId(download.collectionId());
        other.setFormat(download.novelFormat());
        if (download.novelAutoTranslate()) {
            other.setAutoTranslate(true);
            other.setAutoTranslateLanguage(download.novelTranslateLanguage());
            other.setAutoTranslateSegmentSize(download.novelTranslateSegmentSize());
            other.setAutoTranslateMerge(download.novelMerge());
            other.setAutoTranslateMergeFormat(download.novelMergeFormat());
        }
        if (series != null) {
            if (series.description() != null && !series.description().isBlank()) {
                other.setSeriesDescription(series.description());
            }
            if (series.coverUrl() != null && !series.coverUrl().isBlank()) {
                other.setSeriesCoverUrl(series.coverUrl());
            }
            if (series.tags() != null && !series.tags().isEmpty()) {
                other.setSeriesTags(series.tags());
            }
        }
        request.setOther(other);
        return request;
    }

    @Override
    public void finishRun(ScheduledWorkRunContext context) throws ScheduledExecutionException {
        Objects.requireNonNull(context, "context");
        ScheduledTaskDefinition task = Objects.requireNonNull(context.task(), "task");
        clearRunCache(task);
        if (!WORK_TYPE.equals(context.workType()) || context.statistics().completedWorkCount() <= 0L) {
            return;
        }
        if (!SERIES_SOURCE_TYPE.equals(task.sourceType())) {
            return;
        }
        PixivScheduledNovelDefinition definition;
        try {
            definition = PixivScheduledNovelDefinition.parse(objectMapper, context.task());
        } catch (IllegalArgumentException e) {
            throw failure(ScheduledFailure.Category.INVALID_DEFINITION, "pixiv.schedule.definition-invalid");
        }
        long seriesId = definition.seriesId();
        if (!definition.download().novelMerge() || seriesId <= 0L) {
            return;
        }
        if (context.cancellation().isCancellationRequested()) {
            return;
        }
        try {
            novelMergeService.merge(
                    seriesId,
                    NovelDownloadService.NovelFormat.parse(definition.download().novelMergeFormat()));
        } catch (Exception e) {
            log.warn("Scheduled novel series merge failed: taskId={}, seriesId={}, errorType={}",
                    context.task().taskId(), seriesId, e.getClass().getSimpleName());
        }
    }

    @Override
    public void abortRun(ScheduledTaskDefinition task) {
        clearRunCache(Objects.requireNonNull(task, "task"));
    }

    @Override
    public Map<String, String> status(ScheduledWorkKey key) {
        if (key == null || !WORK_TYPE.equals(key.workType())) {
            return Map.of();
        }
        long novelId;
        try {
            novelId = canonicalPositiveLong(key.id());
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
        NovelAutoTranslateService.StatusView status;
        try {
            status = novelAutoTranslateService.getStatus(novelId);
        } catch (RuntimeException e) {
            return Map.of();
        }
        if (status == null || status.phase() == null || status.phase().isBlank()) {
            return Map.of();
        }
        return Map.of(
                "phase", status.phase(),
                "elapsedSeconds", Long.toString(status.elapsedSeconds()),
                "seriesPending", Integer.toString(status.seriesPending()));
    }

    private PixivScheduledNovelDefinition parseDefinition(ScheduledWorkContext context)
            throws ScheduledExecutionException {
        try {
            return PixivScheduledNovelDefinition.parse(objectMapper, context.task());
        } catch (IllegalArgumentException e) {
            throw failure(ScheduledFailure.Category.INVALID_DEFINITION, "pixiv.schedule.definition-invalid");
        }
    }

    private long decodeWorkId(ScheduledWork work) throws ScheduledExecutionException {
        if (work == null
                || !WORK_TYPE.equals(work.key().workType())
                || !WORK_PAYLOAD_SCHEMA.equals(work.payloadSchema())
                || work.payloadVersion() != WORK_PAYLOAD_VERSION) {
            throw payloadUnsupported();
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(work.payloadJson());
        } catch (JsonProcessingException e) {
            throw payloadUnsupported();
        }
        if (payload == null
                || !payload.isObject()
                || payload.size() != 1
                || !payload.has("workId")
                || !payload.path("workId").isTextual()) {
            throw payloadUnsupported();
        }
        String id = payload.path("workId").textValue();
        if (!work.key().id().equals(id)) {
            throw payloadUnsupported();
        }
        try {
            return canonicalPositiveLong(id);
        } catch (IllegalArgumentException e) {
            throw payloadUnsupported();
        }
    }

    private JsonNode requireAjaxBody(String response) throws ScheduledExecutionException {
        JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw failure(ScheduledFailure.Category.RETRYABLE_NETWORK, "pixiv.novel.response-invalid");
        }
        if (root == null || !root.isObject()) {
            throw failure(ScheduledFailure.Category.RETRYABLE_NETWORK, "pixiv.novel.response-invalid");
        }
        if (root.path("error").asBoolean(false)) {
            throw classifyAjaxError(root.path("message").asText(""));
        }
        JsonNode body = root.path("body");
        if (!body.isObject()) {
            throw failure(ScheduledFailure.Category.RETRYABLE_NETWORK, "pixiv.novel.response-invalid");
        }
        return body;
    }

    private static ScheduledExecutionException classifyAjaxError(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (containsAny(normalized,
                "not found", "does not exist", "deleted", "削除", "存在しません", "不存在")) {
            return failure(ScheduledFailure.Category.NOT_FOUND, "pixiv.novel.not-found");
        }
        if (containsAny(normalized,
                "login", "log in", "cookie", "auth", "ログイン", "登录", "登入")) {
            return failure(ScheduledFailure.Category.CREDENTIAL_INVALID, "pixiv.credential.invalid");
        }
        return failure(ScheduledFailure.Category.RETRYABLE_NETWORK, "pixiv.novel.fetch-retryable");
    }

    private static ScheduledExecutionException classifyClientError(HttpClientErrorException error) {
        HttpStatus status = HttpStatus.resolve(error.getStatusCode().value());
        if (status == HttpStatus.NOT_FOUND || status == HttpStatus.FORBIDDEN) {
            return failure(ScheduledFailure.Category.NOT_FOUND, "pixiv.novel.not-found");
        }
        return failure(ScheduledFailure.Category.RETRYABLE_NETWORK, "pixiv.novel.fetch-retryable");
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static long canonicalPositiveLong(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing id");
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0L || !Long.toString(parsed).equals(value)) {
                throw new IllegalArgumentException("non-canonical id");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid id", e);
        }
    }

    private static ScheduledExecutionException payloadUnsupported() {
        return failure(ScheduledFailure.Category.PAYLOAD_UNSUPPORTED, "pixiv.schedule.work-payload-unsupported");
    }

    private static ScheduledExecutionException failure(ScheduledFailure.Category category, String code) {
        return new ScheduledExecutionException(category, code);
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

    private void clearRunCache(ScheduledTaskDefinition task) {
        detachRun(task);
        clearSeriesEntries(task.taskId());
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
        seriesMetadataCache.keySet().removeIf(key -> key.taskId() == taskId);
    }

    private static Map<String, String> resultAttributes(
            PixivScheduledNovelMetadata metadata,
            boolean autoTranslateSubmitted) {
        if (autoTranslateSubmitted) {
            return Map.of(
                    "title", metadata.title(),
                    "xRestrict", Integer.toString(metadata.xRestrict()),
                    "ai", Boolean.toString(metadata.ai()),
                    "autoTranslateSubmitted", "true");
        }
        return Map.of(
                "title", metadata.title(),
                "xRestrict", Integer.toString(metadata.xRestrict()),
                "ai", Boolean.toString(metadata.ai()));
    }

    @FunctionalInterface
    private interface ScheduledWorkCall {

        ScheduledWorkResult call() throws ScheduledExecutionException;
    }

    private static final class ScopedWorkExecution implements Runnable {

        private final ScheduledWorkCall call;
        private ScheduledWorkResult result;
        private ScheduledExecutionException failure;

        private ScopedWorkExecution(ScheduledWorkCall call) {
            this.call = call;
        }

        @Override
        public void run() {
            try {
                result = Objects.requireNonNull(call.call(), "scheduled work result");
            } catch (ScheduledExecutionException e) {
                failure = e;
            }
        }

        private ScheduledWorkResult result() throws ScheduledExecutionException {
            if (failure != null) {
                throw failure;
            }
            return Objects.requireNonNull(result, "scheduled work result");
        }
    }

    private record SeriesCacheKey(long taskId, long seriesId) {
    }
}
