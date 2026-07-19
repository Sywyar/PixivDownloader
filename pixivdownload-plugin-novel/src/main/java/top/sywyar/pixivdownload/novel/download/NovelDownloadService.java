package top.sywyar.pixivdownload.novel.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;
import top.sywyar.pixivdownload.core.collection.CollectionDownloadRootResolver;
import top.sywyar.pixivdownload.core.collection.WorkCollectionMembership;
import top.sywyar.pixivdownload.core.pixiv.PixivDescriptionHtml;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker;
import top.sywyar.pixivdownload.plugin.runtime.download.queue.QueueStatusRetention;
import top.sywyar.pixivdownload.core.pixiv.PixivBookmarkService;
import top.sywyar.pixivdownload.core.pixiv.PixivCoverUrlResolver;
import top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaService;
import top.sywyar.pixivdownload.core.time.EpochMillisNormalizer;
import top.sywyar.pixivdownload.core.work.WorkActionResult;
import top.sywyar.pixivdownload.core.work.PixivWorkFileNameFormatter;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.AuthorObservationService;
import top.sywyar.pixivdownload.core.work.service.DownloadPathGuard;
import top.sywyar.pixivdownload.core.work.service.WorkFileNameCatalog;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import top.sywyar.pixivdownload.novel.NovelSeriesService;
import top.sywyar.pixivdownload.novel.export.NovelEpubWriter;
import top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService;

@Slf4j
@Service
public class NovelDownloadService implements NovelDownloader {

    public enum NovelFormat {
        TXT("txt"), HTML("html"), EPUB("epub");

        private final String ext;

        NovelFormat(String ext) {
            this.ext = ext;
        }

        public String ext() { return ext; }

