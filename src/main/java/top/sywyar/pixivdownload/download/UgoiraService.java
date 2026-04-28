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
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 动图（Ugoira）处理服务：下载 ZIP → 提取帧 → ffmpeg 合成 WebP。
 */
@Slf4j
@Service
public class UgoiraService {

    private final RestTemplate downloadRestTemplate;
    private final AppMessages messages;

    public UgoiraService(@Qualifier("downloadRestTemplate") RestTemplate downloadRestTemplate,
                         AppMessages messages) {
        this.downloadRestTemplate = downloadRestTemplate;
        this.messages = messages;
    }

    /**
     * 处理动图并写出到 downloadPath。
     *
     * @return 1 表示成功，0 表示失败
     */
    public int processUgoira(Long artworkId, DownloadRequest.Other other,
                             Path downloadPath, String referer, String cookie) {
        return processUgoira(artworkId, other, downloadPath, referer, cookie, null);
    }

    public int processUgoira(Long artworkId, DownloadRequest.Other other,
                             Path downloadPath, String referer, String cookie,
                             Consumer<UgoiraProgress> progressListener) {
        DownloadService.validatePixivUrl(other.getUgoiraZipUrl());
        String outputBaseName = resolveOutputBaseName(artworkId, other);

        Path zipPath = downloadPath.resolve("_ugoira_frames.zip");
        Path tempDir = downloadPath.resolve("_frames_tmp");
        int maxAttempts = 3;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info(message("ugoira.log.zip.download.started", id(artworkId), text(attempt), text(maxAttempts)));
                publishProgress(progressListener, UgoiraProgress.builder()
                        .phase(UgoiraProgress.PHASE_ZIP)
                        .status(UgoiraProgress.STATUS_RUNNING)
                        .attempt(attempt)
                        .maxAttempts(maxAttempts)
                        .zipDownloadedBytes(0L)
                        .zipProgress(0)
                        .build());
                if (!downloadZip(other.getUgoiraZipUrl(), zipPath, referer, cookie, attempt, maxAttempts, progressListener)) {
                    log.error(message("ugoira.log.zip.download.failed", id(artworkId), text(attempt), text(maxAttempts)));
                    continue;
                }

                Files.createDirectories(tempDir);
                int expectedFrames = other.getUgoiraDelays() == null ? 0 : other.getUgoiraDelays().size();
                publishProgress(progressListener, UgoiraProgress.builder()
                        .phase(UgoiraProgress.PHASE_EXTRACT)
                        .status(UgoiraProgress.STATUS_RUNNING)
                        .attempt(attempt)
                        .maxAttempts(maxAttempts)
                        .zipProgress(100)
                        .extractedFrames(0)
                        .totalFrames(expectedFrames > 0 ? expectedFrames : null)
                        .build());
                TreeMap<String, Path> frameFiles = extractFrames(
                        artworkId, zipPath, tempDir, progressListener, expectedFrames, attempt, maxAttempts);
                if (frameFiles.isEmpty()) {
                    log.error(message("ugoira.log.zip.empty", id(artworkId)));
                    continue;
                }

                List<Map.Entry<String, Path>> orderedFrames = new ArrayList<>(frameFiles.entrySet());
                List<Integer> delays = resolveDelays(other.getUgoiraDelays(), orderedFrames.size());

                // 保存第一帧作为缩略图（供后端 thumbnail 接口使用）
                Files.copy(orderedFrames.get(0).getValue(),
                        downloadPath.resolve(outputBaseName + "_thumb.jpg"),
                        StandardCopyOption.REPLACE_EXISTING);

                if (runFfmpeg(artworkId, orderedFrames, delays, tempDir, downloadPath,
                        outputBaseName, attempt, maxAttempts, progressListener)) {
                    return 1;
                }

            } catch (java.util.zip.ZipException e) {
                log.warn(message("ugoira.log.zip.invalid",
                        id(artworkId), text(attempt), text(maxAttempts), e.getMessage()));
            } catch (Exception e) {
                log.error(message("ugoira.log.processing.failed", id(artworkId), e.getMessage()), e);
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
        publishProgress(progressListener, UgoiraProgress.builder()
                .phase(UgoiraProgress.PHASE_FFMPEG)
                .status(UgoiraProgress.STATUS_FAILED)
                .build());
        return 0;
    }

