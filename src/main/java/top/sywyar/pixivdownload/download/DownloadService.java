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
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.download.db.ArtworkRecord;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.download.db.TagDto;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.download.response.ImageResponse;
import top.sywyar.pixivdownload.download.response.StatisticsResponse;
import top.sywyar.pixivdownload.imageclassifier.ThumbnailManager;
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
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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

    // 存储下载状态
    private final ConcurrentHashMap<Long, DownloadStatus> downloadStatusMap = new ConcurrentHashMap<>();

    public DownloadService(DownloadConfig downloadConfig,
                           ApplicationEventPublisher eventPublisher,
                           PixivDatabase pixivDatabase,
                           @Nullable UserQuotaService userQuotaService,
                           @Qualifier("downloadRestTemplate") RestTemplate downloadRestTemplate,
                           TaskScheduler taskScheduler,
                           PixivBookmarkService pixivBookmarkService,
                           UgoiraService ugoiraService,
                           AuthorService authorService) {
        this.downloadConfig = downloadConfig;
        this.eventPublisher = eventPublisher;
        this.pixivDatabase = pixivDatabase;
        this.userQuotaService = userQuotaService;
        this.downloadRestTemplate = downloadRestTemplate;
        this.taskScheduler = taskScheduler;
        this.pixivBookmarkService = pixivBookmarkService;
        this.ugoiraService = ugoiraService;
        this.authorService = authorService;
    }

    @Async
    public void downloadImages(Long artworkId, String title, List<String> imageUrls,
                               String referer, DownloadRequest.Other other, String cookie,
                               String userUuid) {
        // 初始化下载状态
        DownloadStatus status = new DownloadStatus(artworkId, title, imageUrls.size());
        downloadStatusMap.put(artworkId, status);

        // 发送初始状态更新
        eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status));

        try {
            String folderName = String.valueOf(artworkId);

            // 创建文件夹结构
            Path downloadPath = Paths.get(downloadConfig.getRootFolder());
            if (other.isUserDownload() && !downloadConfig.isUserFlatFolder()) {
                downloadPath = Paths.get(downloadPath.toString(), other.getUsername());

                if (other.isR18()) {
                    downloadPath = Paths.get(downloadPath.toString(), "R18");
                }
            }
            downloadPath = Paths.get(downloadPath.toString(), folderName);
            status.setFolderName(Paths.get(downloadConfig.getRootFolder()).relativize(downloadPath).toString());
            Files.createDirectories(downloadPath);
            status.setDownloadPath(downloadPath.toString());

            AtomicInteger successCount = new AtomicInteger(0);

            HashSet<String> fileExtensions = new HashSet<>();

            if (other.isUgoira() && other.getUgoiraZipUrl() != null) {
                // === 动图 (ugoira) 处理：委托给 UgoiraService ===
                fileExtensions.add("webp");
                status.setCurrentImageIndex(0);
                eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status));

                successCount.set(ugoiraService.processUgoira(artworkId, other, downloadPath, referer, cookie));

                status.setDownloadedCount(successCount.get());
                eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status));

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
                    eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status));

                    try {
                        String extension = getFileExtension(imageUrl);
                        fileExtensions.add(extension);
                        String filename = artworkId + "_p" + i + "." + extension;
                        Path filePath = downloadPath.resolve(filename);
                        if (downloadImage(imageUrl, filePath, referer, cookie)) {
                            successCount.incrementAndGet();
                            status.setDownloadedCount(successCount.get());
                            log.info("作品：{}，下载进度：{}/{}", artworkId, successCount.get(), imageUrls.size());
                            eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status));
                        }
                        if (other.getDelayMs() > 0) Thread.sleep(other.getDelayMs());
                    } catch (Exception e) {
                        log.error("下载图片失败: {}, 错误: {}", imageUrl, e.getMessage());
                    }
                }
            }

            // 多人模式：记录已下载的文件夹（用于配额超出时打包）
            if (userUuid != null && userQuotaService != null) {
                userQuotaService.recordFolder(userUuid, downloadPath);
            }

            // 记录下载信息
            recordDownload(artworkId, title, status.getDownloadPath(), fileExtensions,
                    successCount.get(), other.isR18(), other.isAi(), other.getAuthorId(), other.getDescription(), other.getTags());

            recordStatistics(imageUrls.size());
            recordAuthorInfo(artworkId, other, cookie);

            // 更新下载状态为完成
            status.setCompleted(true);
            status.setSuccessCount(successCount.get());
            status.setFailedCount(imageUrls.size() - successCount.get());
            status.setCurrentImageIndex(-1); // 完成后重置索引

            log.info("下载完成: 作品 {}, 成功下载 {}/{} 张图片到 {}", artworkId, successCount.get(), imageUrls.size(), downloadPath);

            // 下载后收藏（可选，best-effort）
            if (other.isBookmark()) {
                pixivBookmarkService.bookmarkArtwork(artworkId, cookie);
            }

            // 发送最终完成状态更新
            eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status));

        } catch (Exception e) {
            log.error("下载出错", e);
            status.setCompleted(true);
            status.setFailed(true);
            status.setErrorMessage(e.getMessage());
        } finally {
            // 下载完成后，保留状态5分钟供查询，然后清理
            taskScheduler.schedule(
                    () -> downloadStatusMap.remove(artworkId),
                    Instant.now().plusSeconds(300)
            );
        }
    }

    private boolean downloadImage(String imageUrl, Path filePath, String referer, String cookie) {
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
                                log.error("HTTP错误: {} for {}", response.getStatusCode(), imageUrl);
                                return false;
                            }
                            try (InputStream inputStream = response.getBody();
                                 FileOutputStream outputStream = new FileOutputStream(filePath.toFile())) {
                                byte[] buffer = new byte[4096];
                                int bytesRead;
                                while ((bytesRead = inputStream.read(buffer)) != -1) {
                                    outputStream.write(buffer, 0, bytesRead);
                                }
                            }
                            return true;
                        });

                if (Boolean.TRUE.equals(success)) {
                    return true;
                }
                retryCount++;
            } catch (Exception e) {
                retryCount++;
                log.error("下载失败：{}，错误:{}，重试：{}/{}", imageUrl, e.getMessage(), retryCount, maxRetries);

                if (retryCount < maxRetries) {
                    try {
                        Thread.sleep(2000L * retryCount);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    log.error("重试次数用尽，放弃下载: {}", imageUrl);
                    return false;
                }
            }
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

    private void recordDownload(Long artworkId, String title, String folderPath, HashSet<String> fileExtensions,
                                int count, boolean isR18, boolean isAi, Long authorId, String description, List<TagDto> tags) {
        try {
            long time = pixivDatabase.getUniqueTime();
            pixivDatabase.insertArtwork(
                    artworkId, title,
                    Path.of(folderPath).toAbsolutePath().toString(),
                    count, String.join(",", fileExtensions), time, isR18, isAi, authorId, description
            );
            pixivDatabase.saveArtworkTags(artworkId, tags);
        } catch (Exception e) {
            log.error("记录下载历史失败: {}", e.getMessage(), e);
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
            log.warn("记录作者信息失败: artworkId={}", artworkId, e);
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
            log.error("移动记录失败: {}", e.getMessage(), e);
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

        String dirPath = artwork.moved() ? artwork.moveFolder() : artwork.folder();
        String extension = artwork.extensions();

        File imageFile;
        if (count == 1) {
            imageFile = Paths.get(dirPath, artworkId + "_p0." + extension).toFile();
        } else {
            String fileName = artworkId + "_p" + page;
            String[] extensions = extension.split(",");
            if (extensions.length > 1) {
                imageFile = findFileByName(dirPath, fileName);
                if (imageFile == null) {
                    return null;
                }
                extension = getFileExtension(imageFile.getName());
            } else {
                imageFile = Paths.get(dirPath, fileName + "." + extension).toFile();
            }
        }

        boolean isWebp = "webp".equals(extension);

        // WebP 完整图请求：直接返回原始字节，由 rawfile 端点处理更高效
        // 此处 thumbnail=false 路径保留为备用
        if (isWebp && !thumbnail) {
            byte[] fileBytes = Files.readAllBytes(imageFile.toPath());
            String base64Image = Base64.getEncoder().encodeToString(fileBytes);
            return new ImageResponse(true, base64Image, "webp", base64Image.length(), 0, 0, "成功获取动图");
        }

        // WebP 缩略图：使用伴随的 _p0_thumb.jpg 文件
        if (isWebp) {
            File thumbFile = Paths.get(dirPath, artworkId + "_p0_thumb.jpg").toFile();
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

        return new ImageResponse(true, base64Image, writeFormat, base64Image.length(), image.getWidth(), image.getHeight(), "成功获取图片缩略图");
    }

    public File getImageFile(Long artworkId, int page) {
        ArtworkRecord artwork = pixivDatabase.getArtwork(artworkId);
        if (artwork == null) return null;

        int count = artwork.count();
        if (count <= page || page < 0) return null;

        String dirPath = artwork.moved() ? artwork.moveFolder() : artwork.folder();
        String extension = artwork.extensions();

        File imageFile;
        if (count == 1) {
            imageFile = Paths.get(dirPath, artworkId + "_p0." + extension).toFile();
        } else {
            String fileName = artworkId + "_p" + page;
            String[] extensions = extension.split(",");
            if (extensions.length > 1) {
                imageFile = findFileByName(dirPath, fileName);
            } else {
                imageFile = Paths.get(dirPath, fileName + "." + extension).toFile();
            }
        }

        return (imageFile != null && imageFile.exists()) ? imageFile : null;
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
        File[] files = directory.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            return false;
        }

        Pattern artworkImagePattern = Pattern.compile("^" + artwork.artworkId() + "_p\\d+\\.[^.]+$",
                Pattern.CASE_INSENSITIVE);
        return Arrays.stream(files)
                .map(File::getName)
                .filter(artworkImagePattern.asMatchPredicate())
                .map(this::getFileExtension)
                .map(ext -> ext == null ? "" : ext.toLowerCase(Locale.ROOT))
                .anyMatch(IMAGE_EXTENSIONS::contains);
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
            log.info("删除无效下载记录: artworkId={}, path={}",
                    artwork.artworkId(), resolveArtworkDirectory(artwork));
        } catch (Exception e) {
            log.warn("删除无效下载记录失败: artworkId={}", artwork.artworkId(), e);
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
            log.error("记录统计信息失败: {}", e.getMessage(), e);
        }
    }

    public StatisticsResponse getStatistics() {
        int[] stats = pixivDatabase.getStats();
        return new StatisticsResponse(true, stats[0], stats[1], stats[2], "获取成功");
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
                throw new SecurityException("只允许 HTTPS 协议的下载 URL: " + url);
            }
            if (host == null || !host.endsWith(".pximg.net")) {
                throw new SecurityException("下载 URL 的域名不在白名单内: " + host);
            }
        } catch (URISyntaxException e) {
            throw new SecurityException("无效的下载 URL: " + url);
        }
    }
}
