package top.sywyar.pixivdownload.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.core.pixiv.PixivDescriptionHtml;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkAuthorLookup;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadCompletion;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadHistory;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadLookup;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadStatistics;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkSeriesObservation;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkSeriesObserver;
import top.sywyar.pixivdownload.core.collection.CollectionDownloadRootResolver;
import top.sywyar.pixivdownload.core.collection.WorkCollectionMembership;
import top.sywyar.pixivdownload.core.download.InteractiveDownloadExecutionLane;
import top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopControlCenterAvailability;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDashboardCardContribution;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDashboardSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDashboardSource;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopRunningTaskContribution;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiTone;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.runtime.download.queue.QueueStatusRetention;
import top.sywyar.pixivdownload.core.hash.ArtworkHashIndexMaintenance;
import top.sywyar.pixivdownload.core.pixiv.PixivBookmarkActions;
import top.sywyar.pixivdownload.core.pixiv.PixivImageDownloader;
import top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver;
import top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaService;
import top.sywyar.pixivdownload.core.time.EpochMillisNormalizer;
import top.sywyar.pixivdownload.core.work.WorkActionResult;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import top.sywyar.pixivdownload.core.work.service.AuthorObservationService;
import top.sywyar.pixivdownload.core.work.service.DownloadPathGuard;
import top.sywyar.pixivdownload.core.work.service.DownloadPathRejectedException;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataCapture;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.download.web.LocalizedException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 单作品下载引擎：图片 / 动图落盘、下载状态机、取消 / 清空、文件名规划、入库与下载后置动作
 * （收藏 / 收藏夹 / Hash / 系列 / 作者 / 前端转发 meta 旁路捕获）。{@link ArtworkDownloader} 的实现。
 */
@Slf4j
@Service
public class ArtworkDownloadExecutor implements ArtworkDownloader, DesktopDashboardSource {

    private static final URI DEFAULT_PIXIV_REFERER = URI.create("https://www.pixiv.net/");
    private static final String DASHBOARD_NAMESPACE = "batch";
    private static final int MAX_DASHBOARD_TEXT_CODE_POINTS = 512;

    private final DownloadSettings downloadSettings;
    private final ApplicationEventPublisher eventPublisher;
    private final ArtworkDownloadHistory artworkDownloadHistory;
    private final ArtworkDownloadLookup artworkDownloadLookup;
    private final ArtworkDownloadStatistics artworkDownloadStatistics;
    private final VisitorDownloadQuotaService visitorDownloadQuotaService;
    private final PixivImageDownloader pixivImageDownloader;
    private final TaskScheduler taskScheduler;
    private final InteractiveDownloadExecutionLane interactiveDownloadExecutionLane;
    private final PixivBookmarkActions pixivBookmarkActions;
    private final UgoiraService ugoiraService;
    private final AuthorObservationService authorObservationService;
    private final ArtworkAuthorLookup artworkAuthorLookup;
    private final DownloadPathGuard downloadPathGuard;
    private final CollectionDownloadRootResolver collectionDownloadRootResolver;
    private final WorkCollectionMembership workCollectionMembership;
    private final ArtworkSeriesObserver artworkSeriesObserver;
    private final ArtworkHashIndexMaintenance artworkHashIndexMaintenance;
    private final WorkMetadataCapture workMetadataCapture;
    private final MessageResolver messages;

    // 存储下载状态
    private final ConcurrentHashMap<String, DownloadStatus> downloadStatusMap = new ConcurrentHashMap<>();
    private final QueueTaskTracker taskTracker = new QueueTaskTracker("illust");

    public ArtworkDownloadExecutor(DownloadSettings downloadSettings,
                                   ApplicationEventPublisher eventPublisher,
                                   ArtworkDownloadHistory artworkDownloadHistory,
                                   ArtworkDownloadLookup artworkDownloadLookup,
                                   ArtworkDownloadStatistics artworkDownloadStatistics,
                                   @Nullable VisitorDownloadQuotaService visitorDownloadQuotaService,
                                   PixivImageDownloader pixivImageDownloader,
                                   @Qualifier("downloadWorkbenchTaskScheduler")
                                   TaskScheduler taskScheduler,
                                   InteractiveDownloadExecutionLane interactiveDownloadExecutionLane,
                                   PixivBookmarkActions pixivBookmarkActions,
                                   UgoiraService ugoiraService,
                                   AuthorObservationService authorObservationService,
                                   ArtworkAuthorLookup artworkAuthorLookup,
                                   DownloadPathGuard downloadPathGuard,
                                   CollectionDownloadRootResolver collectionDownloadRootResolver,
                                   WorkCollectionMembership workCollectionMembership,
                                   ArtworkSeriesObserver artworkSeriesObserver,
                                   ArtworkHashIndexMaintenance artworkHashIndexMaintenance,
                                   WorkMetadataCapture workMetadataCapture,
                                   MessageResolver messages) {
        this.downloadSettings = downloadSettings;
        this.eventPublisher = eventPublisher;
        this.artworkDownloadHistory = artworkDownloadHistory;
        this.artworkDownloadLookup = artworkDownloadLookup;
        this.artworkDownloadStatistics = artworkDownloadStatistics;
        this.visitorDownloadQuotaService = visitorDownloadQuotaService;
        this.pixivImageDownloader = pixivImageDownloader;
        this.taskScheduler = taskScheduler;
        this.interactiveDownloadExecutionLane = interactiveDownloadExecutionLane;
        this.pixivBookmarkActions = pixivBookmarkActions;
        this.ugoiraService = ugoiraService;
        this.authorObservationService = authorObservationService;
        this.artworkAuthorLookup = artworkAuthorLookup;
        this.downloadPathGuard = downloadPathGuard;
        this.collectionDownloadRootResolver = collectionDownloadRootResolver;
        this.workCollectionMembership = workCollectionMembership;
        this.artworkSeriesObserver = artworkSeriesObserver;
        this.artworkHashIndexMaintenance = artworkHashIndexMaintenance;
        this.workMetadataCapture = workMetadataCapture;
        this.messages = messages;
    }

