package top.sywyar.pixivdownload.download;

import top.sywyar.pixivdownload.core.asset.ArtworkMediaStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner;
import top.sywyar.pixivdownload.core.pixiv.PixivImageDownloader;
import top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * 动图下载、帧校验及用户选择的动画输出。
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
    static final long MAX_FRAME_PIXELS = 25_000_000L;
    static final Duration FFMPEG_TIMEOUT = Duration.ofMinutes(10);
    static final long MAX_FFMPEG_OUTPUT_BYTES = MAX_ZIP_BYTES;

    private final PixivImageDownloader pixivImageDownloader;
    private final FfmpegRunner ffmpegRunner;
    private final MessageResolver messages;
    private final ArtworkMediaStore mediaStore;
    private final Map<Path, ProcessingLock> processingLocks = new HashMap<>();

    private static final class ProcessingLock {
        private final java.util.concurrent.locks.ReentrantLock gate = new java.util.concurrent.locks.ReentrantLock();
        private int users;
    }

    public UgoiraService(PixivImageDownloader pixivImageDownloader,
                         FfmpegRunner ffmpegRunner,
                         MessageResolver messages,
                         ArtworkMediaStore mediaStore) {
        this.pixivImageDownloader = pixivImageDownloader;
        this.ffmpegRunner = ffmpegRunner;
        this.messages = messages;
        this.mediaStore = mediaStore;
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

    public List<String> outputFormats(DownloadRequest.Other other) {
        return top.sywyar.pixivdownload.download.media.MediaOutputSettings.parseFormats(
                other.resolveMediaOutputSettings().getUgoiraFormats(),
                top.sywyar.pixivdownload.download.media.MediaOutputSettings.UGOIRA_FORMATS);
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
        return processArchive(artworkId, other, downloadPath, referer, cookie, progressListener, cancellationRequested, null, null);
    }

    public boolean addMissingFormats(long artworkId, Path displayedFile, String selectedFormats,
                                     BooleanSupplier cancellationRequested) throws IOException {
        String name = displayedFile.getFileName().toString();
        String base = name.substring(0, name.lastIndexOf('.'));
        if (base.endsWith("_thumb")) base = base.substring(0, base.length() - 6);
        Path stem = displayedFile.resolveSibling(base);
        var previous = mediaStore.find(artworkId, 0);
        if (previous.isEmpty() || !previous.get().originalExtension().equals("zip")) return false;
        Path zip = stem.resolveSibling(base + ".zip");
        Path timingFile = stem.resolveSibling(base + ".frames.properties");
        if (!Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS) || Files.size(zip) > MAX_ZIP_BYTES
                || !Files.isRegularFile(timingFile, LinkOption.NOFOLLOW_LINKS) || Files.size(timingFile) > 128 * 1024) {
            throw new IOException("Original animation archive and timing are required");
        }
        List<String> missing = new ArrayList<>();
        for (String format : top.sywyar.pixivdownload.download.media.MediaOutputSettings.parseFormats(selectedFormats,
                top.sywyar.pixivdownload.download.media.MediaOutputSettings.UGOIRA_FORMATS)) {
            if (!format.equals("zip") && !Files.exists(stem.resolveSibling(base + "." + format))) missing.add(format);
        }
        if (missing.isEmpty()) return true;
        Properties timing = new Properties();
        try (var reader = Files.newBufferedReader(timingFile, StandardCharsets.UTF_8)) { timing.load(reader); }
        if (timing.isEmpty() || timing.size() > MAX_FRAME_COUNT) throw new IOException("Invalid animation timing");
        List<Integer> delays = new ArrayList<>();
        for (String frame : new TreeSet<>(timing.stringPropertyNames())) {
            if (!isSafeFrameEntryName(frame)) throw new IOException("Invalid frame name");
            try {
                int delay = Integer.parseInt(timing.getProperty(frame));
                if (delay <= 0) throw new NumberFormatException();
                delays.add(delay);
            } catch (NumberFormatException invalid) { throw new IOException("Invalid animation delay", invalid); }
        }
        DownloadRequest.Other request = new DownloadRequest.Other();
        request.setFileNames(List.of(base));
        request.setUgoiraDelays(delays);
        request.setUgoiraFormats(String.join(",", missing));
        if (processArchive(artworkId, request, displayedFile.getParent(), null, null, null, cancellationRequested, zip, timing.stringPropertyNames()) != 1) {
            throw new IOException("Animation conversion failed");
        }
        return true;
    }

    private int processArchive(Long artworkId, DownloadRequest.Other other,
                               Path downloadPath, String referer, String cookie,
                               Consumer<UgoiraProgress> progressListener,
                               BooleanSupplier cancellationRequested, Path sourceArchive, Set<String> frameNames) {
        Path directory = downloadPath.toAbsolutePath().normalize();
        ProcessingLock lock;
        synchronized (processingLocks) {
            lock = processingLocks.computeIfAbsent(directory, ignored -> new ProcessingLock());
            lock.users++;
        }
        boolean acquired = false;
        try {
            do {
                ensureNotCancelled(cancellationRequested);
                acquired = lock.gate.tryLock(200, TimeUnit.MILLISECONDS);
            } while (!acquired);
            return processArchiveLocked(artworkId, other, downloadPath, referer, cookie,
                    progressListener, cancellationRequested, sourceArchive, frameNames);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("download cancelled");
        } finally {
            if (acquired) lock.gate.unlock();
            synchronized (processingLocks) {
                if (--lock.users == 0) processingLocks.remove(directory, lock);
            }
        }
    }

    private int processArchiveLocked(Long artworkId, DownloadRequest.Other other,
                                     Path downloadPath, String referer, String cookie,
                                     Consumer<UgoiraProgress> progressListener,
                                     BooleanSupplier cancellationRequested, Path sourceArchive, Set<String> frameNames) {
        if (sourceArchive == null) ArtworkDownloadExecutor.validatePixivUrl(other.getUgoiraZipUrl());
        String outputBaseName = resolveOutputBaseName(artworkId, other);
        List<String> formats = outputFormats(other);
        if (sourceArchive != null) {
            formats = formats.stream().filter(format -> !Files.exists(downloadPath.resolve(outputBaseName + "." + format))).toList();
        }
        var encodingSettings = other.resolveMediaOutputSettings();

        String localSuffix = sourceArchive == null ? "" : "_" + UUID.randomUUID();
        Path zipPath = downloadPath.resolve("_ugoira_frames" + localSuffix + ".zip");
        Path tempDir = downloadPath.resolve("_frames_tmp" + localSuffix);
        int maxAttempts = 3;
        cleanup(zipPath, tempDir);

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
                boolean downloaded;
                if (sourceArchive != null) {
                    Files.copy(sourceArchive, zipPath, StandardCopyOption.REPLACE_EXISTING);
                    downloaded = true;
                } else {
                    downloaded = downloadZip(other.getUgoiraZipUrl(), zipPath, referer, cookie, attempt, maxAttempts,
                            progressListener, cancellationRequested);
                }
                if (!downloaded) {
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
                if (sourceArchive != null && (!frameFiles.keySet().equals(frameNames) || orderedFrames.size() != expectedFrames)) {
                    throw new IOException("Animation timing does not match frames");
                }
                List<Integer> delays = resolveDelays(other.getUgoiraDelays(), orderedFrames.size());

                // 用户选中的原始帧包先落盘，后续编码失败或取消也保留这份恢复源。
                if (formats.contains("zip")) saveArchive(zipPath, downloadPath.resolve(outputBaseName), orderedFrames, delays);
                boolean complete = true;
                int outputIndex = 0;
                int outputCount = (int) formats.stream().filter(format -> !format.equals("zip")).count();
                for (String format : formats) {
                    if (format.equals("zip")) continue;
                    int index = ++outputIndex;
                    Consumer<UgoiraProgress> outputProgress = progress -> publishProgress(progressListener,
                            progress.toBuilder().outputFormat(format).outputIndex(index).outputCount(outputCount).build());
                    if (!runFfmpeg(artworkId, orderedFrames, delays, tempDir, downloadPath,
                            outputBaseName, format, encodingSettings, attempt, maxAttempts,
                            outputProgress, cancellationRequested)) {
                        complete = false;
                        break;
                    }
                }
                if (complete) {
                    publishProgress(progressListener, UgoiraProgress.builder()
                            .phase(UgoiraProgress.PHASE_FINALIZING).status(UgoiraProgress.STATUS_RUNNING)
                            .attempt(attempt).maxAttempts(maxAttempts).zipProgress(100).build());
                    Path thumbnailPath = downloadPath.resolve(outputBaseName + "_thumb.jpg");
                    if (sourceArchive == null || !Files.exists(thumbnailPath)) {
                        BufferedImage thumbnail = top.sywyar.pixivdownload.core.asset.ImageThumbnailScaler.scale(
                                orderedFrames.get(0).getValue(), 1600, 1600);
                        Path temporary = Files.createTempFile(downloadPath, ".media-thumb-", ".jpg");
                        try {
                            if (!ImageIO.write(thumbnail, "jpg", temporary.toFile())) throw new IOException("JPEG writer unavailable");
                            publishOutput(temporary, thumbnailPath);
                        } finally { Files.deleteIfExists(temporary); }
                    }
                    LinkedHashSet<String> savedFormats = new LinkedHashSet<>();
                    if (sourceArchive != null) {
                        mediaStore.find(artworkId, 0)
                                .ifPresent(existing -> savedFormats.addAll(existing.extensions()));
                    }
                    savedFormats.addAll(formats);
                    mediaStore.save(artworkId, 0,
                            new top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest("zip", List.copyOf(savedFormats)));
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
                cleanup(zipPath, tempDir);
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

    private void saveArchive(Path zip, Path stem, List<Map.Entry<String, Path>> frames,
                             List<Integer> delays) throws IOException {
        Path archive = Files.createTempFile(stem.getParent(), ".media-archive-", ".zip");
        Path timingFile = Files.createTempFile(stem.getParent(), ".media-timing-", ".properties");
        try {
            Files.copy(zip, archive, StandardCopyOption.REPLACE_EXISTING);
            Properties timing = new Properties();
            for (int index = 0; index < frames.size(); index++) {
                timing.setProperty(frames.get(index).getKey(), Integer.toString(delays.get(index)));
            }
            try (var writer = Files.newBufferedWriter(timingFile, StandardCharsets.UTF_8)) { timing.store(writer, null); }
            publishOutput(timingFile, stem.resolveSibling(stem.getFileName() + ".frames.properties"));
            publishOutput(archive, stem.resolveSibling(stem.getFileName() + ".zip"));
        } finally {
            Files.deleteIfExists(archive);
            Files.deleteIfExists(timingFile);
        }
    }

    private static void publishOutput(Path temporary, Path target) throws IOException {
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unavailable) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
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
                    String extension = entry.getName().substring(entry.getName().lastIndexOf('.'));
                    Path framePath = normalizedTempDir.resolve(frameFiles.size() + extension).normalize();
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
                    // 纯色 PNG 在 ZIP 中仍可能高度压缩；解压预算按实际写出字节执行。
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
        if (delays.stream().anyMatch(delay -> delay == null || delay <= 0)) throw new IllegalArgumentException("Invalid animation delay");
        return delays;
    }

    private String resolveOutputBaseName(Long artworkId, DownloadRequest.Other other) {
        if (other != null && other.getFileNames() != null && !other.getFileNames().isEmpty()) {
            return other.getFileNames().get(0);
        }
        return artworkId + "_p0";
    }

    private boolean runFfmpeg(Long artworkId, List<Map.Entry<String, Path>> orderedFrames,
                              List<Integer> delays, Path tempDir, Path downloadPath,
                              String outputBaseName, String format,
                              top.sywyar.pixivdownload.download.media.MediaOutputSettings encodingSettings,
                              int attempt, int maxAttempts,
                              Consumer<UgoiraProgress> progressListener,
                              BooleanSupplier cancellationRequested) throws Exception {
        Path listFile = tempDir.resolve("frames.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < orderedFrames.size(); i++) {
            String fp = orderedFrames.get(i).getValue().getFileName()
                    .toString().replace("\\", "/");
            sb.append("file '").append(fp).append("'\n");
            sb.append("option framerate 1000\n");
            sb.append("duration ").append(delays.get(i) / 1000.0).append("\n");
        }
        // ffmpeg concat 需要重复最后一帧才能正确应用末帧时长
        sb.append("file '").append(
                orderedFrames.get(orderedFrames.size() - 1).getValue()
                        .getFileName().toString().replace("\\", "/"))
                .append("'\noption framerate 1000\n");
        Files.writeString(listFile, sb.toString(), StandardCharsets.UTF_8);

        Path outputPath = downloadPath.resolve(outputBaseName + "." + format);
        Path partialOutput = downloadPath.resolve(outputBaseName + "." + format + ".part");
        long durationMs = Math.max(1L, delays.stream().mapToLong(Integer::longValue).sum());
        UgoiraProgress encoding = UgoiraProgress.builder()
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
                .build();
        ensureNotCancelled(cancellationRequested);
        try {
            Files.deleteIfExists(partialOutput);
            Path workingDirectory = ffmpegWorkingDirectory(downloadPath);
            List<String> commandLine = new ArrayList<>(List.of(
                    "-y", "-nostdin",
                    "-nostats",
                    "-stats_period", "0.5",
                    "-progress", "pipe:1",
                    "-f", "concat", "-safe", "0",
                    "-i", workingDirectory.relativize(listFile.toAbsolutePath()).toString()));
            commandLine.addAll(top.sywyar.pixivdownload.download.media.UgoiraEncoding.arguments(format, encodingSettings));
            int lastDelay = delays.get(delays.size() - 1);
            if (format.equals("gif")) commandLine.addAll(List.of("-final_delay", Integer.toString(Math.max(1, Math.round(lastDelay / 10f)))));
            if (format.equals("apng")) commandLine.addAll(List.of("-final_delay", lastDelay + "/1000"));
            commandLine.addAll(List.of("-fps_mode", "vfr"));
            if (format.equals("gif") || format.equals("apng")) {
                commandLine.addAll(List.of("-t", Double.toString(durationMs / 1000.0)));
            }
            commandLine.add(workingDirectory.relativize(partialOutput.toAbsolutePath()).toString());
            int[] lastProgress = {-1};
            long[] lastAt = {0L};
            ffmpegRunner.run(FfmpegRunner.Tool.FFMPEG, commandLine, workingDirectory, partialOutput,
                    MAX_FFMPEG_OUTPUT_BYTES, FFMPEG_TIMEOUT, cancellationRequested,
                    phase -> publishProgress(progressListener, phase == FfmpegRunner.Phase.WAITING
                            ? encoding.toBuilder().phase(UgoiraProgress.PHASE_WAITING_FFMPEG).ffmpegProgress(null).build()
                            : encoding),
                    line -> {
                        Long outTimeMs = parseFfmpegOutTimeMs(line);
                        if (outTimeMs == null) return;
                        int progress = Math.min(99, Math.max(0, (int) Math.round(outTimeMs * 100.0 / durationMs)));
                        if (shouldEmitStepProgress(progress, lastProgress, lastAt)) {
                            publishProgress(progressListener, encoding.toBuilder()
                                    .ffmpegOutTimeMs(Math.min(outTimeMs, durationMs)).ffmpegProgress(progress).build());
                        }
                    });
            ensureNotCancelled(cancellationRequested);
            if (!Files.isRegularFile(partialOutput) || Files.size(partialOutput) == 0) return false;
            publishOutput(partialOutput, outputPath);
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
        } finally {
            try {
                Files.deleteIfExists(partialOutput);
            } catch (IOException cleanupFailure) {
                log.debug("Could not remove FFmpeg temporary output", cleanupFailure);
            }
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

    static Path ffmpegWorkingDirectory(Path directory) {
        Path working = directory.toAbsolutePath().normalize();
        // CreateProcess 的工作目录仍受 258 字符限制，与 NIO 文件路径支持分开处理。
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            while (working.toString().length() > 258 && working.getParent() != null) {
                working = working.getParent();
            }
        }
        return working;
    }

    private UgoiraResourceLimitException resourceLimit(String code, Object... args) {
        return new UgoiraResourceLimitException(message(code, args));
    }

    private void cleanup(Path zipPath, Path tempDir) {
        try { Files.deleteIfExists(zipPath); } catch (Exception ignored) {}
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
