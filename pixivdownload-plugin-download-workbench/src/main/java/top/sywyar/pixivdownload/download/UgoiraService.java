package top.sywyar.pixivdownload.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegCommandResolver;
import top.sywyar.pixivdownload.core.ffmpeg.ResolvedFfmpegCommand;
import top.sywyar.pixivdownload.core.pixiv.PixivImageDownloader;
import top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * 动图（Ugoira）处理服务：下载 ZIP → 提取帧 → ffmpeg 合成 WebP。
 */
@Slf4j
@Service
public class UgoiraService {

    private static final URI DEFAULT_PIXIV_REFERER = URI.create("https://www.pixiv.net/");
    private static final long MIB = 1024L * 1024L;
    static final long MAX_ZIP_BYTES = 100L * MIB;
    // Pixiv 当前两种 Ugoira 投稿方式最多覆盖 500 帧；其余数值是本地固定安全预算。
    static final int MAX_FRAME_COUNT = 500;
    static final int MAX_ZIP_ENTRIES = MAX_FRAME_COUNT;
    static final long MAX_ZIP_ENTRY_BYTES = 32L * MIB;
    static final long MAX_ZIP_UNCOMPRESSED_BYTES = 2L * MAX_ZIP_BYTES;
    static final long MAX_ZIP_COMPRESSION_RATIO = 100L;
    static final long MAX_FRAME_PIXELS = 25_000_000L;
    static final Duration FFMPEG_TIMEOUT = Duration.ofMinutes(10);
    static final long MAX_FFMPEG_OUTPUT_BYTES = MAX_ZIP_BYTES;
    static final int MAX_FFMPEG_PROCESSES = 1;

    private final PixivImageDownloader pixivImageDownloader;
    private final FfmpegCommandResolver ffmpegCommandResolver;
    private final MessageResolver messages;
    private final Semaphore ffmpegPermits = new Semaphore(MAX_FFMPEG_PROCESSES, true);

    public UgoiraService(PixivImageDownloader pixivImageDownloader,
                         FfmpegCommandResolver ffmpegCommandResolver,
                         MessageResolver messages) {
        this.pixivImageDownloader = pixivImageDownloader;
        this.ffmpegCommandResolver = ffmpegCommandResolver;
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
        return processUgoira(artworkId, other, downloadPath, referer, cookie, progressListener, () -> false);
    }

    public int processUgoira(Long artworkId, DownloadRequest.Other other,
                             Path downloadPath, String referer, String cookie,
                             Consumer<UgoiraProgress> progressListener,
                             BooleanSupplier cancellationRequested) {
        ArtworkDownloadExecutor.validatePixivUrl(other.getUgoiraZipUrl());
        String outputBaseName = resolveOutputBaseName(artworkId, other);

        Path zipPath = downloadPath.resolve("_ugoira_frames.zip");
        Path tempDir = downloadPath.resolve("_frames_tmp");
        Path partialOutput = partialOutputPath(downloadPath, outputBaseName);
        int maxAttempts = 3;
        cleanup(zipPath, tempDir, partialOutput);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ensureNotCancelled(cancellationRequested);
                log.info(message("ugoira.log.zip.download.started", id(artworkId), text(attempt), text(maxAttempts)));
                publishProgress(progressListener, UgoiraProgress.builder()
                        .phase(UgoiraProgress.PHASE_ZIP)
                        .status(UgoiraProgress.STATUS_RUNNING)
                        .attempt(attempt)
                        .maxAttempts(maxAttempts)
                        .zipDownloadedBytes(0L)
                        .zipProgress(0)
                        .build());
                if (!downloadZip(other.getUgoiraZipUrl(), zipPath, referer, cookie, attempt, maxAttempts,
                        progressListener, cancellationRequested)) {
                    log.error(message("ugoira.log.zip.download.failed", id(artworkId), text(attempt), text(maxAttempts)));
                    continue;
                }
                ensureNotCancelled(cancellationRequested);

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
                        artworkId, zipPath, tempDir, progressListener, expectedFrames, attempt, maxAttempts,
                        cancellationRequested);
                if (frameFiles.isEmpty()) {
                    log.error(message("ugoira.log.zip.empty", id(artworkId)));
                    continue;
                }
                ensureNotCancelled(cancellationRequested);

