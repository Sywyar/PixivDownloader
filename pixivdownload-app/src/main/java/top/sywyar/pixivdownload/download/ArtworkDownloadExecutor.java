package top.sywyar.pixivdownload.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.common.PixivDescriptionHtml;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;
import top.sywyar.pixivdownload.common.SafePathSegment;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.ArtworkFileNameFormatter;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.core.hash.ArtworkHashService;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;
import top.sywyar.pixivdownload.core.pixiv.PixivBookmarkService;
import top.sywyar.pixivdownload.core.work.WorkActionResult;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.series.MangaSeriesService;
import top.sywyar.pixivdownload.util.TimestampUtils;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 单作品下载引擎：图片 / 动图落盘、下载状态机、取消 / 清空、文件名规划、入库与下载后置动作
 * （收藏 / 收藏夹 / Hash / 系列 / 作者 / 前端转发 meta 旁路捕获）。{@link ArtworkDownloader} 的实现。
 */
@Slf4j
@Service
public class ArtworkDownloadExecutor implements ArtworkDownloader {

    private final DownloadConfig downloadConfig;
    private final ApplicationEventPublisher eventPublisher;
    private final PixivDatabase pixivDatabase;
    private final UserQuotaService userQuotaService;
    private final RestTemplate downloadRestTemplate;
    private final TaskScheduler taskScheduler;
    private final PixivBookmarkService pixivBookmarkService;
    private final UgoiraService ugoiraService;
    private final AuthorService authorService;
    private final CollectionService collectionService;
    private final MangaSeriesService mangaSeriesService;
    private final ArtworkHashService artworkHashService;
    private final WorkMetaCaptureService workMetaCaptureService;
    private final DownloadStatisticsService downloadStatisticsService;
    private final DownloadedArtworkService downloadedArtworkService;
    private final AppMessages messages;

    // 存储下载状态
    private final ConcurrentHashMap<String, DownloadStatus> downloadStatusMap = new ConcurrentHashMap<>();

    public ArtworkDownloadExecutor(DownloadConfig downloadConfig,
                                   ApplicationEventPublisher eventPublisher,
                                   PixivDatabase pixivDatabase,
                                   @Nullable UserQuotaService userQuotaService,
                                   @Qualifier("downloadRestTemplate") RestTemplate downloadRestTemplate,
                                   @Qualifier("taskScheduler") TaskScheduler taskScheduler,
                                   PixivBookmarkService pixivBookmarkService,
                                   UgoiraService ugoiraService,
                                   AuthorService authorService,
                                   CollectionService collectionService,
                                   MangaSeriesService mangaSeriesService,
                                   ArtworkHashService artworkHashService,
                                   WorkMetaCaptureService workMetaCaptureService,
                                   DownloadStatisticsService downloadStatisticsService,
                                   DownloadedArtworkService downloadedArtworkService,
                                   AppMessages messages) {
        this.downloadConfig = downloadConfig;
        this.eventPublisher = eventPublisher;
        this.pixivDatabase = pixivDatabase;
        this.userQuotaService = userQuotaService;
        this.downloadRestTemplate = downloadRestTemplate;
        this.taskScheduler = taskScheduler;
        this.pixivBookmarkService = pixivBookmarkService;
        this.ugoiraService = ugoiraService;
        this.authorService = authorService;
        this.collectionService = collectionService;
        this.mangaSeriesService = mangaSeriesService;
        this.artworkHashService = artworkHashService;
        this.workMetaCaptureService = workMetaCaptureService;
        this.downloadStatisticsService = downloadStatisticsService;
        this.downloadedArtworkService = downloadedArtworkService;
        this.messages = messages;
    }

    @Override
    @Async("downloadTaskExecutor")
    public void downloadImages(Long artworkId, String title, List<String> imageUrls,
                               String referer, DownloadRequest.Other other, String cookie,
                               String userUuid) {
        downloadImagesBlocking(artworkId, title, imageUrls, referer, other, cookie, userUuid);
    }