    @Override
    public void downloadImages(Long artworkId, String title, List<String> imageUrls,
                               String referer, DownloadRequest.Other other, String cookie,
                               String userUuid) {
        QueueTaskTracker.Task task = taskTracker.prepareQueued(userUuid);
        task.bind(() -> downloadImagesTracked(task, artworkId, title, imageUrls,
                referer, other, cookie, userUuid));
        try {
            interactiveDownloadExecutionLane.execute(task);
        } catch (RuntimeException | Error failure) {
            task.rejectSubmission();
            throw failure;
        }
    }

    @Override
    public boolean downloadImagesBlocking(Long artworkId, String title, List<String> imageUrls,
                                          String referer, DownloadRequest.Other other, String cookie,
                                          String userUuid) {
        QueueTaskTracker.Task task = taskTracker.beginRunning(userUuid);
        try {
            return downloadImagesTracked(task, artworkId, title, imageUrls, referer, other, cookie, userUuid);
        } finally {
            task.completeRunning();
        }
    }

    private boolean downloadImagesTracked(QueueTaskTracker.Task task,
                                          Long artworkId, String title, List<String> imageUrls,
                                          String referer, DownloadRequest.Other other, String cookie,
                                          String userUuid) {
        boolean succeeded = false;
        if (other == null) {
            other = new DownloadRequest.Other();
        }
        // 初始化下载状态
        DownloadStatus status = new DownloadStatus(artworkId, title, imageUrls.size(), userUuid);
        String statusKey = statusKey(artworkId, userUuid);
        task.onCancellation(() -> cancelTrackedStatus(statusKey, status));
        if (!task.publishIfActive(() -> downloadStatusMap.put(statusKey, status))) {
            return false;
        }

        // 发送初始状态更新
        eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status, userUuid));

        try {
            ensureNotCancelled(status);
            FileNamePlan fileNamePlan = buildFileNamePlan(artworkId, title, imageUrls.size(), other);
            other.setFileNames(fileNamePlan.baseNames());
            String folderName = String.valueOf(artworkId);

            // 创建文件夹结构
            validateUserDownloadFolder(other);
            Path downloadRoot = resolveEffectiveDownloadRoot(other).toAbsolutePath().normalize();
            Path downloadPath = downloadRoot;
            if (other.isUserDownload() && other.getUsername() != null && !downloadSettings.isUserFlatFolder()) {
                downloadPath = downloadPath.resolve(requireSafeDirectoryName(other.getUsername()));

                if (other.getXRestrict() == 2) {
                    downloadPath = downloadPath.resolve("R18G");
                } else if (other.getXRestrict() == 1) {
                    downloadPath = downloadPath.resolve("R18");
                }
            }
            downloadPath = downloadPath.resolve(folderName).normalize();
            requireWithinDownloadRoot(downloadRoot, downloadPath);
            status.setFolderName(displayFolderName(downloadRoot, downloadPath));
            Files.createDirectories(downloadPath);
            status.setDownloadPath(downloadPath.toString());

            AtomicInteger successCount = new AtomicInteger(0);

            HashSet<String> fileExtensions = new HashSet<>();

            if (other.isUgoira() && other.getUgoiraZipUrl() != null) {
                // === 动图 (ugoira) 处理：委托给 UgoiraService ===
                fileExtensions.add("webp");
                status.setCurrentImageIndex(0);
                eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status, userUuid));

                Consumer<UgoiraProgress> progressListener = progress -> {
                    status.setUgoiraProgress(progress);
                    eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status, userUuid));
                };
                successCount.set(ugoiraService.processUgoira(
                        artworkId, other, downloadPath, referer, cookie, progressListener, status::isCancelled));

                status.setDownloadedCount(successCount.get());
                eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status, userUuid));

            } else {
                // === 普通图片下载 ===
                AtomicLong remainingImageBytes = new AtomicLong(PixivImageTransferObserver.MAX_TASK_BYTES);
                for (String url : imageUrls) validatePixivUrl(url);
                for (int i = 0; i < imageUrls.size(); i++) {
                    ensureNotCancelled(status);
                    if (remainingImageBytes.get() <= 0) {
                        break;
                    }

                    String imageUrl = imageUrls.get(i);

                    status.setCurrentImageIndex(i);
                    status.setDownloadedCount(successCount.get());
                    eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status, userUuid));

                    try {
                        Path fileStem = downloadPath.resolve(fileNamePlan.baseName(i));
                        int imageNumber = i + 1;
                        Consumer<ImageDownloadProgress> imageProgressListener = progress -> {
                            status.setImageProgress(progress);
                            eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status, userUuid));
                        };
                        String extension = downloadImage(imageUrl, fileStem, referer, cookie,
                                imageNumber, imageUrls.size(), imageProgressListener, status::isCancelled,
                                remainingImageBytes);
                        if (extension != null) {
                            fileExtensions.add(extension);
                            successCount.incrementAndGet();
                            status.setDownloadedCount(successCount.get());
                            log.info(logMessage("download.log.progress",
                                    id(artworkId), text(successCount.get()), text(imageUrls.size())));
                            eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status, userUuid));
                        }
                        if (other.getDelayMs() > 0) sleepCancellable(other.getDelayMs(), status::isCancelled);
                    } catch (Exception e) {
                        if (e instanceof CancellationException) {
                            throw e;
                        }
                        log.error(logMessage("download.log.image.failed", imageUrl, e.getMessage()));
                    }
                }
            }

            ensureNotCancelled(status);

            // 多人模式：记录已下载的文件夹（用于配额超出时打包）；部分失败时已落盘的文件同样要纳入打包/清理
            if (userUuid != null && visitorDownloadQuotaService != null && successCount.get() > 0) {
                visitorDownloadQuotaService.recordFolder(userUuid, downloadPath);
            }

            int expectedCount = other.isUgoira() && other.getUgoiraZipUrl() != null ? 1 : imageUrls.size();
            if (successCount.get() < expectedCount) {
                // 有图片未下载成功时绝不写下载历史：一旦入库就会被「跳过已下载」判重挡住，缺页再也补不齐
                status.setSuccessCount(successCount.get());
                status.setFailedCount(expectedCount - successCount.get());
                status.setCurrentImageIndex(-1);
                status.setCompleted(true);
                status.setFailed(true);
                status.setErrorMessage(messages.get("download.incomplete",
                        text(successCount.get()), text(expectedCount)));
                status.setEndTime(java.time.LocalDateTime.now());
                log.warn(logMessage("download.log.incomplete",
                        id(artworkId), text(successCount.get()), text(expectedCount), downloadPath));
                eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status, userUuid));
                return false;
            }

            // 记录下载信息
            recordDownload(artworkId, title, status.getDownloadPath(), fileExtensions,
                    successCount.get(), other.getXRestrict(), other.isAi(), other.getAuthorId(), other.getDescription(), other.getTags(),
                    fileNamePlan.template(), fileNamePlan.recordTime(), fileNamePlan.normalizedAuthorName(),
                    other.getSeriesId(), other.getSeriesOrder());

            recordDownloadStatistics(successCount.get());
            recordAuthorInfo(artworkId, other, cookie);
            recordSeriesInfo(artworkId, other, cookie);

            status.setSuccessCount(successCount.get());
            status.setFailedCount(imageUrls.size() - successCount.get());
            status.setCurrentImageIndex(-1); // 完成后重置索引

            log.info(logMessage("download.log.completed",
                    id(artworkId), text(successCount.get()), text(imageUrls.size()), downloadPath));

            // 下载后收藏（可选，best-effort）
            if (other.isBookmark()) {
                status.setBookmarkResult(pixivBookmarkActions.bookmarkArtwork(artworkId, cookie));
            }

            // 下载后加入收藏夹（可选，best-effort）
            if (other.getCollectionId() != null) {
                try {
                    boolean added = workCollectionMembership.addWork(
                            WorkType.ARTWORK, other.getCollectionId(), artworkId);
                    status.setCollectionResult(added
                            ? WorkActionResult.success(messages.get("collection.result.added"))
                            : WorkActionResult.exists(messages.get("collection.result.exists")));
                } catch (Exception e) {
                    log.warn(logMessage("download.log.collection.add.failed", artworkId, other.getCollectionId(), e.getMessage()), e);
                    status.setCollectionResult(WorkActionResult.failed(messages.get("collection.result.failed")));
                }
            }

            try {
                artworkHashIndexMaintenance.rebuildArtwork(artworkId);
            } catch (Exception e) {
                log.warn(logMessage("core.hash.log.artwork-failed", artworkId, e.getMessage()), e);
            }

            // 更新下载状态为完成。放在后置动作之后，确保最终事件包含收藏/收藏夹结果。
            status.setCompleted(true);

            // 发送最终完成状态更新
            eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status, userUuid));
            succeeded = true;

            // 前端转发的原始 meta（若有）：下载成功、作品行已落库后旁路归一化为 sidecar + 列投影。
            // 零额外请求、best-effort，绝不反报已成功的下载。
            captureForwardedMeta(artworkId, other);

        } catch (CancellationException e) {
            status.setCancelled(true);
            status.setCompleted(true);
            status.setFailed(false);
            status.setEndTime(java.time.LocalDateTime.now());
            status.setErrorMessage(messages.get("download.cancelled"));
            eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status, userUuid));
        } catch (Exception e) {
            log.error(logMessage("download.log.failed"), e);
            status.setCompleted(true);
            status.setFailed(true);
            status.setErrorMessage(resolveStatusErrorMessage(e));
            status.setEndTime(java.time.LocalDateTime.now());
            eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status, userUuid));
        } finally {
            // 下载完成后保留状态 5 分钟；清理句柄也属于本 queue generation，热停时会被取消并移除。
            if (downloadStatusMap.get(statusKey) == status) {
                QueueStatusRetention.schedule(taskTracker, userUuid, taskScheduler,
                        Instant.now().plusSeconds(300),
                        () -> downloadStatusMap.remove(statusKey, status));
            }
        }
        return succeeded;
    }

    private Path resolveEffectiveDownloadRoot(DownloadRequest.Other other) {
        Path defaultRoot = Paths.get(downloadSettings.getRootFolder());
        if (other != null && other.getCollectionId() != null) {
            return collectionDownloadRootResolver.resolveDownloadRoot(other.getCollectionId(), defaultRoot);
        }
        return defaultRoot;
    }

    public void validateUserDownloadFolder(DownloadRequest.Other other) {
        if (other != null && other.isUserDownload() && other.getUsername() != null) {
            requireSafeDirectoryName(other.getUsername());
        }
    }

    private String requireSafeDirectoryName(String value) {
        try {
            return downloadPathGuard.requireSafeDirectoryName(value);
        } catch (DownloadPathRejectedException rejected) {
            throw LocalizedException.badRequest(
                    "download.path.segment.invalid",
                    "Unsafe download subdirectory: {0}",
                    value
            );
        }
    }

    private void requireWithinDownloadRoot(Path downloadRoot, Path downloadPath) {
        try {
            downloadPathGuard.requireWithinRoot(downloadRoot, downloadPath);
        } catch (DownloadPathRejectedException rejected) {
            throw LocalizedException.badRequest(
                    "download.path.segment.invalid",
                    "Unsafe download subdirectory: {0}",
                    downloadPath
            );
        }
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

    private String downloadImage(String imageUrl, Path fileStem, String referer, String cookie,
                                 int imageNumber, int totalImages,
                                 Consumer<ImageDownloadProgress> progressListener,
                                 BooleanSupplier cancellationRequested,
                                 AtomicLong remainingImageBytes) {
        int maxRetries = 3;
        int retryCount = 0;
        while (retryCount < maxRetries) {
            ensureNotCancelled(cancellationRequested);
            long maximumBytes = Math.min(
                    PixivImageTransferObserver.MAX_IMAGE_BYTES, remainingImageBytes.get());
            if (maximumBytes <= 0) {
                break;
            }
            long[] downloadedBytes = {0L};
            try {
                long[] totalBytes = {0L};
                int[] lastProgress = {-1};
                long[] lastBytes = {0L};
                long[] lastAt = {0L};
                URI refererUri = StringUtils.hasText(referer)
                        ? URI.create(referer)
                        : DEFAULT_PIXIV_REFERER;
                String extension = pixivImageDownloader.downloadImage(
                        URI.create(imageUrl), refererUri, fileStem, cookie,
                            new PixivImageTransferObserver() {
                                @Override
                                public long maximumBytes() {
                                    return maximumBytes;
                                }

                                @Override
                                public void checkCancelled() {
                                    ensureNotCancelled(cancellationRequested);
                                }

                                @Override
                                public void onContentLength(long contentLength) {
                                    totalBytes[0] = contentLength;
                                    lastProgress[0] = contentLength > 0 ? 0 : -1;
                                    lastAt[0] = System.currentTimeMillis();
                                    publishImageProgress(progressListener, ImageDownloadProgress.builder()
                                            .status(ImageDownloadProgress.STATUS_RUNNING)
                                            .imageNumber(imageNumber)
                                            .totalImages(totalImages)
                                            .downloadedBytes(0L)
                                            .totalBytes(contentLength > 0 ? contentLength : null)
                                            .progress(contentLength > 0 ? 0 : null)
                                            .build());
                                }

                                @Override
                                public void onBytesTransferred(long transferredBytes) {
                                    downloadedBytes[0] = transferredBytes;
                                    Integer progress = totalBytes[0] > 0
                                            ? Math.min(99, (int) (transferredBytes * 100 / totalBytes[0]))
                                            : null;
                                    if (shouldEmitImageByteProgress(
                                            progress, transferredBytes, lastProgress, lastBytes, lastAt)) {
                                        publishImageProgress(progressListener, ImageDownloadProgress.builder()
                                                .status(ImageDownloadProgress.STATUS_RUNNING)
                                                .imageNumber(imageNumber)
                                                .totalImages(totalImages)
                                                .downloadedBytes(transferredBytes)
                                                .totalBytes(totalBytes[0] > 0 ? totalBytes[0] : null)
                                                .progress(progress)
                                                .build());
                                    }
                                }
                        });
                if (extension != null) {
                    if (imageNumber < totalImages) {
                        publishImageProgress(progressListener, ImageDownloadProgress.builder()
                                .status(ImageDownloadProgress.STATUS_RUNNING)
                                .imageNumber(imageNumber + 1)
                                .totalImages(totalImages)
                                .downloadedBytes(0L)
                                .progress(0)
                                .build());
                    } else {
                        publishImageProgress(progressListener, ImageDownloadProgress.builder()
                                .status(ImageDownloadProgress.STATUS_COMPLETED)
                                .imageNumber(imageNumber)
                                .totalImages(totalImages)
                                .downloadedBytes(downloadedBytes[0])
                                .totalBytes(totalBytes[0] > 0 ? totalBytes[0] : null)
                                .progress(100)
                                .build());
                    }
                    return extension;
                }
                retryCount++;
                logAndBackoffBeforeRetry(
                        imageUrl,
                        messages.get("download.image.transfer-unsuccessful"),
                        retryCount,
                        maxRetries,
                        cancellationRequested);
            } catch (CancellationException e) {
                throw e;
            } catch (Exception e) {
                retryCount++;
                logAndBackoffBeforeRetry(
                        imageUrl, e.getMessage(), retryCount, maxRetries, cancellationRequested);
            } finally {
                remainingImageBytes.updateAndGet(
                        remaining -> Math.max(0L, remaining - downloadedBytes[0]));
            }
        }
        publishImageProgress(progressListener, ImageDownloadProgress.builder()
                .status(ImageDownloadProgress.STATUS_FAILED)
                .imageNumber(imageNumber)
                .totalImages(totalImages)
                .build());
        return null;
    }

    private void logAndBackoffBeforeRetry(
            String imageUrl,
            String reason,
            int retryCount,
            int maxRetries,
            BooleanSupplier cancellationRequested) {
        log.error(logMessage("download.log.retry", imageUrl, reason, retryCount, maxRetries));
        if (retryCount < maxRetries) {
            sleepCancellable(2000L * retryCount, cancellationRequested);
        } else {
            log.error(logMessage("download.log.retry.exhausted", imageUrl));
        }
    }

    private void publishImageProgress(Consumer<ImageDownloadProgress> progressListener, ImageDownloadProgress progress) {
        if (progressListener != null) {
            progressListener.accept(progress);
        }
    }

    private void ensureNotCancelled(DownloadStatus status) {
        if (status != null && status.isCancelled()) {
            throw new CancellationException(messages.get("download.cancelled"));
        }
    }

    private void ensureNotCancelled(BooleanSupplier cancellationRequested) {
        if (cancellationRequested != null && cancellationRequested.getAsBoolean()) {
            throw new CancellationException(messages.get("download.cancelled"));
        }
    }

    private void sleepCancellable(long millis, BooleanSupplier cancellationRequested) {
        long deadline = System.currentTimeMillis() + Math.max(0L, millis);
        while (true) {
            ensureNotCancelled(cancellationRequested);
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.min(remaining, 200L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException(messages.get("download.cancelled"));
            }
        }
    }

    private boolean shouldEmitImageByteProgress(Integer progress, long bytes,
                                                int[] lastProgress, long[] lastBytes, long[] lastAt) {
        long now = System.currentTimeMillis();
        int currentProgress = progress == null ? -1 : progress;
        if (currentProgress != lastProgress[0]
                || bytes - lastBytes[0] >= 512 * 1024
                || now - lastAt[0] >= 1000) {
            lastProgress[0] = currentProgress;
            lastBytes[0] = bytes;
            lastAt[0] = now;
            return true;
        }
        return false;
    }

    // 获取下载状态
    public DownloadStatus getDownloadStatus(Long artworkId) {
        return findAnyStatus(artworkId);
    }

    public DownloadStatus getDownloadStatus(Long artworkId, String ownerUuid, boolean admin) {
        if (admin) {
            return findAnyStatus(artworkId);
        }
        return downloadStatusMap.get(statusKey(artworkId, ownerUuid));
    }

    public List<Long> getDownloadStatus() {
        Set<Long> downloadStatus = new LinkedHashSet<>();
        downloadStatusMap.forEach(10, (key, status) -> downloadStatus.add(status.getArtworkId()));
        return new LinkedList<>(downloadStatus);
    }

    public List<Long> getDownloadStatus(String ownerUuid, boolean admin) {
        if (admin) {
            return getDownloadStatus();
        }
        Set<Long> downloadStatus = new LinkedHashSet<>();
        downloadStatusMap.forEach(10, (key, status) -> {
            if (canAccessStatus(status, ownerUuid, false)) {
                downloadStatus.add(status.getArtworkId());
            }
        });
        return new LinkedList<>(downloadStatus);
    }

    /**
     * 返回下载工作台当前可证明的队列卡片与运行任务纯值，不暴露 owner、路径或可执行句柄。
     * 已结束状态仍按既有保留窗口留在查询 map 中，但不再列为运行任务。
     *
     * @return 当前桌面首页只读快照
     */
    @Override
    public DesktopDashboardSnapshot snapshot() {
        Instant observedAt = Instant.now();
        QueueTaskTracker.Snapshot queue = taskTracker.snapshot();
        List<DesktopDashboardCardContribution> cards = List.of(
                unavailableCard("today-downloads", 10,
                        text("desktop.control-center.card.today-downloads", "Today's downloads"),
                        DesktopUiIcon.DOWNLOAD, observedAt),
                new DesktopDashboardCardContribution(
                        "waiting-queue",
                        20,
                        text("desktop.control-center.card.waiting-queue", "Waiting queue"),
                        DesktopUiText.raw(Integer.toString(queue.queued())),
                        queue.accepting()
                                ? text("desktop.control-center.card.queue.accepting",
                                "{0} queued, {1} running",
                                Integer.toString(queue.queued()), Integer.toString(queue.running()))
                                : text("desktop.control-center.card.queue.quiesced",
                                "{0} queued, {1} running; intake stopped",
                                Integer.toString(queue.queued()), Integer.toString(queue.running())),
                        DesktopUiTone.INFO,
                        DesktopUiIcon.QUEUE,
                        DesktopControlCenterAvailability.AVAILABLE,
                        observedAt),
                unavailableCard("success-rate", 30,
                        text("desktop.control-center.card.success-rate", "Success rate"),
                        DesktopUiIcon.SUCCESS, observedAt));

        List<DesktopRunningTaskContribution> runningTasks = new ArrayList<>();
        for (Map.Entry<String, DownloadStatus> entry : List.copyOf(downloadStatusMap.entrySet())) {
            DesktopRunningTaskContribution task = runningTask(entry.getKey(), entry.getValue(), observedAt);
            if (task != null) {
                runningTasks.add(task);
            }
        }
        return new DesktopDashboardSnapshot(cards, runningTasks, observedAt);
    }

    private static DesktopDashboardCardContribution unavailableCard(
            String cardId,
            int order,
            DesktopUiText title,
            DesktopUiIcon icon,
            Instant observedAt) {
        return new DesktopDashboardCardContribution(
                cardId,
                order,
                title,
                DesktopUiText.raw("—"),
                text("desktop.control-center.card.unavailable", "Reliable statistics unavailable"),
                DesktopUiTone.DEFAULT,
                icon,
                DesktopControlCenterAvailability.UNAVAILABLE,
                observedAt);
    }

    private static DesktopRunningTaskContribution runningTask(
            String statusKey,
            DownloadStatus status,
            Instant observedAt) {
        if (status == null || status.isCompleted() || status.isFailed() || status.isCancelled()) {
            return null;
        }
        int total = Math.max(0, status.getTotalImages());
        int downloaded = Math.max(0, Math.min(status.getDownloadedCount(), total));
        boolean started = status.getCurrentImageIndex() >= 0;
        DesktopRunningTaskContribution.Status taskStatus = !started
                ? DesktopRunningTaskContribution.Status.PREPARING
                : total > 0 && downloaded >= total
                ? DesktopRunningTaskContribution.Status.FINALIZING
                : DesktopRunningTaskContribution.Status.RUNNING;
        DesktopUiText supportingText = !started
                ? text("desktop.control-center.task.preparing", "Preparing download")
                : total > 0
                ? text("desktop.control-center.task.progress", "{0} of {1} images downloaded",
                Integer.toString(downloaded), Integer.toString(total))
                : text("desktop.control-center.task.downloading", "Downloading");
        String title = boundedDashboardText(status.getTitle());
        DesktopUiText titleToken = title.isBlank()
                ? text("desktop.control-center.task.artwork", "Artwork {0}",
                String.valueOf(status.getArtworkId()))
                : DesktopUiText.raw(title);
        return new DesktopRunningTaskContribution(
                "illust:" + UUID.nameUUIDFromBytes(statusKey.getBytes(StandardCharsets.UTF_8)),
                0,
                titleToken,
                supportingText,
                taskStatus,
                total > 0 ? (double) downloaded / total : null,
                DesktopControlCenterAvailability.AVAILABLE,
                observedAt);
    }

    private static DesktopUiText text(String key, String fallback, String... arguments) {
        return new DesktopUiText(DASHBOARD_NAMESPACE, key, fallback, List.of(arguments));
    }

    private static String boundedDashboardText(String value) {
        if (value == null) {
            return "";
        }
        int codePoints = value.codePointCount(0, value.length());
        int end = value.offsetByCodePoints(0, Math.min(codePoints, MAX_DASHBOARD_TEXT_CODE_POINTS));
        return value.substring(0, end);
    }

    /**
     * 取消该 artworkId 的所有下载（admin / solo 路径）。
     * multi 模式下若两个用户并发下载同一作品，此调用会同时取消双方任务；普通用户取消请走带 ownerUuid 的重载。
     */
    public void cancelDownload(Long artworkId) {
        downloadStatusMap.forEach(10, (key, status) -> {
            if (Objects.equals(status.getArtworkId(), artworkId)) {
                status.setCancelled(true);
            }
        });
    }

    public void cancelDownload(Long artworkId, String ownerUuid, boolean admin) {
        if (admin) {
            cancelDownload(artworkId);
            return;
        }
        DownloadStatus status = getDownloadStatus(artworkId, ownerUuid, admin);
        if (status != null) {
            status.setCancelled(true);
        }
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

    public int forceClearDownloadsForOwner(@Nullable String ownerUuid) {
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
                    status -> Objects.equals(status.getOwnerUuid(), ownerUuid));
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

    private int forceClearDownloads(java.util.function.Predicate<DownloadStatus> matcher) {
        AtomicInteger cleared = new AtomicInteger();
        Throwable failure = null;
        for (var entry : List.copyOf(downloadStatusMap.entrySet())) {
            String key = entry.getKey();
            DownloadStatus status = entry.getValue();
            try {
                if (status == null || !matcher.test(status)) {
                    continue;
                }
                status.setCancelled(true);
                status.setCompleted(true);
                status.setFailed(false);
                status.setEndTime(java.time.LocalDateTime.now());
                status.setErrorMessage(messages.get("download.cancelled"));
                if (downloadStatusMap.remove(key, status)) {
                    cleared.incrementAndGet();
                }
                eventPublisher.publishEvent(new DownloadProgressEvent(this, status.getArtworkId(), status, status.getOwnerUuid()));
            } catch (Throwable error) {
                failure = mergeFailure(failure, error);
            }
        }
        rethrow(failure);
        return cleared.get();
    }

    private void cancelTrackedStatus(String statusKey, DownloadStatus status) {
        status.setCancelled(true);
        status.setCompleted(true);
        status.setFailed(false);
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

    private boolean canAccessStatus(DownloadStatus status, String ownerUuid, boolean admin) {
        if (status == null) {
            return false;
        }
        if (admin) {
            return true;
        }
        return status.getOwnerUuid() != null && status.getOwnerUuid().equals(ownerUuid);
    }

    /**
     * 在所有 owner 中查找首个匹配 artworkId 的下载状态。
     * 仅用于 admin / solo 路径——当 multi 模式下两个用户并发下载同一作品时，返回值是任意一方，不保证稳定性。
     */
    private DownloadStatus findAnyStatus(Long artworkId) {
        if (artworkId == null) {
            return null;
        }
        for (DownloadStatus status : downloadStatusMap.values()) {
            if (Objects.equals(status.getArtworkId(), artworkId)) {
                return status;
            }
        }
        return null;
    }

    private String statusKey(Long artworkId, String ownerUuid) {
        return (ownerUuid == null ? "admin" : ownerUuid) + ":" + artworkId;
    }

    private FileNamePlan buildFileNamePlan(Long artworkId, String title, int count, DownloadRequest.Other other) {
        String template = PixivWorkFileNameFormatter.normalizeTemplate(other.getFileNameTemplate());
        long preferredTime = EpochMillisNormalizer.normalize(other.getFileNameTimestamp());
        long recordTime = artworkDownloadHistory.allocateRecordTime(preferredTime);
        String sanitizedAuthorName = PixivWorkFileNameFormatter.sanitize(other.getAuthorName());
        List<String> computed = PixivWorkFileNameFormatter.formatAll(
                template,
                artworkId,
                title,
                other.getAuthorId(),
                other.getAuthorName(),
                recordTime,
                count,
                other.isAi(),
                other.getXRestrict()
        );
        List<String> provided = PixivWorkFileNameFormatter.normalizeProvidedBaseNames(other.getFileNames(), count, artworkId);
        if (!provided.isEmpty() && !provided.equals(computed)) {
            log.debug(logMessage("download.log.filename-mismatch", artworkId));
        }
        return new FileNamePlan(
                template,
                recordTime,
                sanitizedAuthorName.isEmpty() ? null : sanitizedAuthorName,
                provided.equals(computed) ? provided : computed);
    }

    private void recordDownload(Long artworkId, String title, String folderPath, HashSet<String> fileExtensions,
                                int count, int xRestrict, boolean isAi, Long authorId, String description, List<WorkTag> tags,
                                String fileNameTemplate, long recordTime, String normalizedAuthorName,
                                Long seriesId, Long seriesOrder) {
        artworkDownloadHistory.record(new ArtworkDownloadCompletion(
                artworkId,
                title,
                Path.of(folderPath).toAbsolutePath(),
                count,
                fileExtensions,
                recordTime,
                xRestrict,
                isAi,
                authorId,
                PixivDescriptionHtml.normalizeLinks(description),
                fileNameTemplate,
                normalizedAuthorName,
                seriesId,
                seriesOrder,
                tags
        ));
    }

    private void recordDownloadStatistics(int imageCount) {
        try {
            artworkDownloadStatistics.recordCompleted(imageCount);
        } catch (Exception e) {
            log.warn(logMessage("download.log.statistics.failed", e.getMessage()), e);
        }
    }

    private record FileNamePlan(
            String template,
            long recordTime,
            String normalizedAuthorName,
            List<String> baseNames) {
        String baseName(int page) {
            if (page >= 0 && page < baseNames.size()) {
                return baseNames.get(page);
            }
            return "page_" + Math.max(page, 0);
        }
    }

    /**
     * 前端转发的原始 meta（{@code other.rawMetaJson}）落地：交由 {@link WorkMetadataCapture} 解析 + 归一化。
     * 仅前端交互下载链路填充该字段（计划任务走后端自抓 body，不填）。捕获已是下载成功后的旁路动作，
     * 全程 best-effort、warn-continue——任何异常都不得反报已成功的下载（沿一致性模型，由下次下载或历史回填自愈）。
     */
    private void captureForwardedMeta(Long artworkId, DownloadRequest.Other other) {
        if (other == null || !StringUtils.hasText(other.getRawMetaJson())) {
            return;
        }
        try {
            workMetadataCapture.captureForwarded(WorkType.ARTWORK, artworkId, other.getRawMetaJson());
        } catch (RuntimeException e) {
            log.warn("Failed to capture forwarded meta for {}: {}", artworkId, e.getMessage());
        }
    }

    private void recordAuthorInfo(Long artworkId, DownloadRequest.Other other, String cookie) {
        try {
            if (other != null && other.getAuthorId() != null) {
                authorObservationService.observe(other.getAuthorId(), other.getAuthorName());
                return;
            }
            artworkAuthorLookup.resolveMissing(artworkId, cookie);
        } catch (Exception e) {
            log.warn(logMessage("download.log.record-author.failed", id(artworkId)), e);
        }
    }

    private void recordSeriesInfo(Long artworkId, DownloadRequest.Other other, String cookie) {
        try {
            // Pixiv 系列几乎只挂在漫画 (illustType == 1) 上。当前端已经知道作品类型时，
            // 避免对插画/动图也发一次 /ajax/illust/{id} —— 批量下载时 N 张作品 = N 次额外请求很容易被限流。
            // illustType 为空（旧前端 / 未传）才回退到代理查询。
            Integer illustType = other == null ? null : other.getIllustType();
            artworkSeriesObserver.observe(new ArtworkSeriesObservation(
                    artworkId,
                    illustType == null || illustType == 1,
                    other == null ? null : other.getSeriesId(),
                    other == null ? null : other.getSeriesTitle(),
                    other == null ? null : other.getAuthorId(),
                    other == null ? null : other.getSeriesDescription(),
                    other == null ? null : other.getSeriesCoverUrl()
            ), cookie);
        } catch (Exception e) {
            log.warn(logMessage("download.log.record-series.failed", id(artworkId)), e);
        }
    }

    @Override
    public boolean isArtworkDownloaded(long artworkId, boolean verifyFiles) {
        return artworkDownloadLookup.isDownloaded(artworkId, verifyFiles);
    }

    /**
     * SSRF 防护：仅允许向 Pixiv 图床（*.pximg.net）发起 HTTPS 请求。
     * 防止攻击者利用下载接口探测内网或访问任意 URL。
     * public static 供 Controller 在同步阶段提前校验，Service 内保留为纵深防御。
     */
    public static void validatePixivUrl(String url) {
        if (url == null || url.isBlank()) return;
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(scheme)) {
                throw LocalizedException.badRequest(
                        "download.url.https-only",
                        "只允许 HTTPS 协议的下载 URL: {0}",
                        url
                );
            }
            if (host == null || !host.endsWith(".pximg.net")) {
                throw LocalizedException.badRequest(
                        "download.url.host.not-allowed",
                        "下载 URL 的域名不在白名单内: {0}",
                        host
                );
            }
        } catch (URISyntaxException e) {
            throw LocalizedException.badRequest(
                    "download.url.invalid",
                    "无效的下载 URL: {0}",
                    url
            );
        }
    }

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }

    private String id(Long value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private String text(int value) {
        return String.valueOf(value);
    }

    private String resolveStatusErrorMessage(Exception error) {
        if (error instanceof LocalizedException localized) {
            return messages.getOrDefault(
                    Locale.getDefault(),
                    localized.messageCode(),
                    localized.defaultMessage(),
                    localized.messageArgs()
            );
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return messages.get(Locale.getDefault(), "error.unexpected");
        }
        return message;
    }
}