        public static NovelFormat parse(String value) {
            if (value == null || value.isBlank()) return TXT;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "html" -> HTML;
                case "epub" -> EPUB;
                default -> TXT;
            };
        }
    }

    private static final Set<String> COVER_EXT_WHITELIST = Set.of("jpg", "jpeg", "png", "webp");
    private static final Set<String> IMAGE_EXT_WHITELIST = Set.of("jpg", "jpeg", "png", "webp", "gif");
    /** 单本小说最多下载多少张内嵌图，避免极端情况吃满磁盘。 */
    private static final int MAX_EMBEDDED_IMAGES_PER_NOVEL = 200;

    private final DownloadSettings downloadConfig;
    private final WorkFileNameCatalog workFileNameCatalog;
    private final DownloadPathGuard downloadPathGuard;
    private final NovelDatabase novelDatabase;
    private final NovelSeriesService novelSeriesService;
    private final AuthorObservationService authorObservationService;
    private final WorkCollectionMembership workCollectionMembership;
    private final CollectionDownloadRootResolver collectionDownloadRootResolver;
    private final PixivBookmarkService pixivBookmarkService;
    private final VisitorDownloadQuotaService visitorDownloadQuotaService;
    private final RestTemplate downloadRestTemplate;
    private final TaskScheduler taskScheduler;
    private final NovelDownloadExecutionLane downloadExecutionLane;
    private final MessageResolver messages;
    private final NovelAutoTranslateService novelAutoTranslateService;
    private final WorkMetaCaptureService workMetaCaptureService;

    private final ConcurrentHashMap<String, NovelDownloadStatus> statusMap = new ConcurrentHashMap<>();
    private final QueueTaskTracker taskTracker = new QueueTaskTracker("novel");

    public NovelDownloadService(DownloadSettings downloadConfig,
                                WorkFileNameCatalog workFileNameCatalog,
                                DownloadPathGuard downloadPathGuard,
                                NovelDatabase novelDatabase,
                                NovelSeriesService novelSeriesService,
                                AuthorObservationService authorObservationService,
                                WorkCollectionMembership workCollectionMembership,
                                CollectionDownloadRootResolver collectionDownloadRootResolver,
                                PixivBookmarkService pixivBookmarkService,
                                @Nullable VisitorDownloadQuotaService visitorDownloadQuotaService,
                                @Qualifier("downloadRestTemplate") RestTemplate downloadRestTemplate,
                                @Qualifier("taskScheduler") TaskScheduler taskScheduler,
                                NovelDownloadExecutionLane downloadExecutionLane,
                                MessageResolver messages,
                                NovelAutoTranslateService novelAutoTranslateService,
                                WorkMetaCaptureService workMetaCaptureService) {
        this.downloadConfig = downloadConfig;
        this.workFileNameCatalog = workFileNameCatalog;
        this.downloadPathGuard = downloadPathGuard;
        this.novelDatabase = novelDatabase;
        this.novelSeriesService = novelSeriesService;
        this.authorObservationService = authorObservationService;
        this.workCollectionMembership = workCollectionMembership;
        this.collectionDownloadRootResolver = collectionDownloadRootResolver;
        this.pixivBookmarkService = pixivBookmarkService;
        this.visitorDownloadQuotaService = visitorDownloadQuotaService;
        this.downloadRestTemplate = downloadRestTemplate;
        this.taskScheduler = taskScheduler;
        this.downloadExecutionLane = downloadExecutionLane;
        this.messages = messages;
        this.novelAutoTranslateService = novelAutoTranslateService;
        this.workMetaCaptureService = workMetaCaptureService;
    }

    @Override
    public void download(NovelDownloadRequest request, String userUuid) {
        QueueTaskTracker.Task task = taskTracker.prepareQueued(userUuid);
        task.bind(() -> downloadTracked(task, request, userUuid));
        try {
            downloadExecutionLane.execute(task);
        } catch (RuntimeException | Error failure) {
            task.rejectSubmission();
            throw failure;
        }
    }

    @Override
    public boolean downloadBlocking(NovelDownloadRequest request, String userUuid) {
        try {
            return downloadExecutionLane.executeAndWait(() -> downloadBlockingInLane(request, userUuid));
        } catch (InterruptedException e) {
            throw new CancellationException("novel download interrupted");
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("novel download lane failed", e);
        }
    }

    private boolean downloadBlockingInLane(NovelDownloadRequest request, String userUuid) {
        QueueTaskTracker.Task task = taskTracker.beginRunning(userUuid);
        try {
            return downloadTracked(task, request, userUuid);
        } finally {
            task.completeRunning();
        }
    }

    private boolean downloadTracked(QueueTaskTracker.Task task,
                                    NovelDownloadRequest request,
                                    String userUuid) {
        boolean succeeded = false;
        Long novelId = request.getNovelId();
        NovelDownloadRequest.Other other = request.getOther() == null
                ? new NovelDownloadRequest.Other() : request.getOther();
        NovelFormat format = NovelFormat.parse(other.getFormat());
        String title = request.getTitle() == null ? String.valueOf(novelId) : request.getTitle();
        NovelDownloadStatus status = new NovelDownloadStatus(novelId, title, format.ext(), userUuid);
        String statusKey = statusKey(novelId, userUuid);
        task.onCancellation(() -> cancelTrackedStatus(statusKey, status));
        if (!task.publishIfActive(() -> statusMap.put(statusKey, status))) {
            return false;
        }

        try {
            String rawContent = request.getContent() == null ? "" : request.getContent();
            status.setStage("preparing");
            ensureNotCancelled(status);

            // Resolve folder
            validateUserDownloadFolder(other);
            Path downloadRoot = resolveEffectiveDownloadRoot(other).toAbsolutePath().normalize();
            Path downloadPath = downloadRoot;
            if (other.isUserDownload() && other.getUsername() != null && !downloadConfig.isUserFlatFolder()) {
                downloadPath = downloadPath.resolve(downloadPathGuard.requireSafeDirectoryName(other.getUsername()));
                if (other.getXRestrict() == 2) {
                    downloadPath = downloadPath.resolve("R18G");
                } else if (other.getXRestrict() == 1) {
                    downloadPath = downloadPath.resolve("R18");
                }
            }
            String folderName = "novel-" + novelId;
            downloadPath = downloadPath.resolve(folderName).normalize();
            downloadPathGuard.requireWithinRoot(downloadRoot, downloadPath);
            status.setFolderName(displayFolderName(downloadRoot, downloadPath));
            Files.createDirectories(downloadPath);
            status.setDownloadPath(downloadPath.toString());
            ensureNotCancelled(status);

            // Resolve filename template
            long timestamp = other.getFileNameTimestamp() != null
                    ? EpochMillisNormalizer.normalize(other.getFileNameTimestamp())
                    : System.currentTimeMillis();
            String template = PixivWorkFileNameFormatter.normalizeTemplate(other.getFileNameTemplate());
            long templateId = workFileNameCatalog.getOrCreateTemplateId(template);
            String safeAuthorName = PixivWorkFileNameFormatter.normalizeBaseName(
                    other.getAuthorName(), other.getAuthorId() == null ? "" : String.valueOf(other.getAuthorId()));
            Long fileAuthorNameId = safeAuthorName.isEmpty()
                    ? null : workFileNameCatalog.getOrCreateAuthorNameId(safeAuthorName);
            List<String> names = PixivWorkFileNameFormatter.formatAll(
                    template, novelId, title, other.getAuthorId(), other.getAuthorName(),
                    timestamp, 1, other.isAi(), other.getXRestrict());
            String baseName = names.isEmpty() ? String.valueOf(novelId) : names.get(0);

            // Best-effort 内嵌图片下载（与正文同目录、embed_{id}.{ext}）；
            // 写入 HTML/EPUB 之前完成，使写入时即可解析为本地图片链接。
            Map<String, String> embeddedExts = downloadEmbeddedImages(
                    novelId, rawContent, other.getEmbeddedImages(), downloadPath, request.getCookie(), status);
            ensureNotCancelled(status);

            // Best-effort 封面下载（与正文同目录、_thumb.{ext}）。
            // 必须早于写文件：EPUB 需要把封面字节内嵌进电子书。
            if (other.getCoverUrl() != null && !other.getCoverUrl().isBlank()) {
                status.setStage("downloading-cover");
            }
            String coverExt = downloadCover(other.getCoverUrl(), downloadPath, baseName, request.getCookie(), status);
            ensureNotCancelled(status);

            // Write file
            status.setStage("writing");
            String ext = format.ext();
            Path outputFile = downloadPath.resolve(baseName + "." + ext);
            NovelMarkupParser.ImageResolver resolver = localFolderResolver(embeddedExts);
            switch (format) {
                case TXT -> writeTxt(outputFile, rawContent);
                case HTML -> writeHtml(outputFile, title, rawContent, other, resolver);
                case EPUB -> writeEpub(outputFile, novelId, title, other,
                        rawContent, downloadPath, baseName, coverExt, embeddedExts);
            }
            ensureNotCancelled(status);

            // Persist DB
            status.setStage("saving");
            String description = PixivDescriptionHtml.normalizeLinks(other.getDescription());
            long uniqueTime = novelDatabase.getUniqueTime(other.getUploadTimestamp() != null
                    ? EpochMillisNormalizer.normalize(other.getUploadTimestamp())
                    : timestamp);
            novelDatabase.insertNovel(novelId, title, downloadPath.toAbsolutePath().toString(), 1, ext, uniqueTime,
                    other.getXRestrict(), other.isAi(), other.getAuthorId(), description,
                    templateId, fileAuthorNameId, other.getSeriesId(), other.getSeriesOrder(),
                    other.getWordCount(), other.getTextLength(), other.getReadingTimeSeconds(),
                    other.getPageCount(), other.isOriginal(), other.getLanguage(), rawContent, coverExt);

            // Tags
            if (other.getTags() != null && !other.getTags().isEmpty()) {
                novelDatabase.clearNovelTags(novelId);
                novelDatabase.saveNovelTags(novelId, other.getTags());
            }
            // Author + series
            if (other.getAuthorId() != null && other.getAuthorId() > 0) {
                authorObservationService.observe(other.getAuthorId(), other.getAuthorName());
            }
            if (other.getSeriesId() != null && other.getSeriesId() > 0) {
                // 前端/脚本若一并送来了系列简介/封面/tags，由 NovelSeriesService.observeWithMetadata 落库；
                // 否则退回到原来仅 upsert 标题/作者的 observeSeries()。
                boolean hasRichMeta = (other.getSeriesDescription() != null && !other.getSeriesDescription().isBlank())
                        || (other.getSeriesCoverUrl() != null && !other.getSeriesCoverUrl().isBlank())
                        || (other.getSeriesTags() != null && !other.getSeriesTags().isEmpty());
                if (hasRichMeta) {
                    novelSeriesService.observeWithMetadata(
                            other.getSeriesId(), other.getSeriesTitle(), other.getAuthorId(),
                            other.getSeriesDescription(), other.getSeriesCoverUrl(),
                            other.getSeriesTags(), request.getCookie());
                } else {
                    novelDatabase.observeSeries(other.getSeriesId(), other.getSeriesTitle(), other.getAuthorId());
                }
            }

            // 多人模式游客配额归档
            if (userUuid != null && visitorDownloadQuotaService != null) {
                visitorDownloadQuotaService.recordFolder(userUuid, downloadPath);
            }

            // Best-effort bookmark
            if (other.isBookmark()) {
                status.setStage("bookmarking");
                status.setBookmarkResult(pixivBookmarkService.bookmarkNovel(novelId, request.getCookie()));
            }

            // Best-effort collection
            if (other.getCollectionId() != null) {
                status.setStage("collecting");
                try {
                    boolean added = workCollectionMembership.addWork(
                            WorkType.NOVEL, other.getCollectionId(), novelId);
                    status.setCollectionResult(added
                            ? WorkActionResult.success(messages.get("collection.result.added"))
                            : WorkActionResult.exists(messages.get("collection.result.exists")));
                } catch (Exception e) {
                    log.warn("novel collection add failed: novel={}, collection={}: {}",
                            novelId, other.getCollectionId(), e.getMessage(), e);
                    status.setCollectionResult(WorkActionResult.failed(
                            messages.get("collection.result.failed")));
                }
            }

            status.setStage("completed");
            status.setCompleted(true);
            status.setEndTime(java.time.LocalDateTime.now());
            log.info("novel download completed: id={}, format={}, path={}", novelId, ext, downloadPath);
            succeeded = true;

            // 前端转发的原始 meta（若有）：下载成功、小说行已落库后旁路归一化为 sidecar + 列投影。
            // 零额外请求、best-effort，绝不反报已成功的下载。
            captureForwardedMeta(novelId, other);

            // Best-effort 下载即自动翻译：提交到服务端翻译队列（独立线程池、同系列串行），
            // 不阻塞本次下载收尾，失败绝不影响已完成的下载。
            if (other.isAutoTranslate()) {
                try {
                    novelAutoTranslateService.submit(novelId, other.getSeriesId(),
                            other.getAutoTranslateLanguage(),
                            other.getAutoTranslateSegmentSize() == null ? 0 : other.getAutoTranslateSegmentSize(),
                            other.isAutoTranslateMerge(), other.getAutoTranslateMergeFormat());
                } catch (Exception e) {
                    log.warn("submit auto-translate failed: novel={}: {}", novelId, e.getMessage());
                }
            }
        } catch (CancellationException e) {
            status.setCancelled(true);
            status.setCompleted(true);
            status.setFailed(false);
            status.setStage("cancelled");
            status.setEndTime(java.time.LocalDateTime.now());
            status.setErrorMessage(messages.get("download.cancelled"));
        } catch (Exception e) {
            log.error("novel download failed: id={}", novelId, e);
            status.setCompleted(true);
            status.setFailed(true);
            status.setErrorMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (statusMap.get(statusKey) == status) {
                QueueStatusRetention.schedule(taskTracker, userUuid, taskScheduler,
                        Instant.now().plusSeconds(300),
                        () -> statusMap.remove(statusKey, status));
            }
        }
        return succeeded;
    }

    /**
     * 前端转发的原始 meta（{@code other.rawMetaJson}）落地：交由 {@link WorkMetaCaptureService} 解析 + 归一化。
     * 仅前端交互下载链路填充该字段（计划任务走后端自抓 body，不填）。捕获已是下载成功后的旁路动作，
     * 全程 best-effort、warn-continue——任何异常都不得反报已成功的下载（沿一致性模型，由下次下载或历史回填自愈）。
     */
    private void captureForwardedMeta(Long novelId, NovelDownloadRequest.Other other) {
        if (other == null || !StringUtils.hasText(other.getRawMetaJson())) {
            return;
        }
        try {
            workMetaCaptureService.captureForwardedNovel(novelId, other.getRawMetaJson());
        } catch (RuntimeException e) {
            log.warn("Failed to capture forwarded novel meta for {}: {}", novelId, e.getMessage());
        }
    }

    public NovelDownloadStatus getStatus(Long novelId) {
        return findAnyStatus(novelId);
    }

    public NovelDownloadStatus getStatus(Long novelId, String ownerUuid, boolean admin) {
        if (admin) {
            return findAnyStatus(novelId);
        }
        return statusMap.get(statusKey(novelId, ownerUuid));
    }

    public int forceClearDownloads() {
        int cancelledTasks = 0;
        int clearedStatuses = 0;
        Throwable failure = null;
        try {
            cancelledTasks = taskTracker.cancelActive();
        } catch (Throwable error) {
            failure = error;
        }
        try {
            clearedStatuses = forceClearDownloads(status -> true);
        } catch (Throwable error) {
            failure = mergeFailure(failure, error);
        }
        rethrow(failure);
        return clearedStatuses > 0 ? clearedStatuses : cancelledTasks;
    }

    public int forceClearDownloadsForOwner(String ownerUuid) {
        int cancelledTasks = 0;
        int clearedStatuses = 0;
        Throwable failure = null;
        try {
            cancelledTasks = taskTracker.cancelForOwner(ownerUuid);
        } catch (Throwable error) {
            failure = error;
        }
        try {
            clearedStatuses = forceClearDownloads(
                    status -> java.util.Objects.equals(status.getOwnerUuid(), ownerUuid));
        } catch (Throwable error) {
            failure = mergeFailure(failure, error);
        }
        rethrow(failure);
        return clearedStatuses > 0 ? clearedStatuses : cancelledTasks;
    }

    /** 先停止接收并取得唯一 drain；本方法不执行插件 callback。 */
    public QueueGenerationDrain prepareQuiesceDownloads() {
        return taskTracker.prepareQuiesce();
    }

    /** drain 已由生命周期保存后，再取消本代任务并清理状态。 */
    public void cancelQuiescedDownloads() {
        Throwable failure = null;
        try {
            taskTracker.cancelQuiescedTasks();
        } catch (Throwable error) {
            failure = error;
        }
        try {
            forceClearDownloads(status -> true);
        } catch (Throwable error) {
            failure = mergeFailure(failure, error);
        }
        rethrow(failure);
    }

    private int forceClearDownloads(java.util.function.Predicate<NovelDownloadStatus> matcher) {
        AtomicInteger cleared = new AtomicInteger();
        Throwable failure = null;
        for (var entry : List.copyOf(statusMap.entrySet())) {
            String key = entry.getKey();
            NovelDownloadStatus status = entry.getValue();
            try {
                if (status == null || !matcher.test(status)) {
                    continue;
                }
                status.setCancelled(true);
                status.setCompleted(true);
                status.setFailed(false);
                status.setStage("cancelled");
                status.setEndTime(java.time.LocalDateTime.now());
                status.setErrorMessage(messages.get("download.cancelled"));
                if (statusMap.remove(key, status)) {
                    cleared.incrementAndGet();
                }
            } catch (Throwable error) {
                failure = mergeFailure(failure, error);
            }
        }
        rethrow(failure);
        return cleared.get();
    }

    private void cancelTrackedStatus(String statusKey, NovelDownloadStatus status) {
        status.setCancelled(true);
        status.setCompleted(true);
        status.setFailed(false);
        status.setStage("cancelled");
        status.setEndTime(java.time.LocalDateTime.now());
        status.setErrorMessage(messages.get("download.cancelled"));
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static Throwable mergeFailure(Throwable current, Throwable failure) {
        if (current == null) {
            return failure;
        }
        if (failureRank(failure) > failureRank(current)) {
            addSuppressedSafely(failure, current);
            return failure;
        }
        addSuppressedSafely(current, failure);
        return current;
    }

    private static int failureRank(Throwable failure) {
        if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath) {
            return 2;
        }
        return failure instanceof Error ? 1 : 0;
    }

    private static void addSuppressedSafely(Throwable target, Throwable failure) {
        if (target == failure) {
            return;
        }
        try {
            target.addSuppressed(failure);
        } catch (Throwable ignored) {
            // 诊断附加失败不得覆盖主失败对象。
        }
    }

    public void validateUserDownloadFolder(NovelDownloadRequest.Other other) {
        if (other != null && other.isUserDownload() && other.getUsername() != null) {
            downloadPathGuard.requireSafeDirectoryName(other.getUsername());
        }
    }

    /**
     * 在所有 owner 中查找首个匹配 novelId 的下载状态。
     * 仅用于 admin / solo 路径——multi 模式下两个用户并发下载同一小说时返回任意一方，不保证稳定性。
     */
    private NovelDownloadStatus findAnyStatus(Long novelId) {
        if (novelId == null) {
            return null;
        }
        for (NovelDownloadStatus status : statusMap.values()) {
            if (novelId.equals(status.getNovelId())) {
                return status;
            }
        }
        return null;
    }

    private String statusKey(Long novelId, String ownerUuid) {
        return (ownerUuid == null ? "admin" : ownerUuid) + ":" + novelId;
    }

    private Path resolveEffectiveDownloadRoot(NovelDownloadRequest.Other other) {
        Path defaultRoot = Paths.get(downloadConfig.getRootFolder());
        if (other != null && other.getCollectionId() != null) {
            return collectionDownloadRootResolver.resolveDownloadRoot(other.getCollectionId(), defaultRoot);
        }
        return defaultRoot;
    }

    private String displayFolderName(Path root, Path downloadPath) {
        try {
            return root.toAbsolutePath().normalize()
                    .relativize(downloadPath.toAbsolutePath().normalize())
                    .toString();
        } catch (IllegalArgumentException e) {
            return downloadPath.toString();
        }
    }

    /**
     * 下载小说封面到 {@code {downloadPath}/{baseName}_thumb.{ext}}。
     * Best-effort：URL 缺失、host 非 .pximg.net、网络失败一律返回 null，调用方据此把 cover_ext 置 NULL。
     */
    private String downloadCover(String coverUrl, Path downloadPath, String baseName, String cookie,
                                 NovelDownloadStatus status) {
        if (coverUrl == null || coverUrl.isBlank()) return null;
        for (String candidateUrl : PixivCoverUrlResolver.downloadCandidates(coverUrl)) {
            ensureNotCancelled(status);
            String ext = downloadCoverCandidate(candidateUrl, downloadPath, baseName, cookie, status);
            if (ext != null) {
                return ext;
            }
        }
        return null;
    }

    private String downloadCoverCandidate(String coverUrl, Path downloadPath, String baseName, String cookie,
                                          NovelDownloadStatus status) {
        URI uri;
        try {
            uri = URI.create(coverUrl);
        } catch (IllegalArgumentException e) {
            log.warn("novel cover skipped — malformed url: {}", coverUrl);
            return null;
        }
        String host = uri.getHost();
        if (host == null || !host.endsWith(".pximg.net")) {
            log.warn("novel cover skipped — host not pximg.net: {}", host);
            return null;
        }
        String ext = inferCoverExt(uri.getPath());
        Path target = downloadPath.resolve(baseName + "_thumb." + ext);
        try {
            Boolean ok = downloadRestTemplate.execute(coverUrl, HttpMethod.GET,
                    request -> PixivRequestHeaders.applyImage(request.getHeaders(), cookie),
                    response -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return Boolean.FALSE;
                        }
                        if (status != null) {
                            long len = response.getHeaders().getContentLength();
                            status.setCoverTotalBytes(len > 0 ? len : 0);
                            status.setCoverDownloadedBytes(0);
                        }
                        copyResponseBody(response.getBody(), target, status,
                                status == null ? null : status::setCoverDownloadedBytes);
                        return Boolean.TRUE;
                    });
            if (Boolean.TRUE.equals(ok)) {
                return ext;
            }
            log.warn("novel cover download non-2xx: {}", coverUrl);
            return null;
        } catch (Exception e) {
            log.warn("novel cover download failed: {} — {}", coverUrl, e.getMessage());
            return null;
        }
    }

    private static String inferCoverExt(String path) {
        if (path == null) return "jpg";
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = last.lastIndexOf('.');
        if (dot < 0 || dot == last.length() - 1) return "jpg";
        String candidate = last.substring(dot + 1).toLowerCase(Locale.ROOT);
        // 去掉可能的查询串残余
        int q = candidate.indexOf('?');
        if (q >= 0) candidate = candidate.substring(0, q);
        return COVER_EXT_WHITELIST.contains(candidate) ? candidate : "jpg";
    }

    private void writeTxt(Path file, String raw) throws IOException {
        String txt = NovelMarkupParser.render(raw, NovelMarkupParser.Format.TXT, imageLabels());
        Files.writeString(file, txt, StandardCharsets.UTF_8);
    }

    private void writeHtml(Path file, String title, String raw,
                           NovelDownloadRequest.Other other,
                           NovelMarkupParser.ImageResolver resolver) throws IOException {
        String body = NovelMarkupParser.render(raw, NovelMarkupParser.Format.HTML, resolver, imageLabels());
        StringBuilder html = new StringBuilder()
                .append("<!DOCTYPE html>\n")
                .append("<html lang=\"")
                .append(escapeHtml(other.getLanguage() == null ? "ja" : other.getLanguage()))
                .append("\">\n<head>\n<meta charset=\"UTF-8\">\n<title>")
                .append(escapeHtml(title))
                .append("</title>\n<style>\n")
                .append("body{font-family:serif;line-height:1.7;max-width:42em;margin:2em auto;padding:0 1em;}\n")
                .append("h1,h2{font-weight:700;}\n")
                .append("figure.novel-image{text-align:center;margin:1em 0;max-width:100%;}\n")
                .append("figure.novel-image img{display:block;margin:0 auto;max-width:90%;height:auto;}\n")
                .append(".novel-image-placeholder{color:#888;}\n.novel-jump{color:#888;font-size:0.85em;}\n")
                .append("ruby rt{font-size:0.6em;}\n")
                .append("</style>\n</head>\n<body>\n<h1>").append(escapeHtml(title)).append("</h1>\n")
                .append(body)
                .append("</body>\n</html>\n");
        Files.writeString(file, html.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 把已落盘的内嵌图扩展名映射成 {@link NovelMarkupParser.ImageResolver}：
     * {@code [uploadedimage:id]} → 同目录下相对路径 {@code embed_{id}.{ext}}。
     * pixivimage 暂不支持下载，保持占位符。
     */
    private static NovelMarkupParser.ImageResolver localFolderResolver(Map<String, String> exts) {
        if (exts == null || exts.isEmpty()) return NovelMarkupParser.ImageResolver.NONE;
        return new NovelMarkupParser.ImageResolver() {
            @Override public String uploadedImage(String id) {
                String ext = exts.get(id);
                return ext == null ? null : "embed_" + id + "." + ext;
            }
            @Override public String pixivImage(String id) { return null; }
        };
    }

    private void ensureNotCancelled(NovelDownloadStatus status) {
        if (status != null && status.isCancelled()) {
            throw new CancellationException(messages.get("download.cancelled"));
        }
    }

    private void copyResponseBody(InputStream inputStream, Path target, NovelDownloadStatus status) throws IOException {
        copyResponseBody(inputStream, target, status, null);
    }

    /**
     * 拷贝响应体到目标文件；{@code onTotalBytesCopied} 在每个数据块写入后收到累计字节数，
     * 供封面下载的流式进度条使用。
     */
    private void copyResponseBody(InputStream inputStream, Path target, NovelDownloadStatus status,
                                  LongConsumer onTotalBytesCopied) throws IOException {
        try (InputStream in = inputStream;
             OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) != -1) {
                ensureNotCancelled(status);
                out.write(buffer, 0, read);
                total += read;
                if (onTotalBytesCopied != null) {
                    onTotalBytesCopied.accept(total);
                }
            }
        }
    }

    /**
     * 扫描 raw 中出现的 {@code [uploadedimage:id]}，逐张下载至 {@code {downloadPath}/embed_{id}.{ext}}，
     * 持久化映射到 {@code novel_images} 表。
     * Best-effort：单张失败不抛异常；URL 缺失或非 pximg.net 一律跳过。
     *
     * @return id → 实际落盘扩展名的映射（仅成功的条目）。
     */
    private Map<String, String> downloadEmbeddedImages(long novelId, String rawContent,
                                                       Map<String, String> urlMap,
                                                       Path downloadPath, String cookie,
                                                       NovelDownloadStatus status) {
        Set<String> ids = NovelMarkupParser.findUploadedImageIds(rawContent);
        if (ids.isEmpty() || urlMap == null || urlMap.isEmpty()) {
            // 没有占位符或者前端没传 URL（可能为公开 API 限制等），直接跳过
            return Map.of();
        }
        // 清掉历史记录，避免遗留旧 ext
        novelDatabase.clearNovelImages(novelId);
        // 实际会尝试下载的张数（有 URL 的占位符，受预算上限约束），用于进度展示
        int plannedTotal = 0;
        for (String id : ids) {
            String url = urlMap.get(id);
            if (url != null && !url.isBlank()) plannedTotal++;
        }
        plannedTotal = Math.min(plannedTotal, MAX_EMBEDDED_IMAGES_PER_NOVEL);
        if (status != null) {
            status.setStage("downloading-images");
            status.setEmbeddedTotal(plannedTotal);
            status.setEmbeddedDone(0);
        }
        Map<String, String> success = new LinkedHashMap<>();
        int budget = MAX_EMBEDDED_IMAGES_PER_NOVEL;
        for (String id : ids) {
            ensureNotCancelled(status);
            if (budget-- <= 0) {
                log.warn("novel embedded image budget exhausted: novelId={}", novelId);
                break;
            }
            String url = urlMap.get(id);
            if (url == null || url.isBlank()) continue;
            String ext = downloadOneEmbeddedImage(novelId, id, url, downloadPath, cookie, status);
            if (ext != null) success.put(id, ext);
            if (status != null) status.setEmbeddedDone(status.getEmbeddedDone() + 1);
        }
        if (!success.isEmpty()) {
            log.info("novel embedded images downloaded: novelId={}, count={}/{}", novelId, success.size(), ids.size());
        }
        return success;
    }

    private String downloadOneEmbeddedImage(long novelId, String imageId, String url,
                                            Path downloadPath, String cookie,
                                            NovelDownloadStatus status) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            log.warn("novel embed image skipped — malformed url: novelId={}, id={}, url={}", novelId, imageId, url);
            return null;
        }
        String host = uri.getHost();
        if (host == null || !host.endsWith(".pximg.net")) {
            log.warn("novel embed image skipped — host not pximg.net: novelId={}, id={}, host={}", novelId, imageId, host);
            return null;
        }
        String ext = inferImageExt(uri.getPath());
        Path target = downloadPath.resolve("embed_" + imageId + "." + ext);
        try {
            Boolean ok = downloadRestTemplate.execute(url, HttpMethod.GET,
                    request -> PixivRequestHeaders.applyImage(request.getHeaders(), cookie),
                    response -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return Boolean.FALSE;
                        }
                        copyResponseBody(response.getBody(), target, status);
                        return Boolean.TRUE;
                    });
            if (Boolean.TRUE.equals(ok)) {
                novelDatabase.saveNovelImage(novelId, imageId, ext);
                return ext;
            }
            log.warn("novel embed image non-2xx: novelId={}, id={}", novelId, imageId);
            return null;
        } catch (Exception e) {
            log.warn("novel embed image download failed: novelId={}, id={}, url={} — {}",
                    novelId, imageId, url, e.getMessage());
            return null;
        }
    }

    private static String inferImageExt(String path) {
        if (path == null) return "jpg";
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = last.lastIndexOf('.');
        if (dot < 0 || dot == last.length() - 1) return "jpg";
        String candidate = last.substring(dot + 1).toLowerCase(Locale.ROOT);
        int q = candidate.indexOf('?');
        if (q >= 0) candidate = candidate.substring(0, q);
        return IMAGE_EXT_WHITELIST.contains(candidate) ? candidate : "jpg";
    }

    private void writeEpub(Path file, long novelId, String title, NovelDownloadRequest.Other other,
                           String raw, Path downloadPath, String baseName, String coverExt,
                           Map<String, String> embeddedExts) throws IOException {
        // 内嵌图在 EPUB 内的相对路径：images/embed_{id}.{ext}（相对 OEBPS/chapter-n.xhtml）
        NovelMarkupParser.ImageResolver resolver = embeddedExts == null || embeddedExts.isEmpty()
                ? NovelMarkupParser.ImageResolver.NONE
                : new NovelMarkupParser.ImageResolver() {
            @Override public String uploadedImage(String id) {
                String ext = embeddedExts.get(id);
                return ext == null ? null : "images/embed_" + id + "." + ext;
            }
            @Override public String pixivImage(String id) { return null; }
        };
        // [chapter:] 拆成独立 spine 文件 + 单层目录
        List<NovelMarkupParser.Segment> segments = NovelMarkupParser.splitChapters(raw);
        List<NovelEpubWriter.Chapter> chapters = new java.util.ArrayList<>();
        List<NovelEpubWriter.NavEntry> nav = new java.util.ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            NovelMarkupParser.Segment seg = segments.get(i);
            String segTitle = (seg.title() != null && !seg.title().isBlank()) ? seg.title() : title;
            String body = NovelMarkupParser.render(
                    seg.raw(), NovelMarkupParser.Format.XHTML, resolver, imageLabels());
            chapters.add(new NovelEpubWriter.Chapter(segTitle, body));
            nav.add(new NovelEpubWriter.NavEntry(segTitle, i));
        }
        byte[] epub = NovelEpubWriter.write(title, other.getAuthorName(), other.getLanguage(),
                "urn:pixiv:novel:" + novelId, chapters, nav,
                readEmbeddedImages(downloadPath, embeddedExts),
                readCover(downloadPath, baseName, coverExt),
                buildNovelMetadata(novelId, other), epubLabels());
        Files.write(file, epub);
    }

    /** 单本小说的 OPF 元数据：简介、上传日期、标签、Pixiv 源链接、所属系列。 */
    private NovelEpubWriter.Metadata buildNovelMetadata(long novelId, NovelDownloadRequest.Other other) {
        String isoDate = null;
        if (other.getUploadTimestamp() != null) {
            isoDate = Instant.ofEpochMilli(EpochMillisNormalizer.normalize(other.getUploadTimestamp()))
                    .toString().replaceAll("\\.\\d+Z$", "Z");
        }
        List<String> subjects = other.getTags() == null ? List.of()
                : other.getTags().stream()
                        .map(WorkTag::name)
                        .filter(n -> n != null && !n.isBlank())
                        .toList();
        String source = "https://www.pixiv.net/novel/show.php?id=" + novelId;
        String collectionTitle = null;
        String collectionPosition = null;
        if (other.getSeriesId() != null && other.getSeriesId() > 0) {
            collectionTitle = (other.getSeriesTitle() != null && !other.getSeriesTitle().isBlank())
                    ? other.getSeriesTitle() : ("series-" + other.getSeriesId());
            if (other.getSeriesOrder() != null) {
                collectionPosition = String.valueOf(other.getSeriesOrder());
            }
        }
        return new NovelEpubWriter.Metadata(other.getDescription(), isoDate, subjects,
                source, collectionTitle, collectionPosition);
    }

    /**
     * 把已落盘的封面 {@code {baseName}_thumb.{ext}} 读回字节，供 {@link NovelEpubWriter} 内嵌进 EPUB。
     * Best-effort：封面缺失 / 读失败一律返回 null（EPUB 不带封面页，与下载未拿到封面时一致）。
     */
    private NovelEpubWriter.Cover readCover(Path downloadPath, String baseName, String coverExt) {
        if (coverExt == null || coverExt.isBlank()) return null;
        Path cover = downloadPath.resolve(baseName + "_thumb." + coverExt);
        try {
            return new NovelEpubWriter.Cover(coverExt, Files.readAllBytes(cover));
        } catch (IOException ex) {
            log.warn("epub cover read failed, skipped: {} — {}", cover, ex.getMessage());
            return null;
        }
    }

    /**
     * 把已落盘的 {@code embed_{id}.{ext}} 读回字节，供 {@link NovelEpubWriter} 内嵌进 EPUB。
     * Best-effort：单张读失败仅记日志并跳过（XHTML 端会回退到占位符）。
     */
    private List<NovelEpubWriter.ImageResource> readEmbeddedImages(Path downloadPath,
                                                                   Map<String, String> embeddedExts) {
        if (embeddedExts == null || embeddedExts.isEmpty()) return List.of();
        List<NovelEpubWriter.ImageResource> images = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : embeddedExts.entrySet()) {
            Path img = downloadPath.resolve("embed_" + e.getKey() + "." + e.getValue());
            try {
                images.add(new NovelEpubWriter.ImageResource(
                        e.getKey(), e.getValue(), Files.readAllBytes(img)));
            } catch (IOException ex) {
                log.warn("epub embed image read failed, skipped: {} — {}", img, ex.getMessage());
            }
        }
        return images;
    }

    private NovelMarkupParser.ImageLabels imageLabels() {
        return new NovelMarkupParser.ImageLabels() {
            @Override public String uploadedImage(String id) {
                return messages.get("novel.render.uploaded-image", id);
            }

            @Override public String pixivImage(String id) {
                return messages.get("novel.render.pixiv-image", id);
            }
        };
    }

    private NovelEpubWriter.Labels epubLabels() {
        return new NovelEpubWriter.Labels() {
            @Override public String untitled() {
                return messages.get("novel.epub.untitled");
            }

            @Override public String unknownAuthor() {
                return messages.get("novel.epub.unknown-author");
            }

            @Override public String chapter(int index) {
                return messages.get("novel.epub.chapter", index);
            }
        };
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
