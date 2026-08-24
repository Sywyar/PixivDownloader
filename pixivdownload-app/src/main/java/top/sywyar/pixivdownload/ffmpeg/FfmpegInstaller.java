package top.sywyar.pixivdownload.ffmpeg;

import top.sywyar.pixivdownload.common.AppInfo;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;

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
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 下载并安装适配当前系统的稳定版 FFmpeg。
 */
public final class FfmpegInstaller {

    public static final String RELEASE_BASE_URL = "https://github.com/Sywyar/"
            + "PixivDownloader-Remote-Content/releases/download/ffmpeg-stable/";
    private static final String FFMPEG_LICENSE = "ffmpeg-LGPLv2.1.txt";
    private static final String LIBWEBP_LICENSE = "libwebp-COPYING.txt";
    private static final String LIBWEBP_PATENTS = "libwebp-PATENTS.txt";

    private FfmpegInstaller() {}

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }

    public static boolean supportsManagedDownload() {
        return archiveUri(System.getProperty("os.name", ""), System.getProperty("os.arch", "")).isPresent();
    }

    static Optional<URI> archiveUri(String osName, String osArch) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        String normalizedArch = switch (arch) {
            case "amd64", "x86_64", "x64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> "";
        };
        boolean windows = os.startsWith("windows");
        boolean linux = os.contains("linux");
        boolean macos = os.startsWith("mac") || os.startsWith("darwin");
        String asset = "";
        if (windows && normalizedArch.equals("x64")) {
            asset = "ffmpeg-windows-x64.zip";
        } else if (linux && normalizedArch.equals("x64")) {
            asset = "ffmpeg-linux-x64.zip";
        } else if (linux && normalizedArch.equals("arm64")) {
            asset = "ffmpeg-linux-arm64.zip";
        } else if (macos && normalizedArch.equals("x64")) {
            asset = "ffmpeg-macos-x64.zip";
        } else if (macos && normalizedArch.equals("arm64")) {
            asset = "ffmpeg-macos-arm64.zip";
        }
        return asset.isEmpty() ? Optional.empty() : Optional.of(URI.create(RELEASE_BASE_URL + asset));
    }

    public static FfmpegInstallation installManaged(ProxySettings proxySettings,
                                                    ProgressListener listener)
            throws IOException, InterruptedException {
        URI archiveUri = archiveUri(System.getProperty("os.name", ""), System.getProperty("os.arch", ""))
                .orElseThrow(() -> new IOException(message("gui.ffmpeg.install.unsupported")));

        ProxySettings settings = proxySettings == null ? ProxySettings.disabled() : proxySettings;
        ProgressListener progress = listener == null ? ProgressListener.NO_OP : listener;

        Path tempDir = Files.createTempDirectory("pixivdownload-ffmpeg-");
        Path archive = tempDir.resolve("ffmpeg.zip");
        Path extracted = tempDir.resolve("extract");
        try {
            progress.onProgress(message("gui.ffmpeg.install.stage.connecting"), 0L, -1L);
            downloadArchive(archiveUri, settings, archive, progress);

            progress.onProgress(message("gui.ffmpeg.install.stage.extracting"), -1L, -1L);
            ExtractedFiles extractedFiles = extractRequiredFiles(archive, extracted);

            Path toolsDir = FfmpegLocator.managedToolsDir();
            Files.createDirectories(toolsDir);
            Files.copy(extractedFiles.ffmpeg(), toolsDir.resolve(FfmpegLocator.executableName()),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(extractedFiles.ffprobe(), toolsDir.resolve(FfmpegLocator.probeExecutableName()),
                    StandardCopyOption.REPLACE_EXISTING);
            makeExecutable(toolsDir.resolve(FfmpegLocator.executableName()));
            makeExecutable(toolsDir.resolve(FfmpegLocator.probeExecutableName()));

            Path licenseDir = FfmpegLocator.managedLicenseDir();
            Files.createDirectories(licenseDir);
            Files.copy(extractedFiles.ffmpegLicense(), licenseDir.resolve(FFMPEG_LICENSE),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(extractedFiles.libwebpLicense(), licenseDir.resolve(LIBWEBP_LICENSE),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(extractedFiles.libwebpPatents(), licenseDir.resolve(LIBWEBP_PATENTS),
                    StandardCopyOption.REPLACE_EXISTING);

            progress.onProgress(message("gui.ffmpeg.install.completed"), 1L, 1L);
            return FfmpegLocator.managedInstallation()
                    .orElseThrow(() -> new IOException(message("gui.ffmpeg.install.result-missing")));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void downloadArchive(URI source, ProxySettings proxySettings, Path target,
                                        ProgressListener listener)
            throws IOException, InterruptedException {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL);
        proxySettings.toProxySelector().ifPresent(builder::proxy);

        HttpRequest request = HttpRequest.newBuilder(source)
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", AppInfo.userAgent("ffmpeg-installer"))
                .GET()
                .build();

        HttpResponse<InputStream> response = builder.build()
                .send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(message("gui.ffmpeg.install.download-http-error", response.statusCode()));
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
                listener.onProgress(message("gui.ffmpeg.install.stage.downloading"), downloaded, total);
            }
        }
    }

    private static long contentLength(HttpResponse<?> response) {
        OptionalLong contentLength = response.headers().firstValueAsLong("Content-Length");
        return contentLength.isPresent() ? contentLength.getAsLong() : -1L;
    }

    static ExtractedFiles extractRequiredFiles(Path archive, Path extractDir) throws IOException {
        Files.createDirectories(extractDir);
        Path ffmpegPath = null;
        Path ffprobePath = null;
        Path ffmpegLicense = null;
        Path libwebpLicense = null;
        Path libwebpPatents = null;

        try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zipInputStream.closeEntry();
                    continue;
                }

                String normalizedName = entry.getName().replace('\\', '/');
                boolean ffmpeg = normalizedName.endsWith("/bin/" + FfmpegLocator.executableName());
                boolean ffprobe = normalizedName.endsWith("/bin/" + FfmpegLocator.probeExecutableName());
                boolean ffmpegLicenseEntry = normalizedName.endsWith("/licenses/" + FFMPEG_LICENSE);
                boolean libwebpLicenseEntry = normalizedName.endsWith("/licenses/" + LIBWEBP_LICENSE);
                boolean libwebpPatentsEntry = normalizedName.endsWith("/licenses/" + LIBWEBP_PATENTS);
                if (!ffmpeg && !ffprobe && !ffmpegLicenseEntry
                        && !libwebpLicenseEntry && !libwebpPatentsEntry) {
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
                    throw new IOException(message("gui.ffmpeg.install.archive.illegal-path", normalizedName));
                }

                Files.copy(zipInputStream, target, StandardCopyOption.REPLACE_EXISTING);
                if (ffmpeg) {
                    ffmpegPath = target;
                } else if (ffprobe) {
                    ffprobePath = target;
                } else if (ffmpegLicenseEntry) {
                    ffmpegLicense = target;
                } else if (libwebpLicenseEntry) {
                    libwebpLicense = target;
                } else if (libwebpPatentsEntry) {
                    libwebpPatents = target;
                }
                zipInputStream.closeEntry();
            }
        }

        if (ffmpegPath == null || ffprobePath == null) {
            throw new IOException(message("gui.ffmpeg.install.archive.invalid-structure",
                    FfmpegLocator.executableName(), FfmpegLocator.probeExecutableName()));
        }
        if (ffmpegLicense == null || libwebpLicense == null || libwebpPatents == null) {
            throw new IOException(message("gui.ffmpeg.install.result-missing"));
        }

        return new ExtractedFiles(ffmpegPath, ffprobePath, ffmpegLicense, libwebpLicense, libwebpPatents);
    }

    private static void makeExecutable(Path path) throws IOException {
        if (FfmpegLocator.isWindows()) {
            return;
        }
        if (!path.toFile().setExecutable(true, false)) {
            throw new IOException(message("gui.ffmpeg.install.result-missing"));
        }
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

    record ExtractedFiles(Path ffmpeg, Path ffprobe, Path ffmpegLicense,
                          Path libwebpLicense, Path libwebpPatents) {
        ExtractedFiles {
            Objects.requireNonNull(ffmpeg, "ffmpeg");
            Objects.requireNonNull(ffprobe, "ffprobe");
            Objects.requireNonNull(ffmpegLicense, "ffmpegLicense");
            Objects.requireNonNull(libwebpLicense, "libwebpLicense");
            Objects.requireNonNull(libwebpPatents, "libwebpPatents");
        }
    }
}