                List<Map.Entry<String, Path>> orderedFrames = new ArrayList<>(frameFiles.entrySet());
                List<Integer> delays = resolveDelays(other.getUgoiraDelays(), orderedFrames.size());

                if (runFfmpeg(artworkId, orderedFrames, delays, tempDir, downloadPath,
                        outputBaseName, attempt, maxAttempts, progressListener, cancellationRequested)) {
                    // ffmpeg 成功后再发布缩略图，失败路径不会留下半份 Ugoira 产物。
                    Files.copy(orderedFrames.get(0).getValue(),
                            downloadPath.resolve(outputBaseName + "_thumb.jpg"),
                            StandardCopyOption.REPLACE_EXISTING);
                    return 1;
                }

            } catch (CancellationException e) {
                throw e;
            } catch (UgoiraResourceLimitException e) {
                log.warn(message("ugoira.log.processing.failed", id(artworkId), e.getMessage()));
                break;
            } catch (java.util.zip.ZipException e) {
                log.warn(message("ugoira.log.zip.invalid",
                        id(artworkId), text(attempt), text(maxAttempts), e.getMessage()));
            } catch (Exception e) {
                log.error(message("ugoira.log.processing.failed", id(artworkId), e.getMessage()), e);
                break; // 非ZIP格式异常不重试
            } finally {
                cleanup(zipPath, tempDir, partialOutput);
            }

            if (attempt < maxAttempts) {
                sleepCancellable(2000L * attempt, cancellationRequested);
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
                                                 int expectedFrames, int attempt, int maxAttempts,
                                                 BooleanSupplier cancellationRequested) throws IOException {
        TreeMap<String, Path> frameFiles = new TreeMap<>();
        Path normalizedTempDir = tempDir.normalize();
        int[] lastProgress = {-1};
        long[] lastAt = {0L};
        int entryCount = 0;
        long totalUncompressedBytes = 0;
        try (ZipInputStream zis = new ZipInputStream(
                new FileInputStream(zipPath.toFile()), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                ensureNotCancelled(cancellationRequested);
                if (++entryCount > MAX_ZIP_ENTRIES) {
                    throw resourceLimit("ugoira.log.limit.zip.entries", MAX_ZIP_ENTRIES);
                }
                if (!entry.isDirectory()) {
                    if (frameFiles.size() >= MAX_FRAME_COUNT) {
                        throw resourceLimit("ugoira.log.limit.frames", MAX_FRAME_COUNT);
                    }
                    if (!isSafeFrameEntryName(entry.getName())) {
                        throw new ZipException(message("ugoira.log.zip-entry.unsafe", id(artworkId), entry.getName()));
                    }
                    long declaredSize = entry.getSize();
                    if (declaredSize > MAX_ZIP_ENTRY_BYTES) {
                        throw resourceLimit("ugoira.log.limit.zip.entry-bytes", MAX_ZIP_ENTRY_BYTES / MIB);
                    }
                    if (declaredSize > 0 && declaredSize > MAX_ZIP_UNCOMPRESSED_BYTES - totalUncompressedBytes) {
                        throw resourceLimit("ugoira.log.limit.zip.total-bytes", MAX_ZIP_UNCOMPRESSED_BYTES / MIB);
                    }
                    Path framePath = normalizedTempDir.resolve(entry.getName()).normalize();
                    if (!framePath.startsWith(normalizedTempDir)) {
                        throw new ZipException(message("ugoira.log.zip-entry.unsafe", id(artworkId), entry.getName()));
                    }
                    long entryBytes = 0;
                    try (OutputStream out = Files.newOutputStream(
                            framePath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = zis.read(buf)) != -1) {
                            ensureNotCancelled(cancellationRequested);
                            if (len > MAX_ZIP_ENTRY_BYTES - entryBytes) {
                                throw resourceLimit(
                                        "ugoira.log.limit.zip.entry-bytes", MAX_ZIP_ENTRY_BYTES / MIB);
                            }
                            if (len > MAX_ZIP_UNCOMPRESSED_BYTES - totalUncompressedBytes) {
                                throw resourceLimit(
                                        "ugoira.log.limit.zip.total-bytes", MAX_ZIP_UNCOMPRESSED_BYTES / MIB);
                            }
                            out.write(buf, 0, len);
                            entryBytes += len;
                            totalUncompressedBytes += len;
                        }
                    }
                    zis.closeEntry();
                    long compressedBytes = entry.getCompressedSize();
                    if (entryBytes > 0 && (compressedBytes <= 0
                            || entryBytes > compressedBytes * MAX_ZIP_COMPRESSION_RATIO)) {
                        throw resourceLimit(
                                "ugoira.log.limit.zip.ratio", MAX_ZIP_COMPRESSION_RATIO);
                    }
                    validateFrame(framePath);
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
                    continue;
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
     * 从宿主取得 FFmpeg 命令并记录已探测来源。
     */
    String detectFfmpegCommand() {
        ResolvedFfmpegCommand resolved = ffmpegCommandResolver.resolve();
        if (resolved.source() != ResolvedFfmpegCommand.Source.FALLBACK) {
            log.info(message("ugoira.log.ffmpeg.detected",
                    message(ffmpegSourceMessageCode(resolved.source())), resolved.command()));
            return resolved.command();
        }

        log.warn(message("ugoira.log.ffmpeg.missing"));
        return resolved.command();
    }

    private String ffmpegSourceMessageCode(ResolvedFfmpegCommand.Source source) {
        return switch (source) {
            case MANAGED -> "ffmpeg.source.managed";
            case BUNDLED -> "ffmpeg.source.bundled";
            case SYSTEM -> "ffmpeg.source.system";
            case FALLBACK -> throw new IllegalArgumentException("fallback command has no detected source");
        };
    }

    private boolean runFfmpeg(Long artworkId, List<Map.Entry<String, Path>> orderedFrames,
                              List<Integer> delays, Path tempDir, Path downloadPath,
                              String outputBaseName, int attempt, int maxAttempts,
                              Consumer<UgoiraProgress> progressListener,
                              BooleanSupplier cancellationRequested) throws Exception {
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
        Path partialOutput = partialOutputPath(downloadPath, outputBaseName);
        Path progressFile = tempDir.resolve("ffmpeg-progress.log");
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
        acquireFfmpegPermit(cancellationRequested);
        Process process = null;
        try {
            Files.deleteIfExists(partialOutput);
            Files.deleteIfExists(progressFile);
            Files.createFile(progressFile);
            ProcessBuilder processBuilder = new ProcessBuilder(
                    detectFfmpegCommand(), "-y",
                    "-nostats",
                    "-stats_period", "0.5",
                    "-progress", "pipe:1",
                    "-f", "concat", "-safe", "0",
                    "-i", listFile.toAbsolutePath().toString(),
                    "-vcodec", "libwebp",
                    "-quality", "90",
                    "-loop", "0",
                    "-an",
                    "-f", "webp",
                    partialOutput.toAbsolutePath().toString()
            );
            processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(progressFile.toFile()));
            processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = startFfmpeg(processBuilder);
            long deadline = System.nanoTime() + ffmpegTimeoutNanos();
            int[] lastProgress = {-1};
            long[] lastAt = {0L};
            int progressLineCount = 0;
            while (true) {
                ensureNotCancelled(cancellationRequested);
                enforceFfmpegOutputLimit(partialOutput);
                progressLineCount = publishFfmpegProgress(
                        progressFile, progressLineCount, durationMs, orderedFrames.size(), attempt,
                        maxAttempts, progressListener, lastProgress, lastAt);
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw resourceLimit("ugoira.log.limit.ffmpeg-timeout", FFMPEG_TIMEOUT.toMinutes());
                }
                long waitMillis = Math.max(1L, Math.min(200L,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                    break;
                }
            }
            progressLineCount = publishFfmpegProgress(
                    progressFile, progressLineCount, durationMs, orderedFrames.size(), attempt,
                    maxAttempts, progressListener, lastProgress, lastAt);
            enforceFfmpegOutputLimit(partialOutput);
            int exitCode = process.exitValue();

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
                        .ffmpegProgress(Math.max(lastProgress[0], 0))
                        .build());
                return false;
            }
            Files.move(partialOutput, webpPath, StandardCopyOption.REPLACE_EXISTING);
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("download cancelled");
        } finally {
            if (process != null && process.isAlive()) {
                terminateProcessTree(process);
            }
            Files.deleteIfExists(partialOutput);
            Files.deleteIfExists(progressFile);
            ffmpegPermits.release();
        }
    }

    boolean downloadZip(String url, Path path, String referer, String cookie,
                        int outerAttempt, int outerMaxAttempts,
                        Consumer<UgoiraProgress> progressListener,
                        BooleanSupplier cancellationRequested) {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            ensureNotCancelled(cancellationRequested);
            try {
                URI source = URI.create(url);
                URI refererUri = referer == null || referer.isBlank()
                        ? DEFAULT_PIXIV_REFERER
                        : URI.create(referer);
                long[] totalBytes = {0L};
                long[] downloadedBytes = {0L};
                int[] lastProgress = {-1};
                long[] lastBytes = {0L};
                long[] lastAt = {0L};
                boolean success = pixivImageDownloader.download(
                        source,
                        refererUri,
                        path,
                        cookie,
                        new PixivImageTransferObserver() {
                            @Override
                            public long maximumBytes() {
                                return Long.MAX_VALUE;
                            }

                            @Override
                            public void checkCancelled() {
                                ensureNotCancelled(cancellationRequested);
                            }

                            @Override
                            public void onContentLength(long contentLength) {
                                if (contentLength > MAX_ZIP_BYTES) {
                                    throw resourceLimit("ugoira.log.limit.zip.download", MAX_ZIP_BYTES / MIB);
                                }
                                totalBytes[0] = contentLength;
                            }

                            @Override
                            public void onBytesTransferred(long transferredBytes) {
                                if (transferredBytes <= 0) {
                                    return;
                                }
                                if (transferredBytes > MAX_ZIP_BYTES) {
                                    throw resourceLimit("ugoira.log.limit.zip.download", MAX_ZIP_BYTES / MIB);
                                }
                                downloadedBytes[0] = transferredBytes;
                                Integer progress = totalBytes[0] > 0
                                        ? Math.min(99, (int) (transferredBytes * 100 / totalBytes[0]))
                                        : null;
                                if (shouldEmitByteProgress(
                                        progress, transferredBytes, lastProgress, lastBytes, lastAt)) {
                                    publishProgress(progressListener, UgoiraProgress.builder()
                                            .phase(UgoiraProgress.PHASE_ZIP)
                                            .status(UgoiraProgress.STATUS_RUNNING)
                                            .attempt(outerAttempt)
                                            .maxAttempts(outerMaxAttempts)
                                            .zipDownloadedBytes(transferredBytes)
                                            .zipTotalBytes(totalBytes[0] > 0 ? totalBytes[0] : null)
                                            .zipProgress(progress)
                                            .build());
                                }
                            }
                        });
                if (success) {
                    publishProgress(progressListener, UgoiraProgress.builder()
                            .phase(UgoiraProgress.PHASE_ZIP)
                            .status(UgoiraProgress.STATUS_COMPLETED)
                            .attempt(outerAttempt)
                            .maxAttempts(outerMaxAttempts)
                            .zipDownloadedBytes(downloadedBytes[0])
                            .zipTotalBytes(totalBytes[0] > 0 ? totalBytes[0] : null)
                            .zipProgress(100)
                            .build());
                    return true;
                }
            } catch (CancellationException e) {
                throw e;
            } catch (UgoiraResourceLimitException e) {
                throw e;
            } catch (Exception e) {
                log.error(message("ugoira.log.zip.retry", url, e.getMessage(), attempt, maxRetries));
                if (attempt < maxRetries) {
                    sleepCancellable(2000L * attempt, cancellationRequested);
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

    private void ensureNotCancelled(BooleanSupplier cancellationRequested) {
        if (cancellationRequested != null && cancellationRequested.getAsBoolean()) {
            throw new CancellationException("download cancelled");
        }
    }

    void sleepCancellable(long millis, BooleanSupplier cancellationRequested) {
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
                throw new CancellationException("download cancelled");
            }
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

    private void validateFrame(Path framePath) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(framePath.toFile())) {
            if (input == null) {
                throw new ZipException(message("ugoira.log.zip.frame.invalid", framePath.getFileName()));
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new ZipException(message("ugoira.log.zip.frame.invalid", framePath.getFileName()));
            }
            ImageReader reader = readers.next();
            try {
                String fileName = framePath.getFileName().toString().toLowerCase(Locale.ROOT);
                String format = reader.getFormatName();
                boolean expectedFormat = (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg"))
                        ? "JPEG".equalsIgnoreCase(format)
                        : "PNG".equalsIgnoreCase(format);
                if (!expectedFormat) {
                    throw new ZipException(message("ugoira.log.zip.frame.invalid", framePath.getFileName()));
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_FRAME_PIXELS) {
                    throw resourceLimit("ugoira.log.limit.frame-pixels", MAX_FRAME_PIXELS);
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private boolean isSafeFrameEntryName(String entryName) {
        if (entryName == null || entryName.isBlank() || entryName.length() > 128
                || ".".equals(entryName) || "..".equals(entryName)) {
            return false;
        }
        for (int i = 0; i < entryName.length(); i++) {
            char c = entryName.charAt(i);
            if (!(c >= 'a' && c <= 'z')
                    && !(c >= 'A' && c <= 'Z')
                    && !(c >= '0' && c <= '9')
                    && c != '.' && c != '_' && c != '-') {
                return false;
            }
        }
        String normalized = entryName.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".jpg") || normalized.endsWith(".jpeg")
                || normalized.endsWith(".png");
    }

    private void acquireFfmpegPermit(BooleanSupplier cancellationRequested) {
        while (true) {
            ensureNotCancelled(cancellationRequested);
            try {
                if (ffmpegPermits.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("download cancelled");
            }
        }
    }

    Process startFfmpeg(ProcessBuilder processBuilder) throws IOException {
        return processBuilder.start();
    }

    long ffmpegTimeoutNanos() {
        return FFMPEG_TIMEOUT.toNanos();
    }

    long maxFfmpegOutputBytes() {
        return MAX_FFMPEG_OUTPUT_BYTES;
    }

    private void enforceFfmpegOutputLimit(Path partialOutput) throws IOException {
        if (Files.exists(partialOutput) && Files.size(partialOutput) > maxFfmpegOutputBytes()) {
            throw resourceLimit(
                    "ugoira.log.limit.ffmpeg-output", MAX_FFMPEG_OUTPUT_BYTES / MIB);
        }
    }

    private int publishFfmpegProgress(
            Path progressFile,
            int consumedLines,
            long durationMs,
            int frameCount,
            int attempt,
            int maxAttempts,
            Consumer<UgoiraProgress> progressListener,
            int[] lastProgress,
            long[] lastAt
    ) throws IOException {
        List<String> lines = Files.readAllLines(progressFile, StandardCharsets.UTF_8);
        for (int i = consumedLines; i < lines.size(); i++) {
            Long outTimeMs = parseFfmpegOutTimeMs(lines.get(i));
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
                        .extractedFrames(frameCount)
                        .totalFrames(frameCount)
                        .ffmpegOutTimeMs(Math.min(outTimeMs, durationMs))
                        .ffmpegDurationMs(durationMs)
                        .ffmpegProgress(progress)
                        .build());
            }
        }
        return lines.size();
    }

    private void terminateProcessTree(Process process) {
        ProcessHandle parent = process.toHandle();
        List<ProcessHandle> descendants;
        try {
            descendants = parent.descendants().toList();
        } catch (RuntimeException ignored) {
            descendants = List.of();
        }
        parent.destroyForcibly();
        for (int i = descendants.size() - 1; i >= 0; i--) {
            descendants.get(i).destroyForcibly();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline
                && (parent.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static Path partialOutputPath(Path downloadPath, String outputBaseName) {
        return downloadPath.resolve(outputBaseName + ".webp.part");
    }

    private UgoiraResourceLimitException resourceLimit(String code, Object... args) {
        return new UgoiraResourceLimitException(message(code, args));
    }

    private void cleanup(Path zipPath, Path tempDir, Path partialOutput) {
        try { Files.deleteIfExists(zipPath); } catch (Exception ignored) {}
        try { Files.deleteIfExists(partialOutput); } catch (Exception ignored) {}
        try {
            if (Files.exists(tempDir)) {
                try (var paths = Files.walk(tempDir)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                    });
                }
            }
        } catch (Exception ignored) {}
    }

    private static final class UgoiraResourceLimitException extends RuntimeException {
        private UgoiraResourceLimitException(String message) {
            super(message);
        }
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
