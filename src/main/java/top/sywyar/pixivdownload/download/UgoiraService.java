package top.sywyar.pixivdownload.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.ffmpeg.FfmpegInstallation;
import top.sywyar.pixivdownload.ffmpeg.FfmpegLocator;
import top.sywyar.pixivdownload.download.request.DownloadRequest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 动图（Ugoira）处理服务：下载 ZIP → 提取帧 → ffmpeg 合成 WebP。
 */
@Slf4j
@Service
public class UgoiraService {

    private final RestTemplate downloadRestTemplate;

    public UgoiraService(@Qualifier("downloadRestTemplate") RestTemplate downloadRestTemplate) {
        this.downloadRestTemplate = downloadRestTemplate;
    }

    /**
     * 处理动图并写出到 downloadPath。
     *
     * @return 1 表示成功，0 表示失败
     */
    public int processUgoira(Long artworkId, DownloadRequest.Other other,
                             Path downloadPath, String referer, String cookie) {
        DownloadService.validatePixivUrl(other.getUgoiraZipUrl());

        Path zipPath = downloadPath.resolve("_ugoira_frames.zip");
        Path tempDir = downloadPath.resolve("_frames_tmp");
        int maxAttempts = 3;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("正在下载动图ZIP：作品 {}（尝试 {}/{}）", artworkId, attempt, maxAttempts);
                if (!downloadZip(other.getUgoiraZipUrl(), zipPath, referer, cookie)) {
                    log.error("作品：{}，动图ZIP下载失败(尝试 {}/{})", artworkId, attempt, maxAttempts);
                    continue;
                }

                Files.createDirectories(tempDir);
                TreeMap<String, Path> frameFiles = extractFrames(artworkId, zipPath, tempDir);
                if (frameFiles.isEmpty()) {
                    log.error("作品：{}，ZIP内无帧文件", artworkId);
                    continue;
                }

                List<Map.Entry<String, Path>> orderedFrames = new ArrayList<>(frameFiles.entrySet());
                List<Integer> delays = resolveDelays(other.getUgoiraDelays(), orderedFrames.size());

                // 保存第一帧作为缩略图（供后端 thumbnail 接口使用）
                Files.copy(orderedFrames.get(0).getValue(),
                        downloadPath.resolve(artworkId + "_p0_thumb.jpg"),
                        StandardCopyOption.REPLACE_EXISTING);

                if (runFfmpeg(artworkId, orderedFrames, delays, tempDir, downloadPath)) {
                    return 1;
                }

            } catch (java.util.zip.ZipException e) {
                log.warn("作品：{}，动图ZIP校验失败(尝试 {}/{})：{}，将重新下载",
                        artworkId, attempt, maxAttempts, e.getMessage());
            } catch (Exception e) {
                log.error("作品：{}，动图处理异常: {}", artworkId, e.getMessage(), e);
                break; // 非ZIP格式异常不重试
            } finally {
                cleanup(zipPath, tempDir);
            }

            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return 0;
    }

    private TreeMap<String, Path> extractFrames(Long artworkId, Path zipPath, Path tempDir) throws IOException {
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
        return frameFiles;
    }

    private List<Integer> resolveDelays(List<Integer> delays, int frameCount) {
        if (delays == null || delays.size() != frameCount) {
            return Collections.nCopies(frameCount, 100);
        }
        return delays;
    }

    /**
     * 自动检测 ffmpeg 路径：优先 PATH，其次应用根目录（jpackage 打包场景）。
     */
    private String detectFfmpegCommand() {
        var installation = FfmpegLocator.locate();
        if (installation.isPresent()) {
            FfmpegInstallation ffmpegInstallation = installation.get();
            log.info("使用{} FFmpeg：{}", ffmpegInstallation.sourceLabel(), ffmpegInstallation.ffmpegPath());
            return ffmpegInstallation.ffmpegPath().toString();
        }

        log.warn("未检测到 FFmpeg，将尝试直接调用 'ffmpeg'，请确保已安装或配置 ffmpeg.path");
        return FfmpegLocator.fallbackCommand();
    }

    private boolean runFfmpeg(Long artworkId, List<Map.Entry<String, Path>> orderedFrames,
                              List<Integer> delays, Path tempDir, Path downloadPath) throws Exception {
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
        String ffmpegCommand = detectFfmpegCommand();
        ProcessBuilder pb = new ProcessBuilder(
                ffmpegCommand, "-y",
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

        if (exitCode != 0) {
            log.error("作品：{}，ffmpeg 执行失败，退出码：{}", artworkId, exitCode);
            return false;
        }
        return true;
    }

    private boolean downloadZip(String url, Path path, String referer, String cookie) {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Boolean success = downloadRestTemplate.execute(url, HttpMethod.GET,
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
                                log.error("HTTP错误: {} for {}", response.getStatusCode(), url);
                                return false;
                            }
                            try (InputStream in = response.getBody();
                                 FileOutputStream out = new FileOutputStream(path.toFile())) {
                                byte[] buf = new byte[8192];
                                int len;
                                while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                            }
                            return true;
                        });
                if (Boolean.TRUE.equals(success)) return true;
            } catch (Exception e) {
                log.error("ZIP下载失败：{}，错误：{}，重试：{}/{}", url, e.getMessage(), attempt, maxRetries);
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private void cleanup(Path zipPath, Path tempDir) {
        try { Files.deleteIfExists(zipPath); } catch (Exception ignored) {}
        try {
            if (Files.exists(tempDir)) {
                Files.walk(tempDir).sorted(Comparator.reverseOrder())
                        .map(Path::toFile).forEach(File::delete);
            }
        } catch (Exception ignored) {}
    }
}
