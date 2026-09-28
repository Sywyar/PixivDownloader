package top.sywyar.pixivdownload.download.media;

import top.sywyar.pixivdownload.core.ffmpeg.FfmpegCommandResolver;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/** 使用当前解析到的二进制实际编码及解码小样本，不由版本号推定功能。 */
public final class MediaCapabilityService {
    private final FfmpegRunner runner;
    private final FfmpegCommandResolver resolver;
    private final RuntimePathProvider paths;
    private final ReentrantLock checking = new ReentrantLock();

    public MediaCapabilityService(FfmpegRunner runner, FfmpegCommandResolver resolver, RuntimePathProvider paths) {
        this.runner = runner;
        this.resolver = resolver;
        this.paths = paths;
    }

    public record Capability(String name, boolean available) {}
    public record Report(String command, String source, List<Capability> capabilities) {}

    public Report check() throws IOException {
        if (!checking.tryLock()) throw top.sywyar.pixivdownload.download.web.LocalizedException.badRequest("download.media.busy", null);
        Path directory = null;
        try {
            Files.createDirectories(paths.dataDirectory());
            directory = Files.createTempDirectory(paths.dataDirectory(), "media-check-");
            for (int index = 0; index < 2; index++) {
                BufferedImage sample = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                sample.setRGB(8, 8, index == 0 ? 0xFFFF0000 : 0xFF0000FF);
                ImageIO.write(sample, "png", directory.resolve(index + ".png").toFile());
            }
            Files.writeString(directory.resolve("frames.txt"), "file '0.png'\nduration 0.1\nfile '1.png'\nduration 0.1\nfile '1.png'\n", StandardCharsets.UTF_8);
            var resolved = resolver.resolve();
            List<Capability> capabilities = new ArrayList<>();
            boolean probeAvailable;
            try {
                probeAvailable = runner.run(FfmpegRunner.Tool.FFPROBE,
                        List.of("-v", "error", "-show_entries", "stream=width,height", "-of", "csv=p=0", "0.png"),
                        directory, null, 0, Duration.ofSeconds(10), () -> false).trim().equals("16,16");
            } catch (IOException unavailable) { probeAvailable = false; }
            capabilities.add(new Capability("ffprobe", probeAvailable));
            for (String format : List.of("png", "jpg", "webp", "gif", "apng", "mp4")) {
                Path output = directory.resolve("output." + format);
                List<String> arguments = new ArrayList<>(List.of("-y", "-nostdin", "-v", "error"));
                if (format.equals("png") || format.equals("jpg")) {
                    arguments.addAll(List.of("-i", "0.png", "-frames:v", "1"));
                } else {
                    arguments.addAll(List.of("-f", "concat", "-safe", "0", "-i", "frames.txt"));
                    arguments.addAll(UgoiraEncoding.arguments(format, new MediaOutputSettings()));
                }
                arguments.add(output.getFileName().toString());
                capabilities.add(new Capability(format, tryRun(FfmpegRunner.Tool.FFMPEG, arguments, directory, output)));
            }
            Path decoded = directory.resolve("decoded.png");
            capabilities.add(new Capability("animated-webp-decode", tryRun(FfmpegRunner.Tool.FFMPEG,
                    List.of("-y", "-nostdin", "-v", "error", "-i", "output.webp", "-frames:v", "1", "decoded.png"), directory, decoded)
                    && ImageIO.read(decoded.toFile()) != null));
            Path still = directory.resolve("still.webp");
            capabilities.add(new Capability("static-webp", tryRun(FfmpegRunner.Tool.FFMPEG,
                    List.of("-y", "-nostdin", "-v", "error", "-i", "0.png", "-frames:v", "1", "-c:v", "libwebp", "still.webp"), directory, still)));
            capabilities.add(new Capability("static-webp-decode", tryRun(FfmpegRunner.Tool.FFMPEG,
                    List.of("-y", "-nostdin", "-v", "error", "-i", "still.webp", "-frames:v", "1", "decoded.png"), directory, decoded)
                    && ImageIO.read(decoded.toFile()) != null));
            return new Report(resolved.command(), resolved.source().name().toLowerCase(java.util.Locale.ROOT), List.copyOf(capabilities));
        } finally {
            try {
                if (directory != null) {
                    try (var files = Files.list(directory)) {
                        for (Path file : files.toList()) Files.deleteIfExists(file);
                    }
                    Files.deleteIfExists(directory);
                }
            } finally { checking.unlock(); }
        }
    }

    private boolean tryRun(FfmpegRunner.Tool tool, List<String> arguments, Path directory, Path output) {
        try {
            runner.run(tool, arguments, directory, output, 1024 * 1024, Duration.ofSeconds(10), () -> false);
            return true;
        } catch (IOException failure) { return false; }
    }
}
