package top.sywyar.pixivdownload.core.asset.artwork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.asset.BoundedImageDecoder;
import top.sywyar.pixivdownload.core.asset.ImageThumbnailScaler;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 缩略图与哈希共用首帧解码；FFmpeg 只生成缓存，不改写作品。 */
@Component
public final class ArtworkMediaDecoder {
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private final FfmpegRunner runner;
    private final ObjectMapper mapper;

    public ArtworkMediaDecoder(FfmpegRunner runner, ObjectMapper mapper) {
        this.runner = runner;
        this.mapper = mapper;
    }

    private BufferedImage readZipFrame(Path source, int edge, boolean cover) throws IOException {
        Path cache = RuntimeFiles.galleryThumbnailDirectory();
        Files.createDirectories(cache);
        Path temporary = Files.createTempFile(cache, "zip-frame-", ".image");
        try (var zip = new java.util.zip.ZipInputStream(Files.newInputStream(source))) {
            var entry = zip.getNextEntry();
            if (entry == null || entry.isDirectory()) throw new IOException("Missing animation frame");
            // 只读首帧，既不展开目录，也不以归档内路径创建文件。
            int limit = 32 * 1024 * 1024;
            long count = 0;
            try (var out = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = zip.read(buffer)) != -1) {
                    count += n;
                    if (count > limit) throw new IOException("Animation frame byte limit exceeded");
                    out.write(buffer, 0, n);
                }
            }
            BufferedImage image = decodeImage(temporary, edge, cover);
            if (image == null) throw new IOException("Invalid animation frame");
            return image;
        } finally { Files.deleteIfExists(temporary); }
    }

    public BufferedImage read(Path source, int edge) throws IOException {
        return read(source, edge, false);
    }

    public BufferedImage readCover(Path source, int edge) throws IOException {
        return read(source, edge, true);
    }

    private BufferedImage decodeImage(Path source, int edge, boolean cover) throws IOException {
        if (cover) return ImageThumbnailScaler.cover(source, edge);
        return edge > 0 ? ImageThumbnailScaler.scale(source, edge, edge) : BoundedImageDecoder.read(source);
    }

    private BufferedImage read(Path source, int edge, boolean cover) throws IOException {
        if (source.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) return readZipFrame(source, edge, cover);
        try {
            BufferedImage image = decodeImage(source, edge, cover);
            if (image == null) throw new IOException("Native image decoder unavailable");
            return image;
        } catch (java.io.InterruptedIOException cancelled) {
            throw cancelled;
        } catch (IOException unsupported) {
            if (Thread.currentThread().isInterrupted()) {
                var cancelled = new java.io.InterruptedIOException("Image decoding interrupted");
                cancelled.initCause(unsupported);
                throw cancelled;
            }
            if (Files.size(source) <= 0 || Files.size(source) > MAX_BYTES) throw unsupported;
            String json = runner.run(FfmpegRunner.Tool.FFPROBE,
                    List.of("-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
                            "-of", "json", source.toAbsolutePath().toString()),
                    null, null, 0, Duration.ofSeconds(30), () -> false);
            var stream = mapper.readTree(json).path("streams").path(0);
            int width = stream.path("width").asInt();
            int height = stream.path("height").asInt();
            if (width <= 0 || height <= 0 || width > 25_000 || height > 25_000
                    || (long) width * height > 25_000_000L) throw unsupported;
            Path cache = RuntimeFiles.galleryThumbnailDirectory();
            Files.createDirectories(cache);
            Path temporary = Files.createTempFile(cache, "decode-", ".png");
            try {
                List<String> args = new ArrayList<>(List.of("-y", "-nostdin", "-v", "error", "-i",
                        source.toAbsolutePath().toString(), "-frames:v", "1", "-an"));
                if (edge > 0) args.addAll(List.of("-vf", (cover ? "crop='min(iw,ih)':'min(iw,ih)'," : "")
                        + "scale=w='min(iw," + edge
                        + ")':h='min(ih," + edge + ")':force_original_aspect_ratio=decrease:flags=area"));
                args.add(temporary.toAbsolutePath().toString());
                runner.run(FfmpegRunner.Tool.FFMPEG, args, null, temporary, MAX_BYTES, Duration.ofMinutes(1), () -> false);
                BufferedImage image = decodeImage(temporary, edge, cover);
                if (image == null) throw new IOException("FFmpeg returned an unreadable image");
                return image;
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
