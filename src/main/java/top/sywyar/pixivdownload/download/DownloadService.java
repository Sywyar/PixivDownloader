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
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.download.db.ArtworkRecord;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.download.db.TagDto;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.download.response.ImageResponse;
import top.sywyar.pixivdownload.download.response.StatisticsResponse;
import top.sywyar.pixivdownload.imageclassifier.ThumbnailManager;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.quota.UserQuotaService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final AppMessages messages;

    // 存储下载状态
    private final ConcurrentHashMap<Long, DownloadStatus> downloadStatusMap = new ConcurrentHashMap<>();

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
        this.messages = messages;
    }

    @Async
    public void downloadImages(Long artworkId, String title, List<String> imageUrls,
                               String referer, DownloadRequest.Other other, String cookie,
                               String userUuid) {
        if (other == null) {
            other = new DownloadRequest.Other();
        }
        // 初始化下载状态
        DownloadStatus status = new DownloadStatus(artworkId, title, imageUrls.size());
        downloadStatusMap.put(artworkId, status);

        // 发送初始状态更新
        eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status, userUuid));

        try {
            FileNamePlan fileNamePlan = buildFileNamePlan(artworkId, title, imageUrls.size(), other);
            other.setFileNames(fileNamePlan.baseNames());
            String folderName = String.valueOf(artworkId);

            // 创建文件夹结构
            Path downloadRoot = resolveEffectiveDownloadRoot(other);
            Path downloadPath = downloadRoot;
            if (other.isUserDownload() && !downloadConfig.isUserFlatFolder()) {
                downloadPath = downloadPath.resolve(other.getUsername());

                if (other.getXRestrict() == 2) {
                    downloadPath = downloadPath.resolve("R18G");
                } else if (other.getXRestrict() == 1) {
                    downloadPath = downloadPath.resolve("R18");
                }
            }
            downloadPath = downloadPath.resolve(folderName);
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
                        artworkId, other, downloadPath, referer, cookie, progressListener));

                status.setDownloadedCount(successCount.get());
                eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status, userUuid));

            } else {
                // === 普通图片下载 ===
                for (String url : imageUrls) validatePixivUrl(url);
                for (int i = 0; i < imageUrls.size(); i++) {
                    if (status.isCancelled()) {
                        break;
                    }

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
                                imageNumber, imageUrls.size(), imageProgressListener)) {
                            successCount.incrementAndGet();
                            status.setDownloadedCount(successCount.get());
                            log.info(logMessage("download.log.progress",
                                    id(artworkId), text(successCount.get()), text(imageUrls.size())));
                            eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status, userUuid));
                        }
                        if (other.getDelayMs() > 0) Thread.sleep(other.getDelayMs());
                    } catch (Exception e) {
                        log.error(logMessage("download.log.image.failed", imageUrl, e.getMessage()));
                    }
                }
            }

            // 多人模式：记录已下载的文件夹（用于配额超出时打包）
            if (userUuid != null && userQuotaService != null) {
                userQuotaService.recordFolder(userUuid, downloadPath);
            }

            // 记录下载信息
            recordDownload(artworkId, title, status.getDownloadPath(), fileExtensions,
                    successCount.get(), other.getXRestrict(), other.isAi(), other.getAuthorId(), other.getDescription(), other.getTags(),
                    fileNamePlan.templateId(), fileNamePlan.recordTime(), fileNamePlan.fileAuthorNameId());

            recordStatistics(imageUrls.size());
            recordAuthorInfo(artworkId, other, cookie);

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

            // 更新下载状态为完成。放在后置动作之后，确保最终事件包含收藏/收藏夹结果。
            status.setCompleted(true);

            // 发送最终完成状态更新
            eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status, userUuid));

        } catch (Exception e) {
            log.error(logMessage("download.log.failed"), e);
            status.setCompleted(true);
            status.setFailed(true);
            status.setErrorMessage(resolveStatusErrorMessage(e));
        } finally {
            // 下载完成后，保留状态5分钟供查询，然后清理
            taskScheduler.schedule(
                    () -> downloadStatusMap.remove(artworkId),
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
                                  Consumer<ImageDownloadProgress> progressListener) {
        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
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
                                 FileOutputStream outputStream = new FileOutputStream(filePath.toFile())) {
                                byte[] buffer = new byte[4096];
                                int bytesRead;
                                while ((bytesRead = inputStream.read(buffer)) != -1) {
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
                    return true;
                }
                retryCount++;
            } catch (Exception e) {
                retryCount++;
                log.error(logMessage("download.log.retry", imageUrl, e.getMessage(), retryCount, maxRetries));

                if (retryCount < maxRetries) {
                    try {
                        Thread.sleep(2000L * retryCount);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    log.error(logMessage("download.log.retry.exhausted", imageUrl));
                    publishImageProgress(progressListener, ImageDownloadProgress.builder()
                            .status(ImageDownloadProgress.STATUS_FAILED)
                            .imageNumber(imageNumber)
                            .totalImages(totalImages)
                            .build());
                    return false;
                }
            }
        }
        publishImageProgress(progressListener, ImageDownloadProgress.builder()
                .status(ImageDownloadProgress.STATUS_FAILED)
                .imageNumber(imageNumber)
                .totalImages(totalImages)
                .build());
        return false;
    }

    private void publishImageProgress(Consumer<ImageDownloadProgress> progressListener, ImageDownloadProgress progress) {
        if (progressListener != null) {
            progressListener.accept(progress);
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
        return downloadStatusMap.get(artworkId);
    }

    public List<Long> getDownloadStatus() {
        List<Long> downloadStatus = new LinkedList<>();
        downloadStatusMap.forEachKey(10, downloadStatus::add);
        return downloadStatus;
    }

    // 取消下载
    public void cancelDownload(Long artworkId) {
        DownloadStatus status = downloadStatusMap.get(artworkId);
        if (status != null) {
            status.setCancelled(true);
        }
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
        long preferredTime = other.getFileNameTimestamp() == null ? 0L : other.getFileNameTimestamp();
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
                                long fileNameId, long recordTime, long fileAuthorNameId) {
        try {
            pixivDatabase.insertArtwork(
                    artworkId, title,
                    Path.of(folderPath).toAbsolutePath().toString(),
                    count, String.join(",", fileExtensions), recordTime, xRestrict, isAi, authorId, description, fileNameId,
                    fileAuthorNameId > 0 ? fileAuthorNameId : null
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

    @Async
    public void moveArtWork(Long artworkId, String movePath, Long moveTime) {
        try {
            ArtworkRecord existing = pixivDatabase.getArtwork(artworkId);
            if (existing == null) {
                return;
            }
            pixivDatabase.updateArtworkMove(artworkId, movePath, moveTime);
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
        if (!verifyFiles || artwork == null) {
            return artwork;
        }
        if (hasArtworkFiles(artwork)) {
            return artwork;
        }
        removeStaleArtworkRecord(artwork);
        return null;
    }

    public ImageResponse getImageResponse(Long artworkId, int page, boolean thumbnail) throws IOException {
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
        String directoryPath = resolveArtworkDirectory(artwork);
        if (!StringUtils.hasText(directoryPath)) {
            return null;
        }
        String baseName = resolveStoredFileBaseName(artwork, page);
        String[] extensions = artwork.extensions() == null ? new String[0] : artwork.extensions().split(",");
        File imageFile;
        if (extensions.length > 1) {
            imageFile = findFileByName(directoryPath, baseName);
        } else {
            String extension = extensions.length == 0 || !StringUtils.hasText(extensions[0]) ? "jpg" : extensions[0];
            imageFile = Paths.get(directoryPath, baseName + "." + extension).toFile();
        }
        return imageFile != null && imageFile.exists() ? imageFile : null;
    }

    private String resolveStoredFileBaseName(ArtworkRecord artwork, int page) {
        long fileNameId = artwork.fileName() == null
                ? ArtworkFileNameFormatter.DEFAULT_TEMPLATE_ID
                : artwork.fileName();
        String template = pixivDatabase.getFileNameTemplate(fileNameId);
        int count = Math.max(artwork.count(), page + 1);
        String authorName = resolveStoredFileAuthorName(artwork);
        if (authorName == null && template != null && template.contains("{author_name}")) {
            log.warn("模板含{author_name}但file_author_name_id为空，作者名将缺失: artworkId={}", artwork.artworkId());
        }
        List<String> baseNames = ArtworkFileNameFormatter.formatAll(
                template,
                artwork.artworkId(),
                artwork.title(),
                artwork.authorId(),
                authorName,
                artwork.time(),
                count,
                artwork.isAi(),
                artwork.xRestrict()
        );
        return baseNames.get(page);
    }

    private String resolveStoredFileAuthorName(ArtworkRecord artwork) {
        Long fileAuthorNameId = artwork.fileAuthorNameId();
        if (fileAuthorNameId == null || fileAuthorNameId <= 0) {
            return null;
        }
        return pixivDatabase.getFileAuthorName(fileAuthorNameId);
    }

    private String resolveArtworkDirectory(ArtworkRecord artwork) {
        if (artwork == null) {
            return null;
        }
        if (StringUtils.hasText(artwork.moveFolder())) {
            return artwork.moveFolder();
        }
        return artwork.folder();
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
        File directory = new File(directoryPath);
        if (!directory.exists() || !directory.isDirectory()) {
            return null;
        }
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && getBaseName(file.getName()).equals(fileName)) {
                    return file;
                }
            }
        }
        return null;
    }

    private static String getBaseName(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
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
            String host   = uri.getHost();
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