    @Override
    public boolean downloadImagesBlocking(Long artworkId, String title, List<String> imageUrls,
                                          String referer, DownloadRequest.Other other, String cookie,
                                          String userUuid) {
        boolean succeeded = false;
        if (other == null) {
            other = new DownloadRequest.Other();
        }
        // 初始化下载状态
        DownloadStatus status = new DownloadStatus(artworkId, title, imageUrls.size(), userUuid);
        String statusKey = statusKey(artworkId, userUuid);
        downloadStatusMap.put(statusKey, status);

        // 发送初始状态更新
        eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status, userUuid));

        try {
            FileNamePlan fileNamePlan = buildFileNamePlan(artworkId, title, imageUrls.size(), other);
            other.setFileNames(fileNamePlan.baseNames());
            String folderName = String.valueOf(artworkId);

            // 创建文件夹结构
            validateUserDownloadFolder(other);
            Path downloadRoot = resolveEffectiveDownloadRoot(other).toAbsolutePath().normalize();
            Path downloadPath = downloadRoot;
            if (other.isUserDownload() && other.getUsername() != null && !downloadConfig.isUserFlatFolder()) {
                downloadPath = downloadPath.resolve(SafePathSegment.requireSafeDirectoryName(other.getUsername()));

                if (other.getXRestrict() == 2) {
                    downloadPath = downloadPath.resolve("R18G");
                } else if (other.getXRestrict() == 1) {
                    downloadPath = downloadPath.resolve("R18");
                }
            }
            downloadPath = downloadPath.resolve(folderName).normalize();
            ensureWithinDownloadRoot(downloadRoot, downloadPath);
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
                for (String url : imageUrls) validatePixivUrl(url);
                for (int i = 0; i < imageUrls.size(); i++) {
                    ensureNotCancelled(status);

                    String imageUrl = imageUrls.get(i);

                    status.setCurrentImageIndex(i);
                    status.setDownloadedCount(successCount.get());
                    eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status, userUuid));

                    try {
                        String extension = getFileExtension(imageUrl);
                        fileExtensions.add(extension);
                        String filename = fileNamePlan.baseName(i) + "." + extension;
                        Path filePath = downloadPath.resolve(filename);
                        int imageNumber = i + 1;
                        Consumer<ImageDownloadProgress> imageProgressListener = progress -> {
                            status.setImageProgress(progress);
                            eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status, userUuid));
                        };
                        if (downloadImage(imageUrl, filePath, referer, cookie,
                                imageNumber, imageUrls.size(), imageProgressListener, status::isCancelled)) {
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
            if (userUuid != null && userQuotaService != null && successCount.get() > 0) {
                userQuotaService.recordFolder(userUuid, downloadPath);
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
                    fileNamePlan.templateId(), fileNamePlan.recordTime(), fileNamePlan.fileAuthorNameId(),
                    other.getSeriesId(), other.getSeriesOrder());

            downloadStatisticsService.recordStatistics(successCount.get());
            recordAuthorInfo(artworkId, other, cookie);
            recordSeriesInfo(artworkId, other, cookie);

            status.setSuccessCount(successCount.get());
            status.setFailedCount(imageUrls.size() - successCount.get());
            status.setCurrentImageIndex(-1); // 完成后重置索引

            log.info(logMessage("download.log.completed",
                    id(artworkId), text(successCount.get()), text(imageUrls.size()), downloadPath));

            // 下载后收藏（可选，best-effort）
            if (other.isBookmark()) {
                status.setBookmarkResult(pixivBookmarkService.bookmarkArtwork(artworkId, cookie));
            }

            // 下载后加入收藏夹（可选，best-effort）
            if (other.getCollectionId() != null) {
                try {
                    boolean added = collectionService.addArtwork(other.getCollectionId(), artworkId);
                    status.setCollectionResult(added
                            ? WorkActionResult.success(messages.get("collection.result.added"))
                            : WorkActionResult.exists(messages.get("collection.result.exists")));
                } catch (Exception e) {
                    log.warn(logMessage("download.log.collection.add.failed", artworkId, other.getCollectionId(), e.getMessage()), e);
                    status.setCollectionResult(WorkActionResult.failed(messages.get("collection.result.failed")));
                }
            }

            try {
                artworkHashService.recordArtworkHashes(pixivDatabase.getArtwork(artworkId));
            } catch (Exception e) {
                log.warn(logMessage("duplicate.log.hash.artwork-failed", artworkId, e.getMessage()), e);
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
            // 下载完成后，保留状态5分钟供查询，然后清理
            taskScheduler.schedule(
                    () -> downloadStatusMap.remove(statusKey),
                    Instant.now().plusSeconds(300)
            );
        }
        return succeeded;
    }

    private Path resolveEffectiveDownloadRoot(DownloadRequest.Other other) {
        Path defaultRoot = Paths.get(downloadConfig.getRootFolder());
        if (other != null && other.getCollectionId() != null) {
            return collectionService.resolveDownloadRoot(other.getCollectionId(), defaultRoot);
        }
        return defaultRoot;
    }

    public static void validateUserDownloadFolder(DownloadRequest.Other other) {
        if (other != null && other.isUserDownload() && other.getUsername() != null) {
            SafePathSegment.requireSafeDirectoryName(other.getUsername());
        }
    }

    private void ensureWithinDownloadRoot(Path downloadRoot, Path downloadPath) {
        if (!downloadPath.startsWith(downloadRoot)) {
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

    private boolean downloadImage(String imageUrl, Path filePath, String referer, String cookie,
                                  int imageNumber, int totalImages,
                                  Consumer<ImageDownloadProgress> progressListener,
                                  BooleanSupplier cancellationRequested) {
        int maxRetries = 3;
        int retryCount = 0;
        // 先写入 .part 临时文件，整段下载成功后再重命名为最终文件；失败 / 取消时清理 .part，
        // 避免半截文件污染下载目录或在重试 / 重新下载时被误判为已完成。
        Path tempPath = filePath.resolveSibling(filePath.getFileName().toString() + ".part");

        try {
            while (retryCount < maxRetries) {
                ensureNotCancelled(cancellationRequested);
                try {
                    Boolean success = downloadRestTemplate.execute(imageUrl, HttpMethod.GET,
                            request -> PixivRequestHeaders.applyImage(request.getHeaders(), referer, cookie),
                            (ClientHttpResponse response) -> {
                                if (!response.getStatusCode().is2xxSuccessful()) {
                                    log.error(logMessage("download.log.http-error", response.getStatusCode(), imageUrl));
                                    return false;
                                }
                                long totalBytes = response.getHeaders().getContentLength();
                                long[] downloadedBytes = {0L};
                                int[] lastProgress = {-1};
                                long[] lastBytes = {0L};
                                long[] lastAt = {0L};
                                publishImageProgress(progressListener, ImageDownloadProgress.builder()
                                        .status(ImageDownloadProgress.STATUS_RUNNING)
                                        .imageNumber(imageNumber)
                                        .totalImages(totalImages)
                                        .downloadedBytes(0L)
                                        .totalBytes(totalBytes > 0 ? totalBytes : null)
                                        .progress(totalBytes > 0 ? 0 : null)
                                        .build());
                                try (InputStream inputStream = response.getBody();
                                     FileOutputStream outputStream = new FileOutputStream(tempPath.toFile())) {
                                    byte[] buffer = new byte[4096];
                                    int bytesRead;
                                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                                        ensureNotCancelled(cancellationRequested);
                                        outputStream.write(buffer, 0, bytesRead);
                                        downloadedBytes[0] += bytesRead;
                                        Integer progress = totalBytes > 0
                                                ? Math.min(99, (int) (downloadedBytes[0] * 100 / totalBytes))
                                                : null;
                                        if (shouldEmitImageByteProgress(
                                                progress, downloadedBytes[0], lastProgress, lastBytes, lastAt)) {
                                            publishImageProgress(progressListener, ImageDownloadProgress.builder()
                                                    .status(ImageDownloadProgress.STATUS_RUNNING)
                                                    .imageNumber(imageNumber)
                                                    .totalImages(totalImages)
                                                    .downloadedBytes(downloadedBytes[0])
                                                    .totalBytes(totalBytes > 0 ? totalBytes : null)
                                                    .progress(progress)
                                                    .build());
                                        }
                                    }
                                }
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
                                            .totalBytes(totalBytes > 0 ? totalBytes : null)
                                            .progress(100)
                                            .build());
                                }
                                return true;
                            }, new Object[]{});

                    if (Boolean.TRUE.equals(success)) {
                        Files.move(tempPath, filePath, StandardCopyOption.REPLACE_EXISTING);
                        return true;
                    }
                    retryCount++;
                } catch (CancellationException e) {
                    throw e;
                } catch (Exception e) {
                    retryCount++;
                    log.error(logMessage("download.log.retry", imageUrl, e.getMessage(), retryCount, maxRetries));

                    if (retryCount < maxRetries) {
                        sleepCancellable(2000L * retryCount, cancellationRequested);
                    } else {
                        log.error(logMessage("download.log.retry.exhausted", imageUrl));
                    }
                }
            }
            publishImageProgress(progressListener, ImageDownloadProgress.builder()
                    .status(ImageDownloadProgress.STATUS_FAILED)
                    .imageNumber(imageNumber)
                    .totalImages(totalImages)
                    .build());
            return false;
        } finally {
            // 成功路径已把 .part 重命名为最终文件，这里只清理失败 / 取消遗留的半截文件。
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException ex) {
                log.warn(logMessage("download.log.temp-cleanup.failed", tempPath, ex.getMessage()));
            }
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
        return forceClearDownloads(status -> true);
    }

    public int forceClearDownloadsForOwner(@Nullable String ownerUuid) {
        return forceClearDownloads(status -> Objects.equals(status.getOwnerUuid(), ownerUuid));
    }

    private int forceClearDownloads(java.util.function.Predicate<DownloadStatus> matcher) {
        AtomicInteger cleared = new AtomicInteger();
        downloadStatusMap.forEach(10, (key, status) -> {
            if (status != null && matcher.test(status)) {
                status.setCancelled(true);
                status.setCompleted(true);
                status.setFailed(false);
                status.setEndTime(java.time.LocalDateTime.now());
                status.setErrorMessage(messages.get("download.cancelled"));
                if (downloadStatusMap.remove(key, status)) {
                    cleared.incrementAndGet();
                }
                eventPublisher.publishEvent(new DownloadProgressEvent(this, status.getArtworkId(), status, status.getOwnerUuid()));
            }
        });
        return cleared.get();
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

    private String getFileExtension(String url) {
        if (!StringUtils.hasText(url)) return "jpg";
        String[] parts = url.split("\\.");
        return parts.length > 1 ? parts[parts.length - 1] : "jpg";
    }

    private FileNamePlan buildFileNamePlan(Long artworkId, String title, int count, DownloadRequest.Other other) {
        String template = ArtworkFileNameFormatter.normalizeTemplate(other.getFileNameTemplate());
        long templateId = pixivDatabase.getOrCreateFileNameTemplateId(template);
        if (templateId <= 0) {
            templateId = ArtworkFileNameFormatter.DEFAULT_TEMPLATE_ID;
        }
        long preferredTime = TimestampUtils.toMillis(other.getFileNameTimestamp());
        long recordTime = preferredTime > 0 ? pixivDatabase.getUniqueTime(preferredTime) : pixivDatabase.getUniqueTime();
        String sanitizedAuthorName = ArtworkFileNameFormatter.sanitize(other.getAuthorName());
        long fileAuthorNameId = sanitizedAuthorName.isEmpty() ? 0L : pixivDatabase.getOrCreateFileAuthorNameId(sanitizedAuthorName);
        List<String> computed = ArtworkFileNameFormatter.formatAll(
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
        List<String> provided = ArtworkFileNameFormatter.normalizeProvidedBaseNames(other.getFileNames(), count, artworkId);
        if (!provided.isEmpty() && !provided.equals(computed)) {
            log.debug(logMessage("download.log.filename-mismatch", artworkId));
        }
        return new FileNamePlan(templateId, recordTime, fileAuthorNameId, provided.equals(computed) ? provided : computed);
    }

    private void recordDownload(Long artworkId, String title, String folderPath, HashSet<String> fileExtensions,
                                int count, int xRestrict, boolean isAi, Long authorId, String description, List<TagDto> tags,
                                long fileNameId, long recordTime, long fileAuthorNameId,
                                Long seriesId, Long seriesOrder) {
        try {
            pixivDatabase.insertArtwork(
                    artworkId, title,
                    Path.of(folderPath).toAbsolutePath().toString(),
                    count, String.join(",", fileExtensions), recordTime, xRestrict, isAi, authorId,
                    PixivDescriptionHtml.normalizeLinks(description), fileNameId,
                    fileAuthorNameId > 0 ? fileAuthorNameId : null,
                    seriesId, seriesOrder
            );
            pixivDatabase.saveArtworkTags(artworkId, tags);
        } catch (Exception e) {
            log.error(logMessage("download.log.record-history.failed", e.getMessage()), e);
        }
    }

    private record FileNamePlan(long templateId, long recordTime, long fileAuthorNameId, List<String> baseNames) {
        String baseName(int page) {
            if (page >= 0 && page < baseNames.size()) {
                return baseNames.get(page);
            }
            return "page_" + Math.max(page, 0);
        }
    }

    /**
     * 前端转发的原始 meta（{@code other.rawMetaJson}）落地：交由 {@link WorkMetaCaptureService} 解析 + 归一化。
     * 仅前端交互下载链路填充该字段（计划任务走后端自抓 body，不填）。捕获已是下载成功后的旁路动作，
     * 全程 best-effort、warn-continue——任何异常都不得反报已成功的下载（沿一致性模型，由下次下载或历史回填自愈）。
     */
    private void captureForwardedMeta(Long artworkId, DownloadRequest.Other other) {
        if (other == null || !StringUtils.hasText(other.getRawMetaJson())) {
            return;
        }
        try {
            workMetaCaptureService.captureForwardedArtwork(artworkId, other.getRawMetaJson());
        } catch (RuntimeException e) {
            log.warn("Failed to capture forwarded meta for {}: {}", artworkId, e.getMessage());
        }
    }

    private void recordAuthorInfo(Long artworkId, DownloadRequest.Other other, String cookie) {
        try {
            if (other != null && other.getAuthorId() != null) {
                authorService.observe(other.getAuthorId(), other.getAuthorName());
                return;
            }
            authorService.asyncLookupMissing(artworkId, cookie);
        } catch (Exception e) {
            log.warn(logMessage("download.log.record-author.failed", id(artworkId)), e);
        }
    }

    private void recordSeriesInfo(Long artworkId, DownloadRequest.Other other, String cookie) {
        try {
            if (other != null && other.getSeriesId() != null && other.getSeriesId() > 0) {
                // 前端/脚本若一并送来了系列简介或封面 URL，按 observeWithMetadata 流程顺带补齐；
                // 否则退回到原来仅 upsert 标题/作者的 observe()。
                boolean hasRichMeta = (other.getSeriesDescription() != null && !other.getSeriesDescription().isBlank())
                        || (other.getSeriesCoverUrl() != null && !other.getSeriesCoverUrl().isBlank());
                if (hasRichMeta) {
                    mangaSeriesService.observeWithMetadata(
                            other.getSeriesId(), other.getSeriesTitle(), other.getAuthorId(),
                            other.getSeriesDescription(), other.getSeriesCoverUrl(), cookie);
                } else {
                    mangaSeriesService.observe(other.getSeriesId(), other.getSeriesTitle(), other.getAuthorId());
                }
                return;
            }
            // Pixiv 系列几乎只挂在漫画 (illustType == 1) 上。当前端已经知道作品类型时，
            // 避免对插画/动图也发一次 /ajax/illust/{id} —— 批量下载时 N 张作品 = N 次额外请求很容易被限流。
            // illustType 为空（旧前端 / 未传）才回退到代理查询。
            Integer illustType = other == null ? null : other.getIllustType();
            if (illustType != null && illustType != 1) {
                pixivDatabase.updateSeriesInfo(artworkId,
                        MangaSeriesService.NO_SERIES_SENTINEL, MangaSeriesService.NO_SERIES_SENTINEL);
                return;
            }
            mangaSeriesService.asyncLookupMissingSeries(artworkId, cookie);
        } catch (Exception e) {
            log.warn(logMessage("download.log.record-series.failed", id(artworkId)), e);
        }
    }

    @Override
    public boolean isArtworkDownloaded(long artworkId, boolean verifyFiles) {
        return downloadedArtworkService.getDownloadedRecord(artworkId, verifyFiles) != null;
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
            return MessageBundles.getOrDefault(
                    Locale.getDefault(),
                    localized.getMessageCode(),
                    localized.getDefaultMessage(),
                    localized.getMessageArgs()
            );
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return MessageBundles.get("error.unexpected");
        }
        return message;
    }
}
