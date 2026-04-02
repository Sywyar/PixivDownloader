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
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.download.db.ArtworkRecord;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
public class DownloadService {

    private final DownloadConfig downloadConfig;
    private final ApplicationEventPublisher eventPublisher;
    private final PixivDatabase pixivDatabase;
    private final UserQuotaService userQuotaService;
    private final RestTemplate downloadRestTemplate;
    private final TaskScheduler taskScheduler;
    private final PixivBookmarkService pixivBookmarkService;

    // 存储下载状态
    private final ConcurrentHashMap<Long, DownloadStatus> downloadStatusMap = new ConcurrentHashMap<>();

    public DownloadService(DownloadConfig downloadConfig,
                           ApplicationEventPublisher eventPublisher,
                           PixivDatabase pixivDatabase,
                           @Nullable UserQuotaService userQuotaService,
                           @Qualifier("downloadRestTemplate") RestTemplate downloadRestTemplate,
                           TaskScheduler taskScheduler,
                           PixivBookmarkService pixivBookmarkService) {
        this.downloadConfig = downloadConfig;
        this.eventPublisher = eventPublisher;
        this.pixivDatabase = pixivDatabase;
        this.userQuotaService = userQuotaService;
        this.downloadRestTemplate = downloadRestTemplate;
        this.taskScheduler = taskScheduler;
        this.pixivBookmarkService = pixivBookmarkService;
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
                // === 动图 (ugoira) 处理：下载ZIP → 提取帧 → ffmpeg 合成 WebP ===
                validatePixivUrl(other.getUgoiraZipUrl());
                fileExtensions.add("webp");
                status.setCurrentImageIndex(0);
                eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status));

                Path zipPath = downloadPath.resolve("_ugoira_frames.zip");
                Path tempDir = downloadPath.resolve("_frames_tmp");
                int ugoiraMaxAttempts = 3;
                for (int ugoiraAttempt = 1; ugoiraAttempt <= ugoiraMaxAttempts; ugoiraAttempt++) {
                    boolean ugoiraOk = false;
                    boolean fatalError = false;
                    try {
                        log.info("正在下载动图ZIP：作品 {}（尝试 {}/{}）", artworkId, ugoiraAttempt, ugoiraMaxAttempts);
                        boolean zipOk = downloadImage(other.getUgoiraZipUrl(), zipPath, referer, cookie);
                        if (!zipOk) {
                            log.error("作品：{}，动图ZIP下载失败(尝试 {}/{})", artworkId, ugoiraAttempt, ugoiraMaxAttempts);
                        } else {
                            Files.createDirectories(tempDir);

                            // 解压帧到临时目录，按文件名有序排列
                            TreeMap<String, Path> frameFiles = new TreeMap<>();
                            Path normalizedTempDir = tempDir.normalize();
                            try (ZipInputStream zis = new ZipInputStream(
                                    new FileInputStream(zipPath.toFile()), StandardCharsets.UTF_8)) {
                                ZipEntry entry;
                                while ((entry = zis.getNextEntry()) != null) {
                                    if (!entry.isDirectory()) {
                                        // Zip Slip 防护：确保解压路径不逃出 tempDir
                                        Path framePath = normalizedTempDir.resolve(entry.getName()).normalize();
                                        if (!framePath.startsWith(normalizedTempDir)) {
                                            log.warn("作品：{}，跳过危险ZIP条目（Zip Slip）: {}", artworkId, entry.getName());
                                            zis.closeEntry();
                                            continue;
                                        }
                                        try (FileOutputStream fos = new FileOutputStream(framePath.toFile())) {
                                            byte[] buf = new byte[8192];
                                            int len;
                                            while ((len = zis.read(buf)) != -1) fos.write(buf, 0, len);
                                        }
                                        frameFiles.put(entry.getName(), framePath);
                                    }
                                    zis.closeEntry();
                                }
                            }

                            if (frameFiles.isEmpty()) {
                                log.error("作品：{}，ZIP内无帧文件", artworkId);
                            } else {
                                List<Map.Entry<String, Path>> orderedFrames = new ArrayList<>(frameFiles.entrySet());
                                List<Integer> delays = other.getUgoiraDelays();
                                if (delays == null || delays.size() != orderedFrames.size()) {
                                    delays = Collections.nCopies(orderedFrames.size(), 100);
                                }

                                // 保存第一帧作为缩略图（供后端 thumbnail 接口使用）
                                Files.copy(orderedFrames.get(0).getValue(),
                                        downloadPath.resolve(artworkId + "_p0_thumb.jpg"),
                                        StandardCopyOption.REPLACE_EXISTING);

                                // 生成 ffmpeg concat 文件列表（支持可变帧延迟）
                                Path listFile = tempDir.resolve("frames.txt");
                                StringBuilder sb = new StringBuilder();
                                for (int i = 0; i < orderedFrames.size(); i++) {
                                    String fp = orderedFrames.get(i).getValue().toAbsolutePath()
                                            .toString().replace("\\", "/");
                                    sb.append("file '").append(fp).append("'\n");
                                    sb.append("duration ").append(delays.get(i) / 1000.0).append("\n");
                                }
                                // ffmpeg concat 需要重复最后一帧才能正确应用末帧时长
                                sb.append("file '").append(
                                        orderedFrames.get(orderedFrames.size() - 1).getValue()
                                                .toAbsolutePath().toString().replace("\\", "/"))
                                        .append("'\n");
                                Files.writeString(listFile, sb.toString(), StandardCharsets.UTF_8);

                                Path webpPath = downloadPath.resolve(artworkId + "_p0.webp");
                                ProcessBuilder pb = new ProcessBuilder(
                                        "ffmpeg", "-y",
                                        "-f", "concat", "-safe", "0",
                                        "-i", listFile.toAbsolutePath().toString(),
                                        "-vcodec", "libwebp",
                                        "-quality", "90",
                                        "-loop", "0",
                                        "-an",
                                        webpPath.toAbsolutePath().toString()
                                );
                                pb.redirectErrorStream(true);
                                Process process = pb.start();
                                process.getInputStream().transferTo(OutputStream.nullOutputStream());
                                int exitCode = process.waitFor();

                                if (exitCode == 0) {
                                    successCount.set(1);
                                    ugoiraOk = true;
                                } else {
                                    log.error("作品：{}，ffmpeg 执行失败，退出码：{}", artworkId, exitCode);
                                }
                            }
                        }
                    } catch (java.util.zip.ZipException e) {
                        log.warn("作品：{}，动图ZIP校验失败(尝试 {}/{})：{}，将重新下载",
                                artworkId, ugoiraAttempt, ugoiraMaxAttempts, e.getMessage());
                    } catch (Exception e) {
                        log.error("作品：{}，动图处理异常: {}", artworkId, e.getMessage(), e);
                        fatalError = true;
                    } finally {
                        try { Files.deleteIfExists(zipPath); } catch (Exception ignored) {}
                        try {
                            if (Files.exists(tempDir))
                                Files.walk(tempDir).sorted(Comparator.reverseOrder())
                                        .map(Path::toFile).forEach(File::delete);
                        } catch (Exception ignored) {}
                    }
                    if (ugoiraOk || fatalError) break;
                    if (ugoiraAttempt < ugoiraMaxAttempts) {
                        try { Thread.sleep(2000L * ugoiraAttempt); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

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
            recordDownload(artworkId, title, status.getDownloadPath(), fileExtensions, successCount.get(), other.isR18());
            recordStatistics(imageUrls.size());

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

    private void recordDownload(Long artworkId, String title, String folderPath, HashSet<String> fileExtensions, int count, boolean isR18) {
        try {
            long time = pixivDatabase.getUniqueTime();
            pixivDatabase.insertArtwork(
                    artworkId, title,
                    Path.of(folderPath).toAbsolutePath().toString(),
                    count, String.join(",", fileExtensions), time, isR18
            );
        } catch (Exception e) {
            log.error("记录下载历史失败: {}", e.getMessage(), e);
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
