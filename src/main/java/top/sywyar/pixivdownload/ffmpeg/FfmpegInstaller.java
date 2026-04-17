package top.sywyar.pixivdownload.ffmpeg;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 下载并安装 Windows 版 FFmpeg。
 */
public final class FfmpegInstaller {

    public static final String WINDOWS_ARCHIVE_URL =
            "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-lgpl.zip";

    public static final String LGPL_NOTICE = """
            FFmpeg is licensed under the LGPL v2.1.
            Source code: https://ffmpeg.org
            Build: BtbN FFmpeg Builds (https://github.com/BtbN/FFmpeg-Builds)
            LGPL License: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
            """;

    private FfmpegInstaller() {}

    public static boolean supportsManagedDownload() {
        return FfmpegLocator.isWindows();
    }

    public static FfmpegInstallation installManaged(ProxySettings proxySettings,
                                                    ProgressListener listener)
            throws IOException, InterruptedException {
        if (!supportsManagedDownload()) {
            throw new IOException("当前系统暂不支持自动下载 FFmpeg，请自行安装到 PATH。");
        }

        ProxySettings settings = proxySettings == null ? ProxySettings.disabled() : proxySettings;
        ProgressListener progress = listener == null ? ProgressListener.NO_OP : listener;

        Path tempDir = Files.createTempDirectory("pixivdownload-ffmpeg-");
        Path archive = tempDir.resolve("ffmpeg.zip");
        Path extracted = tempDir.resolve("extract");
        try {
            progress.onProgress("正在连接 FFmpeg 发布源…", 0L, -1L);
            downloadArchive(settings, archive, progress);

            progress.onProgress("正在解压 FFmpeg…", -1L, -1L);
            ExtractedFiles extractedFiles = extractRequiredFiles(archive, extracted);

            Path toolsDir = FfmpegLocator.managedToolsDir();
            Files.createDirectories(toolsDir);
            Files.copy(extractedFiles.ffmpeg(), toolsDir.resolve(FfmpegLocator.executableName()),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(extractedFiles.ffprobe(), toolsDir.resolve(FfmpegLocator.probeExecutableName()),
                    StandardCopyOption.REPLACE_EXISTING);

            Path licenseDir = FfmpegLocator.managedLicenseDir();
            Files.createDirectories(licenseDir);
            Files.writeString(licenseDir.resolve("ffmpeg-LGPL.txt"), LGPL_NOTICE);

            progress.onProgress("FFmpeg 已安装到用户目录。", 1L, 1L);
            return FfmpegLocator.managedInstallation()
                    .orElseThrow(() -> new IOException("FFmpeg 安装完成，但未检测到安装结果。"));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void downloadArchive(ProxySettings proxySettings, Path target,
                                        ProgressListener listener)
            throws IOException, InterruptedException {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL);
        proxySettings.toProxySelector().ifPresent(builder::proxy);

        HttpRequest request = HttpRequest.newBuilder(URI.create(WINDOWS_ARCHIVE_URL))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "PixivDownload/ffmpeg-installer")
                .GET()
                .build();

        HttpResponse<InputStream> response = builder.build()
                .send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("FFmpeg 下载失败，HTTP 状态码: " + response.statusCode());
        }

        long total = contentLength(response);
        try (InputStream inputStream = response.body();
             OutputStream outputStream = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            long downloaded = 0L;
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
                downloaded += read;
                listener.onProgress("正在下载 FFmpeg…", downloaded, total);
            }
        }
    }

    private static long contentLength(HttpResponse<?> response) {
        OptionalLong contentLength = response.headers().firstValueAsLong("Content-Length");
        return contentLength.isPresent() ? contentLength.getAsLong() : -1L;
    }

    private static ExtractedFiles extractRequiredFiles(Path archive, Path extractDir) throws IOException {
        Files.createDirectories(extractDir);
        Path ffmpegPath = null;
        Path ffprobePath = null;

        try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zipInputStream.closeEntry();
                    continue;
                }

                String normalizedName = entry.getName().replace('\\', '/');
                if (!normalizedName.endsWith("/bin/" + FfmpegLocator.executableName())
                        && !normalizedName.endsWith("/bin/" + FfmpegLocator.probeExecutableName())) {
                    zipInputStream.closeEntry();
                    continue;
                }

                Path fileName = Path.of(normalizedName).getFileName();
                if (fileName == null) {
                    zipInputStream.closeEntry();
                    continue;
                }

                Path target = extractDir.resolve(fileName.toString()).normalize();
                if (!target.startsWith(extractDir)) {
                    throw new IOException("FFmpeg 压缩包包含非法路径: " + normalizedName);
                }

                Files.copy(zipInputStream, target, StandardCopyOption.REPLACE_EXISTING);
                if (fileName.toString().equalsIgnoreCase(FfmpegLocator.executableName())) {
                    ffmpegPath = target;
                } else if (fileName.toString().equalsIgnoreCase(FfmpegLocator.probeExecutableName())) {
                    ffprobePath = target;
                }
                zipInputStream.closeEntry();
            }
        }

        if (ffmpegPath == null || ffprobePath == null) {
            throw new IOException("FFmpeg 压缩包结构异常，未找到 ffmpeg.exe 或 ffprobe.exe。");
        }

        return new ExtractedFiles(ffmpegPath, ffprobePath);
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    public interface ProgressListener {
        ProgressListener NO_OP = (stage, current, total) -> {};

        void onProgress(String stage, long current, long total);
    }

    public record ProxySettings(boolean enabled, String host, int port) {

        public ProxySettings {
            host = host == null ? "" : host.trim();
        }

        public static ProxySettings disabled() {
            return new ProxySettings(false, "", 0);
        }

        public Optional<ProxySelector> toProxySelector() {
            if (!enabled || host.isBlank() || port <= 0) {
                return Optional.empty();
            }
            InetSocketAddress address = new InetSocketAddress(host, port);
            return Optional.of(ProxySelector.of(address));
        }
    }

    private record ExtractedFiles(Path ffmpeg, Path ffprobe) {
        private ExtractedFiles {
            Objects.requireNonNull(ffmpeg, "ffmpeg");
            Objects.requireNonNull(ffprobe, "ffprobe");
        }
    }
}
