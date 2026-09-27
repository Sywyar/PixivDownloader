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

    public BufferedImage read(Path source, int edge) throws IOException {
        try {
            BufferedImage image = edge > 0 ? ImageThumbnailScaler.scale(source, edge, edge) : BoundedImageDecoder.read(source);
            if (image == null) throw new IOException("Native image decoder unavailable");
            return image;
        } catch (IOException unsupported) {
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
                if (edge > 0) args.addAll(List.of("-vf", "scale=w='min(iw," + edge
                        + ")':h='min(ih," + edge + ")':force_original_aspect_ratio=decrease"));
                args.add(temporary.toAbsolutePath().toString());
                runner.run(FfmpegRunner.Tool.FFMPEG, args, null, temporary, MAX_BYTES, Duration.ofMinutes(1), () -> false);
                BufferedImage image = edge > 0 ? ImageThumbnailScaler.scale(temporary, edge, edge) : BoundedImageDecoder.read(temporary);
                if (image == null) throw new IOException("FFmpeg returned an unreadable image");
                return image;
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
