package top.sywyar.pixivdownload.download.media;

import top.sywyar.pixivdownload.core.work.service.DownloadPathGuard;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaStore;
import top.sywyar.pixivdownload.core.asset.FileTransactionService;
import top.sywyar.pixivdownload.core.artwork.download.PartialArtwork;
import tools.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;
import top.sywyar.pixivdownload.core.asset.ImageThumbnailScaler;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner;
import top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 先生成全部选定产物，再保存媒体记录并按选择移除原文件。 */
public final class ImageOutputService {
    static final long MAX_PIXELS = 25_000_000L;
    static final Duration TIMEOUT = Duration.ofMinutes(10);
    private final FfmpegRunner runner;
    private final ObjectMapper mapper;
    private final ArtworkMediaStore mediaStore;
    private final FileTransactionService transactions;

    public ImageOutputService(FfmpegRunner runner, ObjectMapper mapper, ArtworkMediaStore mediaStore, FileTransactionService transactions) {
        this.runner = runner;
        this.mapper = mapper;
        this.mediaStore = mediaStore;
        this.transactions = transactions;
    }

    public List<String> process(long artworkId, int page, Path stem, String sourceExtension, MediaOutputSettings settings,
                                BooleanSupplier cancelled) throws IOException {
        return process(page, stem, sourceExtension, settings, cancelled, null, null, downloadedMedia(artworkId, false));
    }

    /** 只报告机器阶段与输出序号；原始路径、命令和异常诊断不进入队列状态。 */
    public record Progress(String phase, String outputFormat, Integer outputIndex, Integer outputCount) {}

    public List<String> process(long artworkId, int page, Path stem, String sourceExtension, MediaOutputSettings settings,
                                BooleanSupplier cancelled, Consumer<Progress> progress) throws IOException {
        return process(page, stem, sourceExtension, settings, cancelled, null, progress, downloadedMedia(artworkId, false));
    }

    public List<String> process(int page, Path stem, String sourceExtension, MediaOutputSettings settings,
                                BooleanSupplier cancelled, Consumer<Progress> progress, MediaSink sink) throws IOException {
        return process(page, stem, sourceExtension, settings, cancelled, null, progress, sink);
    }

