package top.sywyar.pixivdownload.download;

import com.sywyar.superjsonobject.SuperJsonObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.download.response.ThumbnailResponse;
import top.sywyar.pixivdownload.imageclassifier.ThumbnailManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class DownloadService {

    @Autowired
    private DownloadConfig downloadConfig;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    // 存储下载状态
    private final ConcurrentHashMap<Long, DownloadStatus> downloadStatusMap = new ConcurrentHashMap<>();

    private SuperJsonObject download_history;

    @Async
    public void downloadImages(Long artworkId, String title, java.util.List<String> imageUrls, String referer, String cookie) {
        // 初始化下载状态
        DownloadStatus status = new DownloadStatus(artworkId, imageUrls.size());
        downloadStatusMap.put(artworkId, status);

        // 发送初始状态更新
        eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status));

        try {
            // 获取下一个文件夹索引
            /*int folderIndex = getNextFolderIndex();
            String folderName = String.valueOf(folderIndex == Integer.MAX_VALUE ? "temp" : folderIndex);*/
            String folderName = String.valueOf(artworkId);
            status.setFolderName(folderName);

            // 创建文件夹结构
            Path downloadPath = Paths.get(downloadConfig.getRootFolder(), folderName);
            Files.createDirectories(downloadPath);
            status.setDownloadPath(downloadPath.toString());

            AtomicInteger successCount = new AtomicInteger(0);

            // 优化 HttpClient 配置
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectTimeout(30000)
                    .setSocketTimeout(60000)
                    .setConnectionRequestTimeout(30000)
                    .build();

            HttpHost proxy = new HttpHost("127.0.0.1", 7890);

            CloseableHttpClient httpClient = HttpClients.custom()
                    .setDefaultRequestConfig(requestConfig)
                    .setMaxConnTotal(20)
                    .setMaxConnPerRoute(10)
                    .setProxy(proxy)
                    .build();

            HashSet<String> fileExtensions = new HashSet<>();

            // 下载所有图片
            for (int i = 0; i < imageUrls.size(); i++) {
                if (status.isCancelled()) {
                    break; // 如果下载被取消，停止下载
                }

                String imageUrl = imageUrls.get(i);

                //在开始下载每张图片前更新当前图片索引
                status.setCurrentImageIndex(i);
                status.setDownloadedCount(successCount.get()); // 更新已下载数量

                // 发送图片开始下载状态更新
                eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status));

                try {
                    String extension = getFileExtension(imageUrl);
                    fileExtensions.add(extension);
                    String filename = artworkId + "_p" + i + "." + extension;
                    Path filePath = downloadPath.resolve(filename);
                    if (downloadImage(httpClient, imageUrl, filePath, referer, cookie)) {
                        successCount.incrementAndGet();
                        status.setDownloadedCount(successCount.get()); // 更新成功下载数量

                        log.info("作品：{}，下载进度：{}/{}", artworkId, successCount.get(), imageUrls.size());

                        // 发送图片下载完成状态更新
                        eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status));
                    }

                    // 延迟避免请求过快
                    Thread.sleep(downloadConfig.getDelayMs());

                } catch (Exception e) {
                    System.err.println("下载图片失败: " + imageUrl + ", 错误: " + e.getMessage());
                }
            }

            httpClient.close();

            // 记录下载信息
            recordDownload(artworkId, title, status.getDownloadPath(), fileExtensions, successCount.get());

            // 更新下载状态为完成
            status.setCompleted(true);
            status.setSuccessCount(successCount.get());
            status.setFailedCount(imageUrls.size() - successCount.get());
            status.setCurrentImageIndex(-1); // 完成后重置索引

            log.info("下载完成: 作品 {}, 成功下载 {}/{} 张图片到 {}", artworkId, successCount.get(), imageUrls.size(), downloadPath);

            // 发送最终完成状态更新
            eventPublisher.publishEvent(new DownloadProgressEvent(this, artworkId, status));

        } catch (Exception e) {
            log.error("下载出错", e);
            status.setCompleted(true);
            status.setFailed(true);
            status.setErrorMessage(e.getMessage());
        } finally {
            // 下载完成后，保留状态一段时间供查询，然后清理
            new Thread(() -> {
                try {
                    Thread.sleep(300000); // 保留5分钟
                    downloadStatusMap.remove(artworkId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }

    private boolean downloadImage(CloseableHttpClient httpClient, String imageUrl, Path filePath, String referer, String cookie) {
        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                HttpGet request = new HttpGet(imageUrl);
                request.setHeader("Referer", referer);
                request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

                // 添加Cookie到请求头
                if (cookie != null && !cookie.trim().isEmpty()) {
                    request.setHeader("Cookie", cookie);
                }

                // 设置超时
                RequestConfig config = RequestConfig.custom()
                        .setConnectTimeout(30000)
                        .setSocketTimeout(60000)
                        .build();
                request.setConfig(config);

                try (CloseableHttpResponse response = httpClient.execute(request);
                     InputStream inputStream = response.getEntity().getContent();
                     FileOutputStream outputStream = new FileOutputStream(filePath.toFile())) {

                    // 检查响应状态
                    if (response.getStatusLine().getStatusCode() != 200) {
                        log.error("HTTP错误: {} for {}", response.getStatusLine().getStatusCode(), imageUrl);
                        retryCount++;
                        continue;
                    }

                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }

                    return true;
                }
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

    private synchronized int getNextFolderIndex() {
        Path indexFile = Paths.get(downloadConfig.getRootFolder());
        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            if (!Files.exists(indexFile.resolve(String.valueOf(i)))) {
                return i;
            }
        }
        log.error("获取下一个文件夹失败，使用temp文件夹");
        return Integer.MAX_VALUE;
    }

    @Async("downloadHistoryFileTaskExecutor")
    protected void initDownloadHistory() throws IOException {
        if (download_history == null) {
            Path recordFile = Paths.get(downloadConfig.getRootFolder(), "download_history.json");
            if (!Files.exists(recordFile)) {
                Files.createFile(recordFile);
                SuperJsonObject.Writer((new SuperJsonObject()).toString(), recordFile.toString());
            }
            download_history = new SuperJsonObject(recordFile.toFile());
        }
    }

    @Async("downloadHistoryFileTaskExecutor")
    protected void recordDownload(Long artworkId, String title, String folderPath, HashSet<String> fileExtensions, int count) {
        try {
            initDownloadHistory();

            SuperJsonObject downloaded = download_history.getOrDefault("downloaded", new SuperJsonObject());

            SuperJsonObject artwork = new SuperJsonObject();
            artwork.addProperty("title", title);
            artwork.addProperty("folder", Path.of(folderPath).toAbsolutePath().toString());
            artwork.addProperty("count", count);
            artwork.addProperty("extensions", String.join(",", fileExtensions));
            artwork.addProperty("time", System.currentTimeMillis() / 1000);

            downloaded.add(String.valueOf(artworkId), artwork);
            download_history.add("downloaded", downloaded);

            download_history.save();
        } catch (Exception e) {
            log.error("记录下载历史失败: {}", e.getMessage(), e);
        }

    }

    @Async("downloadHistoryFileTaskExecutor")
    public void moveArtWork(Long artworkId, String movePath, Long moveTime) {
        try {
            initDownloadHistory();
            SuperJsonObject downloaded = download_history.getOrDefault("downloaded", new SuperJsonObject());
            if (!downloaded.has(String.valueOf(artworkId))) {
                return;
            }

            SuperJsonObject artwork = downloaded.getAsSuperJsonObject(String.valueOf(artworkId));
            artwork.addProperty("moved", true);
            artwork.addProperty("moveFolder", movePath);
            artwork.addProperty("moveTime", moveTime);

            downloaded.add(String.valueOf(artworkId), artwork);
            download_history.add("downloaded", downloaded);

            download_history.save();

        } catch (Exception e) {
            log.error("移动记录失败: {}", e.getMessage(), e);
        }
    }


    public List<String> getDownloadedRecord() {
        try {
            initDownloadHistory();
            List<String> downloaded = new LinkedList<>();

            SuperJsonObject downloadedRecord = download_history.deepCopy().getOrDefault("downloaded", new SuperJsonObject());

            downloadedRecord.asMap().forEach((k, v) -> downloaded.add(String.valueOf(k)));

            return downloaded;
        } catch (IOException e) {
            log.error("获取历史下载错误：: {}", e.getMessage(), e);
            return new LinkedList<>();
        }
    }

    public SuperJsonObject getDownloadedRecord(Long artworkId) {
        try {
            initDownloadHistory();

            SuperJsonObject downloaded = download_history.deepCopy().getOrDefault("downloaded", new SuperJsonObject());
            return downloaded.getOrDefault(String.valueOf(artworkId), null);
        } catch (IOException e) {
            log.error("作品：{}，下载历史获取失败", artworkId, e);
            return null;
        }
    }

    public ThumbnailResponse getThumbnail(Long artworkId, int page) {
        try {
            initDownloadHistory();

            SuperJsonObject downloaded = download_history.deepCopy().getOrDefault("downloaded", new SuperJsonObject());
            if (!downloaded.has(String.valueOf(artworkId))) {
                throw new RuntimeException("找不到作品");
            }

            SuperJsonObject artwork = downloaded.getAsSuperJsonObject(String.valueOf(artworkId));

            String dirPath;
            if (artwork.has("moved") && artwork.getAsBoolean("moved")) {
                dirPath = artwork.getAsString("moveFolder");
            } else {
                dirPath = artwork.getAsString("folder");
            }

            File imageFile;
            String extension = artwork.getAsString("extensions");
            if (artwork.getAsInt("count") == 1) {
                if (page != 0) {
                    return new ThumbnailResponse(false, null, null, 0, 0, 0, artworkId + "作品没有第" + page + "页");
                }

                imageFile = Paths.get(dirPath, artworkId + "_p0." + extension).toFile();
            } else {
                String fileName = artworkId + "_p" + page;

                String[] extensions = extension.split(",");
                if (extensions.length > 1) {
                    imageFile = findFileByName(dirPath, fileName);
                    if (imageFile == null) {
                        return new ThumbnailResponse(false, null, null, 0, 0, 0, artworkId + "作品找不到" + fileName);
                    }
                    extension = getFileExtension(imageFile.getName());
                } else {
                    imageFile = Paths.get(dirPath, fileName + "." + extension).toFile();
                }
            }
            BufferedImage image = ThumbnailManager.getThumbnail(imageFile, -1, -1);

            ByteArrayOutputStream bass = new ByteArrayOutputStream();
            ImageIO.write(image, extension, bass);

            String base64Image = Base64.getEncoder().encodeToString(bass.toByteArray());

            return new ThumbnailResponse(true, base64Image, extension, base64Image.length(), image.getWidth(), image.getHeight(), "成功获取图片缩略图");
        } catch (IOException e) {
            log.error("获取缩略图失败，作品：{}，页码：{}，原因：{}", artworkId, page, e.getMessage(), e);
            return new ThumbnailResponse(false, null, null, 0, 0, 0, "获取缩略图失败，原因:" + e.getMessage());
        }
    }

    public static File findFileByName(String directoryPath, String fileName) {
        File directory = new File(directoryPath);

        if (!directory.exists() || !directory.isDirectory()) {
            return null;
        }

        // 列出目录中的所有文件
        File[] files = directory.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    // 获取不带扩展名的文件名
                    String baseName = getBaseName(file.getName());
                    // 如果匹配，返回该文件
                    if (baseName.equals(fileName)) {
                        return file;
                    }
                }
            }
        }
        return null;
    }

    private static String getBaseName(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex);
        }
        return fileName;
    }

}