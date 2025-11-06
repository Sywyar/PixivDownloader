package top.sywyar.pixivdownload.download;

import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DownloadService {

    @Autowired
    private DownloadConfig downloadConfig;

    // 存储下载状态
    private final ConcurrentHashMap<Long, DownloadStatus> downloadStatusMap = new ConcurrentHashMap<>();

    @Async
    public void downloadImages(Long artworkId, java.util.List<String> imageUrls, String referer, String cookie) {
        // 初始化下载状态
        DownloadStatus status = new DownloadStatus(artworkId, imageUrls.size());
        downloadStatusMap.put(artworkId, status);

        try {
            // 获取下一个文件夹索引
            int folderIndex = getNextFolderIndex();
            String folderName = String.valueOf(folderIndex);
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

            // 下载所有图片
            for (int i = 0; i < imageUrls.size(); i++) {
                if (status.isCancelled()) {
                    break; // 如果下载被取消，停止下载
                }

                String imageUrl = imageUrls.get(i);

                // 重要修复：在开始下载每张图片前更新当前图片索引
                status.setCurrentImageIndex(i);
                status.setDownloadedCount(successCount.get()); // 更新已下载数量

                try {
                    String extension = getFileExtension(imageUrl);
                    String filename = artworkId + "_" + i + "." + extension;
                    Path filePath = downloadPath.resolve(filename);

                    if (downloadImage(httpClient, imageUrl, filePath, referer, cookie)) {
                        successCount.incrementAndGet();
                        status.setDownloadedCount(successCount.get()); // 更新成功下载数量
                    }

                    // 延迟避免请求过快
                    Thread.sleep(downloadConfig.getDelayMs());

                } catch (Exception e) {
                    System.err.println("下载图片失败: " + imageUrl + ", 错误: " + e.getMessage());
                }
            }

            httpClient.close();

            // 记录下载信息
            recordDownload(artworkId, folderName, successCount.get());

            // 更新下载状态为完成
            status.setCompleted(true);
            status.setSuccessCount(successCount.get());
            status.setFailedCount(imageUrls.size() - successCount.get());
            status.setCurrentImageIndex(-1); // 完成后重置索引

            System.out.println("下载完成: 作品 " + artworkId +
                    ", 成功下载 " + successCount.get() + "/" + imageUrls.size() +
                    " 张图片到 " + downloadPath);

        } catch (Exception e) {
            System.err.println("下载过程出错: " + e.getMessage());
            e.printStackTrace();
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

    // 获取下载状态
    public DownloadStatus getDownloadStatus(Long artworkId) {
        return downloadStatusMap.get(artworkId);
    }

    // 取消下载
    public void cancelDownload(Long artworkId) {
        DownloadStatus status = downloadStatusMap.get(artworkId);
        if (status != null) {
            status.setCancelled(true);
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
                        System.err.println("HTTP错误: " + response.getStatusLine().getStatusCode() + " for " + imageUrl);
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
                System.err.println("下载失败: " + imageUrl + ", 错误: " + e.getMessage() +
                        ", 重试 " + retryCount + "/" + maxRetries);

                if (retryCount < maxRetries) {
                    try {
                        Thread.sleep(2000L * retryCount);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    System.err.println("重试次数用尽，放弃下载: " + imageUrl);
                    return false;
                }
            }
        }
        return false;
    }

    private String getFileExtension(String url) {
        if (!StringUtils.hasText(url)) return "jpg";
        String[] parts = url.split("\\.");
        return parts.length > 1 ? parts[parts.length - 1] : "jpg";
    }

    private synchronized int getNextFolderIndex() {
        try {
            Path indexFile = Paths.get(downloadConfig.getRootFolder(), "folder_index.txt");
            int index = 0;

            if (Files.exists(indexFile)) {
                String content = Files.readString(indexFile).trim();
                index = Integer.parseInt(content);
            }

            Files.writeString(indexFile, String.valueOf(index + 1));
            return index;

        } catch (Exception e) {
            System.err.println("获取文件夹索引失败: " + e.getMessage());
            return 0;
        }
    }

    private synchronized void recordDownload(Long artworkId, String folderName, int count) {
        try {
            Path recordFile = Paths.get(downloadConfig.getRootFolder(), "download_history.csv");
            String record = artworkId + "," + folderName + "," + count + "," +
                    java.time.LocalDateTime.now() + "\n";

            if (!Files.exists(recordFile)) {
                Files.writeString(recordFile, "artwork_id,folder,image_count,download_time\n");
            }

            Files.writeString(recordFile, record, java.nio.file.StandardOpenOption.APPEND);

        } catch (Exception e) {
            System.err.println("记录下载历史失败: " + e.getMessage());
        }
    }
}