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
import top.sywyar.pixivdownload.common.SafePathSegment;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.download.db.ArtworkRecord;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.download.db.TagDto;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.download.response.ImageResponse;
import top.sywyar.pixivdownload.download.response.StatisticsResponse;
import top.sywyar.pixivdownload.duplicate.ImageHashService;
import top.sywyar.pixivdownload.imageclassifier.ThumbnailManager;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.series.MangaSeriesService;
import top.sywyar.pixivdownload.util.TimestampUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Slf4j
@Service
public class DownloadService {
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");

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
    private final ArtworkFileLocator artworkFileLocator;
    private final ImageHashService imageHashService;
    private final AppMessages messages;

    // 存储下载状态
    private final ConcurrentHashMap<String, DownloadStatus> downloadStatusMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> thumbnailCacheLocks = new ConcurrentHashMap<>();

    public record ThumbnailFile(Path path, String extension) {
    }

    public DownloadService(DownloadConfig downloadConfig,
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
                           ArtworkFileLocator artworkFileLocator,
                           ImageHashService imageHashService,
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
        this.artworkFileLocator = artworkFileLocator;
        this.imageHashService = imageHashService;
        this.messages = messages;
    }

    @Async("downloadTaskExecutor")
    public void downloadImages(Long artworkId, String title, List<String> imageUrls,
                               String referer, DownloadRequest.Other other, String cookie,
                               String userUuid) {
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

            // 多人模式：记录已下载的文件夹（用于配额超出时打包）
            if (userUuid != null && userQuotaService != null) {
                userQuotaService.recordFolder(userUuid, downloadPath);
            }

            // 记录下载信息
            recordDownload(artworkId, title, status.getDownloadPath(), fileExtensions,
                    successCount.get(), other.getXRestrict(), other.isAi(), other.getAuthorId(), other.getDescription(), other.getTags(),
                    fileNamePlan.templateId(), fileNamePlan.recordTime(), fileNamePlan.fileAuthorNameId(),
                    other.getSeriesId(), other.getSeriesOrder());

            recordStatistics(imageUrls.size());
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
                            ? DownloadActionResult.success(messages.get("collection.result.added"))
                            : DownloadActionResult.exists(messages.get("collection.result.exists")));
                } catch (Exception e) {
                    log.warn(logMessage("download.log.collection.add.failed", artworkId, other.getCollectionId(), e.getMessage()), e);
                    status.setCollectionResult(DownloadActionResult.failed(messages.get("collection.result.failed")));
                }
            }

            try {
                imageHashService.recordArtworkHashes(pixivDatabase.getArtwork(artworkId));
            } catch (Exception e) {
                log.warn(logMessage("duplicate.log.hash.artwork-failed", artworkId, e.getMessage()), e);
            }

            // 更新下载状态为完成。放在后置动作之后，确保最终事件包含收藏/收藏夹结果。
            status.setCompleted(true);

            // 发送最终完成状态更新
            eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status, userUuid));

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
                            request -> {
                                request.getHeaders().set("Referer", referer);
                                request.getHeaders().set("User-Agent",
                                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                                if (cookie != null && !cookie.trim().isEmpty()) {
                                    request.getHeaders().set("Cookie", cookie);
                                }
                            },
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
                            });

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
            log.debug("前端文件名与后端模板计算结果不一致，使用后端结果: artworkId={}", artworkId);
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

    @Async
    public void moveArtWork(Long artworkId, String movePath, Long moveTime) {
        moveArtWork(artworkId, movePath, moveTime, null);
    }

    @Async
    public void moveArtWork(Long artworkId, String movePath, Long moveTime, String classifierTargetFolder) {
        try {
            ArtworkRecord existing = pixivDatabase.getArtwork(artworkId);
            if (existing == null) {
                return;
            }
            pixivDatabase.updateArtworkMove(artworkId, movePath,
                    TimestampUtils.toMillis(moveTime), classifierTargetFolder);
            if (!existing.moved()) {
                pixivDatabase.incrementMoved();
            }
        } catch (Exception e) {
            log.error(logMessage("download.log.move-record.failed", e.getMessage()), e);
        }
    }

    public List<String> getDownloadedRecord() {
        List<String> ids = new LinkedList<>();
        pixivDatabase.getAllArtworkIds().forEach(id -> ids.add(String.valueOf(id)));
        return ids;
    }

    public ArtworkRecord getDownloadedRecord(Long artworkId) {
        return pixivDatabase.getArtwork(artworkId);
    }

    public ArtworkRecord getDownloadedRecord(Long artworkId, boolean verifyFiles) {
        ArtworkRecord artwork = pixivDatabase.getArtwork(artworkId);
        if (artwork != null) {
            if (!verifyFiles) {
                return artwork;
            }
            if (hasArtworkFiles(artwork)) {
                return artwork;
            }
            removeStaleArtworkRecord(artwork);
            return null;
        }
        if (verifyFiles) {
            return findArtworkOnDisk(artworkId);
        }
        return null;
    }

    /**
     * pixiv-batch 两阶段恢复入口：调用方已从 Pixiv 拉回元数据。
     * <ul>
     *   <li>DB 已有记录且 title 非空 → 返回原记录（不覆盖任何字段）</li>
     *   <li>DB 已有记录但 title 为空（说明先前是裸记录恢复出来的）→ 仅填补 NULL/空字段后返回最新记录</li>
     *   <li>DB 无记录但 {@code {rootFolder}/{artworkId}/} 下有匹配默认模板的图片 → 用 meta + 实际页数/扩展名写完整记录</li>
     *   <li>否则返回 null（调用方按未下载处理）</li>
     * </ul>
     */
    public ArtworkRecord recoverMetadata(Long artworkId, top.sywyar.pixivdownload.download.request.RecoverMetadataRequest meta) {
        if (meta == null) {
            meta = new top.sywyar.pixivdownload.download.request.RecoverMetadataRequest();
        }
        ArtworkRecord existing = pixivDatabase.getArtwork(artworkId);
        String normalizedDescription = PixivDescriptionHtml.normalizeLinks(meta.getDescription());
        if (existing != null) {
            if (StringUtils.hasText(existing.title())) {
                return existing;
            }
            pixivDatabase.fillArtworkMetadataIfMissing(artworkId,
                    StringUtils.hasText(meta.getTitle()) ? meta.getTitle() : null,
                    meta.getXRestrict(), meta.getIsAi(), meta.getAuthorId(),
                    StringUtils.hasText(normalizedDescription) ? normalizedDescription : null);
            observeAuthorIfPresent(artworkId, meta);
            return pixivDatabase.getArtwork(artworkId);
        }
        // DB 无记录：扫描磁盘 → 用 meta 写完整记录
        String rootFolder = downloadConfig.getRootFolder();
        File rootDir = new File(rootFolder);
        if (!rootDir.isDirectory()) {
            return null;
        }
        Path flatDir = Paths.get(rootFolder, String.valueOf(artworkId));
        File dirFile = flatDir.toFile();
        if (!dirFile.isDirectory()) {
            return null;
        }
        Map<Integer, String> pageExt = scanDefaultTemplateFiles(dirFile, artworkId);
        if (pageExt.isEmpty()) {
            return null;
        }
        int count = Collections.max(pageExt.keySet()) + 1;
        LinkedHashSet<String> uniqueExts = new LinkedHashSet<>(new TreeMap<>(pageExt).values());
        String extensions = String.join(",", uniqueExts);
        String absoluteFolder = flatDir.toAbsolutePath().toString();
        log.info(logMessage("download.log.stale-record.restored",
                id(artworkId), absoluteFolder));
        pixivDatabase.insertArtwork(artworkId,
                StringUtils.hasText(meta.getTitle()) ? meta.getTitle() : "",
                absoluteFolder, count, extensions,
                pixivDatabase.getUniqueTime(), meta.getXRestrict(), meta.getIsAi(),
                meta.getAuthorId(),
                normalizedDescription == null ? "" : normalizedDescription);
        observeAuthorIfPresent(artworkId, meta);
        return pixivDatabase.getArtwork(artworkId);
    }

    private void observeAuthorIfPresent(Long artworkId, top.sywyar.pixivdownload.download.request.RecoverMetadataRequest meta) {
        if (meta.getAuthorId() == null) return;
        try {
            authorService.observe(meta.getAuthorId(), meta.getAuthorName());
        } catch (Exception e) {
            log.warn(logMessage("download.log.record-author.failed", id(artworkId)), e);
        }
    }

    private ArtworkRecord findArtworkOnDisk(Long artworkId) {
        String rootFolder = downloadConfig.getRootFolder();
        File rootDir = new File(rootFolder);
        if (!rootDir.isDirectory()) {
            return null;
        }
        Path flatDir = Paths.get(rootFolder, String.valueOf(artworkId));
        File dirFile = flatDir.toFile();
        if (!dirFile.isDirectory()) {
            return null;
        }
        // 仅识别符合默认文件名模板 {artwork_id}_p{page}.{ext} 的文件 —— 恢复出的记录会以
        // DEFAULT_TEMPLATE_ID 写回 DB，只有匹配该模板的文件才能被后续 resolveImageFile 查到。
        Map<Integer, String> pageExt = scanDefaultTemplateFiles(dirFile, artworkId);
        if (pageExt.isEmpty()) {
            return null;
        }
        int count = Collections.max(pageExt.keySet()) + 1;
        // 按页号升序收集，使 extensions 顺序稳定（便于排查与单测断言）
        LinkedHashSet<String> uniqueExts = new LinkedHashSet<>(new TreeMap<>(pageExt).values());
        String extensions = String.join(",", uniqueExts);
        String absoluteFolder = flatDir.toAbsolutePath().toString();
        log.info(logMessage("download.log.stale-record.restored",
                id(artworkId), absoluteFolder));
        pixivDatabase.insertArtwork(artworkId, "", absoluteFolder, count, extensions,
                pixivDatabase.getUniqueTime(), null, null, null, "");
        return pixivDatabase.getArtwork(artworkId);
    }

    private Map<Integer, String> scanDefaultTemplateFiles(File directory, long artworkId) {
        File[] files = directory.listFiles();
        if (files == null) {
            return Collections.emptyMap();
        }
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "^" + java.util.regex.Pattern.quote(String.valueOf(artworkId)) + "_p(\\d+)\\.([A-Za-z0-9]+)$");
        Map<Integer, String> pageExt = new HashMap<>();
        for (File file : files) {
            if (!file.isFile()) continue;
            java.util.regex.Matcher m = pattern.matcher(file.getName());
            if (!m.matches()) continue;
            String ext = m.group(2).toLowerCase(Locale.ROOT);
            if (!IMAGE_EXTENSIONS.contains(ext)) continue;
            int page = Integer.parseInt(m.group(1));
            pageExt.merge(page, ext, (existing, incoming) -> existing);
        }
        return pageExt;
    }

    public ImageResponse getImageResponse(Long artworkId, int page, boolean thumbnail) throws IOException {
        if (thumbnail) {
            ThumbnailFile thumbnailFile = getThumbnailFile(artworkId, page);
            if (thumbnailFile == null) {
                return null;
            }
            byte[] fileBytes = Files.readAllBytes(thumbnailFile.path());
            BufferedImage image = ImageIO.read(thumbnailFile.path().toFile());
            int width = image == null ? 0 : image.getWidth();
            int height = image == null ? 0 : image.getHeight();
            String base64Image = Base64.getEncoder().encodeToString(fileBytes);
            return new ImageResponse(true, base64Image, thumbnailFile.extension(), base64Image.length(), width, height,
                    messages.get("download.image.thumbnail.fetch-success"));
        }

        ArtworkRecord artwork = pixivDatabase.getArtwork(artworkId);
        if (artwork == null) {
            return null;
        }

        int count = artwork.count();
        if (count <= page || page < 0) {
            return null;
        }

        File imageFile = resolveImageFile(artwork, page);
        if (imageFile == null) {
            return null;
        }
        String extension = getFileExtension(imageFile.getName()).toLowerCase(Locale.ROOT);

        boolean isWebp = "webp".equals(extension);

        // WebP 完整图请求：直接返回原始字节，由 rawfile 端点处理更高效
        // 此处 thumbnail=false 路径保留为备用
        if (isWebp && !thumbnail) {
            byte[] fileBytes = Files.readAllBytes(imageFile.toPath());
            String base64Image = Base64.getEncoder().encodeToString(fileBytes);
            return new ImageResponse(true, base64Image, "webp", base64Image.length(), 0, 0,
                    messages.get("download.image.ugoira.fetch-success"));
        }

        // WebP 缩略图：使用伴随的 _p0_thumb.jpg 文件
        if (isWebp) {
            String dirPath = resolveArtworkDirectory(artwork);
            String baseName = resolveStoredFileBaseName(artwork, page);
            File thumbFile = Paths.get(dirPath, baseName + "_thumb.jpg").toFile();
            if (!thumbFile.exists()) {
                return null;
            }
            imageFile = thumbFile;
            extension = "jpg";
        }

        BufferedImage image;
        if (thumbnail) {
            image = ThumbnailManager.getThumbnail(imageFile, -1, -1);
        } else {
            image = ImageIO.read(imageFile);
        }

        String writeFormat = extension;
        ByteArrayOutputStream bass = new ByteArrayOutputStream();
        ImageIO.write(image, writeFormat, bass);
        String base64Image = Base64.getEncoder().encodeToString(bass.toByteArray());

        return new ImageResponse(true, base64Image, writeFormat, base64Image.length(), image.getWidth(), image.getHeight(),
                messages.get("download.image.thumbnail.fetch-success"));
    }

    public ThumbnailFile getThumbnailFile(Long artworkId, int page) throws IOException {
        ArtworkRecord artwork = pixivDatabase.getArtwork(artworkId);
        if (artwork == null || artwork.count() <= page || page < 0) {
            return null;
        }

        File imageFile = resolveThumbnailSourceFile(artwork, page);
        if (imageFile == null) {
            return null;
        }
        String writeFormat = normalizeThumbnailFormat(getFileExtension(imageFile.getName()).toLowerCase(Locale.ROOT));
        Path cachePath = thumbnailCachePath(artworkId, page, writeFormat);
        String lockKey = cachePath.toString();
        Object lock = thumbnailCacheLocks.computeIfAbsent(lockKey, ignored -> new Object());
        try {
            synchronized (lock) {
                FileTime sourceTime = Files.getLastModifiedTime(imageFile.toPath());
                if (isFreshThumbnailCache(cachePath, sourceTime)) {
                    return new ThumbnailFile(cachePath, writeFormat);
                }
                Files.createDirectories(cachePath.getParent());
                BufferedImage thumbnailImage = ThumbnailManager.getThumbnail(imageFile, -1, -1);
                Path tempPath = Files.createTempFile(cachePath.getParent(), "thumb-", "." + writeFormat);
                try {
                    try (OutputStream out = Files.newOutputStream(tempPath)) {
                        if (!ImageIO.write(thumbnailImage, writeFormat, out)) {
                            throw new IOException("Unsupported thumbnail format: " + writeFormat);
                        }
                    }
                    moveReplacing(tempPath, cachePath);
                    Files.setLastModifiedTime(cachePath, sourceTime);
                } finally {
                    Files.deleteIfExists(tempPath);
                }
            }
        } finally {
            thumbnailCacheLocks.remove(lockKey, lock);
        }
        return new ThumbnailFile(cachePath, writeFormat);
    }

    private File resolveThumbnailSourceFile(ArtworkRecord artwork, int page) {
        File imageFile = resolveImageFile(artwork, page);
        if (imageFile == null) {
            return null;
        }
        String extension = getFileExtension(imageFile.getName()).toLowerCase(Locale.ROOT);
        if (!"webp".equals(extension)) {
            return imageFile;
        }
        String dirPath = resolveArtworkDirectory(artwork);
        String baseName = resolveStoredFileBaseName(artwork, page);
        File thumbFile = Paths.get(dirPath, baseName + "_thumb.jpg").toFile();
        return thumbFile.exists() ? thumbFile : null;
    }

    private Path thumbnailCachePath(Long artworkId, int page, String extension) {
        return RuntimeFiles.galleryThumbnailDirectory()
                .resolve(String.valueOf(artworkId))
                .resolve("p" + page + "." + extension)
                .toAbsolutePath()
                .normalize();
    }

    private boolean isFreshThumbnailCache(Path cachePath, FileTime sourceTime) throws IOException {
        if (!Files.isRegularFile(cachePath) || Files.size(cachePath) <= 0) {
            return false;
        }
        return Files.getLastModifiedTime(cachePath).toMillis() >= sourceTime.toMillis();
    }

    private String normalizeThumbnailFormat(String extension) {
        return "jpeg".equals(extension) ? "jpg" : extension;
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public File getImageFile(Long artworkId, int page) {
        ArtworkRecord artwork = pixivDatabase.getArtwork(artworkId);
        if (artwork == null) return null;

        int count = artwork.count();
        if (count <= page || page < 0) return null;

        return resolveImageFile(artwork, page);
    }

    private boolean hasArtworkFiles(ArtworkRecord artwork) {
        String directoryPath = resolveArtworkDirectory(artwork);
        if (!StringUtils.hasText(directoryPath)) {
            return false;
        }
        File directory = new File(directoryPath);
        if (!directory.isDirectory()) {
            return false;
        }
        for (int page = 0; page < Math.max(artwork.count(), 1); page++) {
            File file = resolveImageFile(artwork, page);
            if (file != null && IMAGE_EXTENSIONS.contains(getFileExtension(file.getName()).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private File resolveImageFile(ArtworkRecord artwork, int page) {
        return artworkFileLocator.resolveImageFile(artwork, page);
    }

    private String resolveStoredFileBaseName(ArtworkRecord artwork, int page) {
        return artworkFileLocator.resolveStoredFileBaseName(artwork, page);
    }

    private String resolveArtworkDirectory(ArtworkRecord artwork) {
        return artworkFileLocator.resolveArtworkDirectory(artwork);
    }

    private void removeStaleArtworkRecord(ArtworkRecord artwork) {
        try {
            pixivDatabase.deleteArtwork(artwork.artworkId());
            log.info(logMessage("download.log.stale-record.deleted",
                    id(artwork.artworkId()), resolveArtworkDirectory(artwork)));
        } catch (Exception e) {
            log.warn(logMessage("download.log.stale-record.delete-failed", id(artwork.artworkId())), e);
        }
    }

    public static File findFileByName(String directoryPath, String fileName) {
        return ArtworkFileLocator.findFileByName(directoryPath, fileName);
    }

    public void recordStatistics(int count) {
        try {
            pixivDatabase.incrementStats(count);
        } catch (Exception e) {
            log.error(logMessage("download.log.statistics.failed", e.getMessage()), e);
        }
    }

    public StatisticsResponse getStatistics() {
        int[] stats = pixivDatabase.getStats();
        return new StatisticsResponse(true, stats[0], stats[1], stats[2], messages.get("download.statistics.success"));
    }

    public List<Long> getSortTimeArtwork() {
        return pixivDatabase.getArtworkIdsSortedByTimeDesc();
    }

    public List<Long> getSortAuthorArtwork() {
        return pixivDatabase.getArtworkIdsSortedByAuthorIdAsc();
    }

    public List<Long> getSortTimeArtworkPaged(int page, int size) {
        return pixivDatabase.getArtworkIdsSortedByTimeDescPaged(page * size, size);
    }

    public List<Long> getSortAuthorArtworkPaged(int page, int size) {
        return pixivDatabase.getArtworkIdsSortedByAuthorIdAscPaged(page * size, size);
    }

    public long getArtworkCount() {
        return pixivDatabase.countArtworks();
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