    private TreeMap<String, Path> extractFrames(Long artworkId, Path zipPath, Path tempDir,
                                                Consumer<UgoiraProgress> progressListener,
                                                int expectedFrames, int attempt, int maxAttempts) throws IOException {
        TreeMap<String, Path> frameFiles = new TreeMap<>();
        Path normalizedTempDir = tempDir.normalize();
        int[] lastProgress = {-1};
        long[] lastAt = {0L};
        try (ZipInputStream zis = new ZipInputStream(
                new FileInputStream(zipPath.toFile()), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    // Zip Slip 防护：确保解压路径不逃出 tempDir
                    Path framePath = normalizedTempDir.resolve(entry.getName()).normalize();
                    if (!framePath.startsWith(normalizedTempDir)) {
                        log.warn(message("ugoira.log.zip-entry.unsafe", id(artworkId), entry.getName()));
                        zis.closeEntry();
                        continue;
                    }
                    try (FileOutputStream fos = new FileOutputStream(framePath.toFile())) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = zis.read(buf)) != -1) fos.write(buf, 0, len);
                    }
                    frameFiles.put(entry.getName(), framePath);
                    Integer progress = expectedFrames > 0
                            ? Math.min(100, (int) Math.round(frameFiles.size() * 100.0 / expectedFrames))
                            : null;
                    if (shouldEmitStepProgress(progress, lastProgress, lastAt)) {
                        publishProgress(progressListener, UgoiraProgress.builder()
                                .phase(UgoiraProgress.PHASE_EXTRACT)
                                .status(UgoiraProgress.STATUS_RUNNING)
                                .attempt(attempt)
                                .maxAttempts(maxAttempts)
                                .zipProgress(100)
                                .extractedFrames(frameFiles.size())
                                .totalFrames(expectedFrames > 0 ? expectedFrames : null)
                                .build());
                    }
                }
                zis.closeEntry();
            }
        }
        publishProgress(progressListener, UgoiraProgress.builder()
                .phase(UgoiraProgress.PHASE_EXTRACT)
                .status(UgoiraProgress.STATUS_COMPLETED)
                .attempt(attempt)
                .maxAttempts(maxAttempts)
                .zipProgress(100)
                .extractedFrames(frameFiles.size())
                .totalFrames(expectedFrames > 0 ? expectedFrames : frameFiles.size())
                .build());
        return frameFiles;
    }

    private List<Integer> resolveDelays(List<Integer> delays, int frameCount) {
        if (delays == null || delays.size() != frameCount) {
            return Collections.nCopies(frameCount, 100);
        }
        return delays;
    }

    private String resolveOutputBaseName(Long artworkId, DownloadRequest.Other other) {
        if (other != null && other.getFileNames() != null && !other.getFileNames().isEmpty()) {
            return other.getFileNames().get(0);
        }
        return artworkId + "_p0";
    }

    /**
     * 自动检测 ffmpeg 路径：优先 PATH，其次应用根目录（jpackage 打包场景）。
     */
    private String detectFfmpegCommand() {
        var installation = FfmpegLocator.locate();
        if (installation.isPresent()) {
            FfmpegInstallation ffmpegInstallation = installation.get();
            log.info(message("ugoira.log.ffmpeg.detected",
                    message(ffmpegInstallation.sourceMessageCode()), ffmpegInstallation.ffmpegPath()));
            return ffmpegInstallation.ffmpegPath().toString();
        }

        log.warn(message("ugoira.log.ffmpeg.missing"));
        return FfmpegLocator.fallbackCommand();
    }

    private boolean runFfmpeg(Long artworkId, List<Map.Entry<String, Path>> orderedFrames,
                              List<Integer> delays, Path tempDir, Path downloadPath,
                              String outputBaseName, int attempt, int maxAttempts,
                              Consumer<UgoiraProgress> progressListener) throws Exception {
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

        Path webpPath = downloadPath.resolve(outputBaseName + ".webp");
        String ffmpegCommand = detectFfmpegCommand();
        long durationMs = Math.max(1L, delays.stream().mapToLong(Integer::longValue).sum());
        publishProgress(progressListener, UgoiraProgress.builder()
                .phase(UgoiraProgress.PHASE_FFMPEG)
                .status(UgoiraProgress.STATUS_RUNNING)
                .attempt(attempt)
                .maxAttempts(maxAttempts)
                .zipProgress(100)
                .extractedFrames(orderedFrames.size())
                .totalFrames(orderedFrames.size())
                .ffmpegOutTimeMs(0L)
                .ffmpegDurationMs(durationMs)
                .ffmpegProgress(0)
                .build());
        ProcessBuilder pb = new ProcessBuilder(
                ffmpegCommand, "-y",
                "-nostats",
                "-stats_period", "0.5",
                "-progress", "pipe:1",
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
        int[] lastProgress = {-1};
        long[] lastAt = {0L};
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Long outTimeMs = parseFfmpegOutTimeMs(line);
                if (outTimeMs == null) {
                    continue;
                }
                int progress = Math.min(99, Math.max(0,
                        (int) Math.round(outTimeMs * 100.0 / durationMs)));
                if (shouldEmitStepProgress(progress, lastProgress, lastAt)) {
                    publishProgress(progressListener, UgoiraProgress.builder()
                            .phase(UgoiraProgress.PHASE_FFMPEG)
                            .status(UgoiraProgress.STATUS_RUNNING)
                            .attempt(attempt)
                            .maxAttempts(maxAttempts)
                            .zipProgress(100)
                            .extractedFrames(orderedFrames.size())
                            .totalFrames(orderedFrames.size())
                            .ffmpegOutTimeMs(Math.min(outTimeMs, durationMs))
                            .ffmpegDurationMs(durationMs)
                            .ffmpegProgress(progress)
                            .build());
                }
            }
        }
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            log.error(message("ugoira.log.ffmpeg.failed", id(artworkId), text(exitCode)));
            publishProgress(progressListener, UgoiraProgress.builder()
                    .phase(UgoiraProgress.PHASE_FFMPEG)
                    .status(UgoiraProgress.STATUS_FAILED)
                    .attempt(attempt)
                    .maxAttempts(maxAttempts)
                    .zipProgress(100)
                    .extractedFrames(orderedFrames.size())
                    .totalFrames(orderedFrames.size())
                    .ffmpegDurationMs(durationMs)
                    .ffmpegProgress(lastProgress[0] < 0 ? 0 : lastProgress[0])
                    .build());
            return false;
        }
        publishProgress(progressListener, UgoiraProgress.builder()
                .phase(UgoiraProgress.PHASE_FFMPEG)
                .status(UgoiraProgress.STATUS_COMPLETED)
                .attempt(attempt)
                .maxAttempts(maxAttempts)
                .zipProgress(100)
                .extractedFrames(orderedFrames.size())
                .totalFrames(orderedFrames.size())
                .ffmpegOutTimeMs(durationMs)
                .ffmpegDurationMs(durationMs)
                .ffmpegProgress(100)
                .build());
        return true;
    }

    private boolean downloadZip(String url, Path path, String referer, String cookie,
                                int outerAttempt, int outerMaxAttempts,
                                Consumer<UgoiraProgress> progressListener) {
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
                                log.error(message("ugoira.log.http-error", response.getStatusCode(), url));
                                return false;
                            }
                            long totalBytes = response.getHeaders().getContentLength();
                            long[] downloadedBytes = {0L};
                            int[] lastProgress = {-1};
                            long[] lastBytes = {0L};
                            long[] lastAt = {0L};
                            try (InputStream in = response.getBody();
                                 FileOutputStream out = new FileOutputStream(path.toFile())) {
                                byte[] buf = new byte[8192];
                                int len;
                                while ((len = in.read(buf)) != -1) {
                                    out.write(buf, 0, len);
                                    downloadedBytes[0] += len;
                                    Integer progress = totalBytes > 0
                                            ? Math.min(99, (int) (downloadedBytes[0] * 100 / totalBytes))
                                            : null;
                                    if (shouldEmitByteProgress(progress, downloadedBytes[0], lastProgress, lastBytes, lastAt)) {
                                        publishProgress(progressListener, UgoiraProgress.builder()
                                                .phase(UgoiraProgress.PHASE_ZIP)
                                                .status(UgoiraProgress.STATUS_RUNNING)
                                                .attempt(outerAttempt)
                                                .maxAttempts(outerMaxAttempts)
                                                .zipDownloadedBytes(downloadedBytes[0])
                                                .zipTotalBytes(totalBytes > 0 ? totalBytes : null)
                                                .zipProgress(progress)
                                                .build());
                                    }
                                }
                            }
                            publishProgress(progressListener, UgoiraProgress.builder()
                                    .phase(UgoiraProgress.PHASE_ZIP)
                                    .status(UgoiraProgress.STATUS_COMPLETED)
                                    .attempt(outerAttempt)
                                    .maxAttempts(outerMaxAttempts)
                                    .zipDownloadedBytes(downloadedBytes[0])
                                    .zipTotalBytes(totalBytes > 0 ? totalBytes : null)
                                    .zipProgress(100)
                                    .build());
                            return true;
                        });
                if (Boolean.TRUE.equals(success)) return true;
            } catch (Exception e) {
                log.error(message("ugoira.log.zip.retry", url, e.getMessage(), attempt, maxRetries));
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

    private void publishProgress(Consumer<UgoiraProgress> progressListener, UgoiraProgress progress) {
        if (progressListener != null) {
            progressListener.accept(progress);
        }
    }

    private boolean shouldEmitByteProgress(Integer progress, long bytes,
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

    private boolean shouldEmitStepProgress(Integer progress, int[] lastProgress, long[] lastAt) {
        long now = System.currentTimeMillis();
        int currentProgress = progress == null ? -1 : progress;
        if (currentProgress != lastProgress[0] || now - lastAt[0] >= 1000) {
            lastProgress[0] = currentProgress;
            lastAt[0] = now;
            return true;
        }
        return false;
    }

    private Long parseFfmpegOutTimeMs(String line) {
        if (line == null) {
            return null;
        }
        if (line.startsWith("out_time_ms=")) {
            try {
                return Math.max(0L, Long.parseLong(line.substring("out_time_ms=".length()).trim()) / 1000L);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (!line.startsWith("out_time=")) {
            return null;
        }
        String value = line.substring("out_time=".length()).trim();
        String[] parts = value.split(":");
        if (parts.length != 3) {
            return null;
        }
        try {
            long hours = Long.parseLong(parts[0]);
            long minutes = Long.parseLong(parts[1]);
            double seconds = Double.parseDouble(parts[2]);
            return Math.max(0L, (long) (((hours * 60 + minutes) * 60 + seconds) * 1000));
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    private String message(String code, Object... args) {
        return messages.getForLog(code, args);
    }

    private String id(Long value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private String text(int value) {
        return String.valueOf(value);
    }
}