    private List<String> process(int page, Path stem, String sourceExtension, MediaOutputSettings settings,
                                 BooleanSupplier cancelled, ArtworkMediaManifest previous,
                                 Consumer<Progress> progress, MediaSink sink) throws IOException {
        List<String> selected = MediaOutputSettings.parseFormats(
                settings.getImageFormats(), MediaOutputSettings.IMAGE_FORMATS);
        if (selected.stream().allMatch(format -> format.equals("original") || format.equals(sourceExtension)
                || format.equals("jpg") && sourceExtension.equals("jpeg"))) {
            checkCancelled(cancelled);
            sink.save(page, stem, previous == null
                    ? new ArtworkMediaManifest(sourceExtension, List.of(sourceExtension)) : previous);
            return List.of(sourceExtension);
        }
        Path source = withExtension(stem, sourceExtension);
        int outputCount = (int) selected.stream().filter(format -> !format.equals("original")
                && !format.equals(sourceExtension) && !(format.equals("jpg") && sourceExtension.equals("jpeg"))).count();
        checkInput(source, cancelled, progress(progress, "verifying", null, null, outputCount));
        LinkedHashSet<String> outputs = new LinkedHashSet<>();
        if (selected.contains("original")) outputs.add(sourceExtension);
        List<Path> pending = new ArrayList<>();
        List<Path> destinations = new ArrayList<>();
        Path stagedThumbnail = null;
        int outputIndex = 0;
        try {
            for (String format : selected) {
                if (format.equals("original")) continue;
                checkCancelled(cancelled);
                // 同格式复用原文件，避免重复有损编码，也不允许缩放副本覆盖已选择保留的原图。
                if (format.equals(sourceExtension) || format.equals("jpg") && sourceExtension.equals("jpeg")) {
                    outputs.add(sourceExtension);
                    continue;
                }
                Path destination = withExtension(stem, format);
                int index = ++outputIndex;
                Path temporary = Files.createTempFile(stem.toAbsolutePath().getParent(), ".media-", "." + format);
                pending.add(temporary);
                destinations.add(destination);
                List<String> args = new ArrayList<>(List.of("-y", "-nostdin", "-v", "error",
                        "-i", source.toAbsolutePath().toString(), "-frames:v", "1", "-an"));
                List<String> filters = new ArrayList<>();
                if (settings.getMaximumEdge() > 0) {
                    int edge = settings.getMaximumEdge();
                    filters.add("scale=w='min(iw," + edge + ")':h='min(ih," + edge + ")':force_original_aspect_ratio=decrease");
                }
                if (format.equals("jpg")) {
                    filters.add("format=rgba");
                    filters.add("split[fg][bg];[bg]drawbox=c=white:t=fill:replace=1[white];[white][fg]overlay=format=auto,format=yuvj444p");
                    args.addAll(List.of("-c:v", "mjpeg", "-q:v", Integer.toString(2 + (100 - settings.getQuality()) * 29 / 99)));
                } else if (format.equals("webp")) {
                    args.addAll(List.of("-c:v", "libwebp", "-quality", Integer.toString(settings.getQuality()),
                            "-lossless", settings.isWebpLossless() ? "1" : "0"));
                } else {
                    args.addAll(List.of("-c:v", "png"));
                }
                if (!filters.isEmpty()) args.addAll(List.of("-vf", String.join(",", filters)));
                args.add(temporary.toAbsolutePath().toString());
                runner.run(FfmpegRunner.Tool.FFMPEG, args, null, temporary,
                        PixivImageTransferObserver.MAX_IMAGE_BYTES, TIMEOUT, cancelled,
                        phase -> report(progress, phase == FfmpegRunner.Phase.WAITING ? "ffmpeg-waiting" : "ffmpeg",
                                format, index, outputCount));
                checkInput(temporary, cancelled, progress(progress, "verifying", format, index, outputCount));
                outputs.add(format);
            }
            boolean thumbnailOwned = previous != null && previous.thumbnailOwned();
            Path thumbnail = stem.resolveSibling(stem.getFileName() + "_thumb.jpg");
            Map<Path, Path> newFiles = new LinkedHashMap<>();
            if (outputs.contains("webp") && Files.notExists(thumbnail, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                stagedThumbnail = Files.createTempFile(thumbnail.toAbsolutePath().getParent(), ".media-thumb-", ".jpg");
                writeThumbnail(source, stagedThumbnail, cancelled, progress(progress, "thumbnail", null, null, outputCount));
                newFiles.put(thumbnail, stagedThumbnail);
                thumbnailOwned = true;
            }
            checkCancelled(cancelled);
            report(progress, "finalizing", null, null, outputCount);
            for (int index = 0; index < pending.size(); index++) {
                checkCancelled(cancelled);
                if (previous == null) publish(pending.get(index), destinations.get(index));
                else newFiles.put(destinations.get(index), pending.get(index));
            }
            LinkedHashSet<String> recorded = new LinkedHashSet<>();
            if (previous != null) recorded.addAll(previous.extensions());
            recorded.addAll(outputs);
            var manifest = new ArtworkMediaManifest(previous == null ? sourceExtension : previous.originalExtension(), List.copyOf(recorded),
                    previous == null ? outputs.contains(sourceExtension) : previous.originalRetained(), thumbnailOwned);
            sink.publishAndSave(transactions, newFiles, page, stem, manifest, cancelled);
            if (!outputs.contains(sourceExtension) && !sink.keepStagedSource()) Files.delete(source);
            return List.copyOf(outputs);
        } finally {
            if (stagedThumbnail != null) Files.deleteIfExists(stagedThumbnail);
            for (Path temporary : pending) Files.deleteIfExists(temporary);
        }
    }

    /** 根据调用方核实的页资产补缺失副本，沿用完整基名和既有媒体的原始格式身份。 */
    public void addMissingFormats(long artworkId, int page, Path source, String selectedFormats, BooleanSupplier cancelled,
                                  DownloadPathGuard paths) throws IOException {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 1) throw new IOException("Invalid image filename");
        String extension = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        if (!List.of("jpg", "jpeg", "png", "webp").contains(extension)) {
            throw new IOException("Animation requires its original frame archive");
        }
        Path stem = source.resolveSibling(name.substring(0, dot));
        var previous = mediaStore.find(artworkId, page);
        if (previous.isPresent() && previous.get().originalExtension().equals("zip")) {
            throw new IOException("Animation requires its original frame archive");
        }
        if (extension.equals("webp")) {
            byte[] header;
            try (var input = Files.newInputStream(source)) { header = input.readNBytes(21); }
            if (header.length >= 21 && new String(header, 12, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("VP8X")
                    && (header[20] & 2) != 0) throw new IOException("Animated image cannot be converted as a still image");
        }
        List<String> missing = new ArrayList<>(List.of("original"));
        for (String format : MediaOutputSettings.parseFormats(selectedFormats, MediaOutputSettings.IMAGE_FORMATS)) {
            if (!format.equals("original") && !Files.exists(withExtension(stem, format))) missing.add(format);
        }
        if (missing.size() == 1) return;
        var settings = new MediaOutputSettings();
        settings.setImageFormats(String.join(",", missing));
        process(page, stem, extension, settings, cancelled,
                previous.orElse(new ArtworkMediaManifest(extension, List.of(extension))), null, MediaSink.forMaintenance(artworkId, mediaStore, paths));
    }

    @FunctionalInterface
    public interface MediaSink {
        void save(int page, Path stem, ArtworkMediaManifest media) throws IOException;

        /** 历史补转只占用实际新增的输出，直至文件及媒体登记一起提交或回滚。 */
        static MediaSink forMaintenance(long artworkId, ArtworkMediaStore store, DownloadPathGuard paths) {
            return new MediaSink() {
                @Override public void save(int page, Path stem, ArtworkMediaManifest media) throws IOException {
                    store.save(artworkId, page, media);
                }

                @Override public void publishAndSave(FileTransactionService transactions, Map<Path, Path> newFiles, int page,
                                                     Path stem, ArtworkMediaManifest media, BooleanSupplier cancelled) throws IOException {
                    checkCancelled(cancelled);
                    try (var reservation = java.util.Objects.requireNonNull(paths.reserveOutputs(artworkId, newFiles.keySet()))) {
                        MediaSink.super.publishAndSave(transactions, newFiles, page, stem, media, cancelled);
                    } catch (top.sywyar.pixivdownload.core.work.directory.DirectoryTemplateException conflict) {
                        throw new IOException("Another work uses a requested media output", conflict);
                    }
                }
            };
        }

        /** 延迟正式登记的首次下载在暂存目录保留原件，提交失败时可交还已下载的源文件。 */
        default boolean keepStagedSource() { return false; }

        default void publishAndSave(FileTransactionService transactions, Map<Path, Path> newFiles, int page, Path stem,
                                    ArtworkMediaManifest media, BooleanSupplier cancelled) throws IOException {
            checkCancelled(cancelled);
            if (newFiles.isEmpty()) {
                save(page, stem, media);
                return;
            }
            try {
                transactions.changeFiles(newFiles, List.of(), newFiles.keySet(), () -> {
                    checkCancelled(cancelled);
                    try { save(page, stem, media); }
                    catch (IOException failure) { throw new UncheckedIOException(failure); }
                    checkCancelled(cancelled);
                });
            } catch (UncheckedIOException failure) {
                throw failure.getCause();
            }
        }
    }

    public MediaSink downloadedMedia(long artworkId, boolean partial) {
        return partial
                ? (page, stem, media) -> mediaStore.savePartial(artworkId, page, media, PartialArtwork.fingerprint(stem, media))
                : (page, stem, media) -> mediaStore.save(artworkId, page, media);
    }

    private void checkInput(Path source, BooleanSupplier cancelled, Consumer<FfmpegRunner.Phase> progress) throws IOException {
        checkCancelled(cancelled);
        long bytes = Files.size(source);
        if (bytes <= 0 || bytes > PixivImageTransferObserver.MAX_IMAGE_BYTES) throw new IOException("Invalid image byte size");
        int width = 0;
        int height = 0;
        try (var input = ImageIO.createImageInputStream(source.toFile())) {
            if (input != null) {
                var readers = ImageIO.getImageReaders(input);
                if (readers.hasNext()) {
                    var reader = readers.next();
                    try {
                        reader.setInput(input);
                        width = reader.getWidth(0);
                        height = reader.getHeight(0);
                    } finally { reader.dispose(); }
                }
            }
        }
        if (width == 0 || height == 0) {
            String json = runner.run(FfmpegRunner.Tool.FFPROBE,
                    List.of("-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
                            "-of", "json", source.toAbsolutePath().toString()),
                    null, null, 0, Duration.ofSeconds(30), cancelled, progress);
            var stream = mapper.readTree(json).path("streams").path(0);
            width = stream.path("width").asInt();
            height = stream.path("height").asInt();
        }
        if (width <= 0 || height <= 0 || width > 25_000 || height > 25_000 || (long) width * height > MAX_PIXELS) {
            throw new IOException("Image dimensions exceed conversion budget");
        }
    }

    private void writeThumbnail(Path source, Path temporary, BooleanSupplier cancelled,
                                Consumer<FfmpegRunner.Phase> progress) throws IOException {
        try {
            if (!ImageIO.write(ImageThumbnailScaler.scale(source, 1600, 1600), "jpg", temporary.toFile())) {
                throw new IOException("JPEG writer unavailable");
            }
        } catch (IOException unsupported) {
            runner.run(FfmpegRunner.Tool.FFMPEG,
                    List.of("-y", "-nostdin", "-v", "error", "-i", source.toAbsolutePath().toString(),
                            "-frames:v", "1", "-vf", "scale=1600:1600:force_original_aspect_ratio=decrease",
                            temporary.toAbsolutePath().toString()), null, temporary,
                    PixivImageTransferObserver.MAX_IMAGE_BYTES, TIMEOUT, cancelled, progress);
        }
        checkCancelled(cancelled);
    }

    static void publish(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Consumer<FfmpegRunner.Phase> progress(Consumer<Progress> listener, String phase,
                                                        String format, Integer index, Integer count) {
        report(listener, phase, format, index, count);
        return state -> report(listener, state == FfmpegRunner.Phase.WAITING ? "ffmpeg-waiting" : phase,
                format, index, count);
    }

    private static void report(Consumer<Progress> listener, String phase, String format, Integer index, Integer count) {
        if (listener != null) listener.accept(new Progress(phase, format, index, count));
    }

    private static Path withExtension(Path stem, String extension) {
        return stem.resolveSibling(stem.getFileName() + "." + extension);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new CancellationException();
    }
}
