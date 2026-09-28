package top.sywyar.pixivdownload.download.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;
import top.sywyar.pixivdownload.core.asset.ImageThumbnailScaler;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner;
import top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 先生成全部选定产物，再发布清单并按选择移除原文件。 */
public final class ImageOutputService {
    static final long MAX_PIXELS = 25_000_000L;
    static final Duration TIMEOUT = Duration.ofMinutes(10);
    private final FfmpegRunner runner;
    private final ObjectMapper mapper;

    public ImageOutputService(FfmpegRunner runner, ObjectMapper mapper) {
        this.runner = runner;
        this.mapper = mapper;
    }

    public List<String> process(Path stem, String sourceExtension, MediaOutputSettings settings,
                                BooleanSupplier cancelled) throws IOException {
        return process(stem, sourceExtension, settings, cancelled, null, null);
    }

    /** 只报告机器阶段与输出序号；原始路径、命令和异常诊断不进入队列状态。 */
    public record Progress(String phase, String outputFormat, Integer outputIndex, Integer outputCount) {}

    public List<String> process(Path stem, String sourceExtension, MediaOutputSettings settings,
                                BooleanSupplier cancelled, Consumer<Progress> progress) throws IOException {
        return process(stem, sourceExtension, settings, cancelled, null, progress);
    }

    private List<String> process(Path stem, String sourceExtension, MediaOutputSettings settings,
                                 BooleanSupplier cancelled, ArtworkMediaManifest previous,
                                 Consumer<Progress> progress) throws IOException {
        List<String> selected = MediaOutputSettings.parseFormats(
                settings.getImageFormats(), MediaOutputSettings.IMAGE_FORMATS);
        if (selected.stream().allMatch(format -> format.equals("original") || format.equals(sourceExtension)
                || format.equals("jpg") && sourceExtension.equals("jpeg"))) return List.of(sourceExtension);
        Path source = withExtension(stem, sourceExtension);
        int outputCount = (int) selected.stream().filter(format -> !format.equals("original")
                && !format.equals(sourceExtension) && !(format.equals("jpg") && sourceExtension.equals("jpeg"))).count();
        checkInput(source, cancelled, progress(progress, "verifying", null, null, outputCount));
        LinkedHashSet<String> outputs = new LinkedHashSet<>();
        if (selected.contains("original")) outputs.add(sourceExtension);
        List<Path> pending = new ArrayList<>();
        List<Path> destinations = new ArrayList<>();
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
            if (outputs.contains("webp") && (previous == null || !Files.exists(stem.resolveSibling(stem.getFileName() + "_thumb.jpg")))) {
                writeThumbnail(source, stem, cancelled, progress(progress, "thumbnail", null, null, outputCount));
            }
            checkCancelled(cancelled);
            report(progress, "finalizing", null, null, outputCount);
            for (int index = 0; index < pending.size(); index++) {
                if (previous == null) publish(pending.get(index), destinations.get(index));
                else {
                    try { Files.move(pending.get(index), destinations.get(index)); }
                    catch (java.nio.file.FileAlreadyExistsException exists) { /* 预览后新增的副本也保留。 */ }
                }
            }
            LinkedHashSet<String> recorded = new LinkedHashSet<>();
            if (previous != null) recorded.addAll(previous.extensions());
            recorded.addAll(outputs);
            new ArtworkMediaManifest(previous == null ? sourceExtension : previous.originalExtension(), List.copyOf(recorded),
                    previous == null ? outputs.contains(sourceExtension) : previous.originalRetained()).write(stem);
            if (!outputs.contains(sourceExtension)) Files.delete(source);
            return List.copyOf(outputs);
        } finally {
            for (Path temporary : pending) Files.deleteIfExists(temporary);
        }
    }

    /** 历史处理只补缺失副本，既有媒体及其原始格式身份保持不变。 */
    public void addMissingFormats(Path source, String selectedFormats, BooleanSupplier cancelled) throws IOException {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 1) throw new IOException("Invalid image filename");
        String extension = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        if (!List.of("jpg", "jpeg", "png", "webp").contains(extension) || name.endsWith("_thumb.jpg")) {
            throw new IOException("Animation requires its original frame archive");
        }
        Path stem = source.resolveSibling(name.substring(0, dot));
        var previous = ArtworkMediaManifest.read(stem);
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
        process(stem, extension, settings, cancelled,
                previous.orElse(new ArtworkMediaManifest(extension, List.of(extension))), null);
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

    private void writeThumbnail(Path source, Path stem, BooleanSupplier cancelled,
                                Consumer<FfmpegRunner.Phase> progress) throws IOException {
        Path temporary = Files.createTempFile(stem.toAbsolutePath().getParent(), ".media-thumb-", ".jpg");
        try {
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
            publish(temporary, stem.resolveSibling(stem.getFileName() + "_thumb.jpg"));
        } finally {
            Files.deleteIfExists(temporary);
        }
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
