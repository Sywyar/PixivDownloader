package top.sywyar.pixivdownload.ffmpeg;

import top.sywyar.pixivdownload.common.AppInfo;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.io.IOException;
import java.net.URI;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Objects;

/**
 * 统一定位 FFmpeg，供桌面 GUI 与后端服务共用。
 */
public final class FfmpegLocator {

    public static final String CONFIG_KEY = "ffmpeg.executable-path";
    private static final String OS_NAME = System.getProperty("os.name", "");
    private static final boolean WINDOWS = OS_NAME.toLowerCase(Locale.ROOT).contains("win");
    private static ConfiguredPath cachedConfig;

    private record ConfiguredPath(Path file, FileTime modified, long size, Object key, String value) {}

    private FfmpegLocator() {}

    public static boolean isWindows() {
        return WINDOWS;
    }

    public static String executableName() {
        return WINDOWS ? "ffmpeg.exe" : "ffmpeg";
    }

    public static String probeExecutableName() {
        return WINDOWS ? "ffprobe.exe" : "ffprobe";
    }

    public static String fallbackCommand() {
        return executableName();
    }

    public static Optional<FfmpegInstallation> locate() {
        return locate(RuntimeFiles.resolveConfigYamlPath());
    }

    static Optional<FfmpegInstallation> locate(Path configFile) {
        try {
            String configured = configuredPath(configFile);
            if (configured != null && !configured.isBlank()) {
                return Optional.of(requireConfiguredInstallation(configured));
            }
        } catch (IOException | RuntimeException invalid) {
            // 手工编辑后配置可能失效；保留既有自动查找能力。
        }
        Optional<FfmpegInstallation> managed = managedInstallation();
        if (managed.isPresent()) {
            return managed;
        }

        Optional<FfmpegInstallation> bundled = bundledInstallation();
        if (bundled.isPresent()) {
            return bundled;
        }

        return systemInstallation();
    }

    private static synchronized String configuredPath(Path configFile) throws IOException {
        Path file = configFile.toAbsolutePath().normalize();
        if (!Files.exists(file)) {
            cachedConfig = null;
            return null;
        }
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        if (cachedConfig == null || !cachedConfig.file().equals(file)
                || !cachedConfig.modified().equals(attributes.lastModifiedTime())
                || cachedConfig.size() != attributes.size()
                || !Objects.equals(cachedConfig.key(), attributes.fileKey())) {
            String value = new ConfigFileEditor(file).read(CONFIG_KEY);
            cachedConfig = new ConfiguredPath(file, attributes.lastModifiedTime(), attributes.size(), attributes.fileKey(), value);
        }
        return cachedConfig.value();
    }

    /** GUI 保存前校验；空值表示恢复自动查找。 */
    public static void validateConfiguredPath(String value) throws IOException {
        if (value != null && !value.isBlank()) {
            requireConfiguredInstallation(value);
        }
    }

    static FfmpegInstallation requireConfiguredInstallation(String value) throws IOException {
        try {
            Path selected = Path.of(value.trim());
            if (!selected.isAbsolute()) {
                throw new IOException("relative FFmpeg path");
            }
            Path executable = Files.isDirectory(selected) ? selected.resolve(executableName()) : selected;
            String name = executable.getFileName() == null ? "" : executable.getFileName().toString();
            boolean matchingName = WINDOWS ? executableName().equalsIgnoreCase(name) : executableName().equals(name);
            if (!matchingName || !Files.isRegularFile(executable)) {
                throw new IOException("FFmpeg executable is missing");
            }
            Path realExecutable = executable.toRealPath();
            Path directory = realExecutable.getParent();
            Path probe = probePath(directory);
            return new FfmpegInstallation(realExecutable,
                    probe,
                    directory,
                    FfmpegInstallation.Source.CUSTOM);
        } catch (IOException | RuntimeException invalid) {
            throw new IOException(MessageBundles.get("gui.ffmpeg.config.path-invalid", value, executableName()), invalid);
        }
    }

    public static String resolveFfmpegCommand() {
        return locate()
                .filter(FfmpegInstallation::hasFfmpeg)
                .map(FfmpegInstallation::ffmpegPath)
                .map(Path::toString)
                .orElseGet(FfmpegLocator::fallbackCommand);
    }

    public static Path managedRootDir() {
        return packagedApplicationRoot()
                .or(FfmpegLocator::workingDirectory)
                .or(FfmpegLocator::launcherRoot)
                .orElseGet(() -> defaultManagedRoot(OS_NAME, System.getenv("LOCALAPPDATA"), userHomeDir()));
    }

    public static Path managedToolsDir() {
        return managedRootDir().resolve("tools").resolve("ffmpeg");
    }

    public static Path managedLicenseDir() {
        return managedToolsDir().resolve("licenses");
    }

    static Path defaultManagedRoot(String osName, String localAppData, Path userHome) {
        boolean windows = osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
        if (windows && localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData).resolve(AppInfo.LEGACY_ARTIFACT_NAME);
        }
        return userHome.resolve(AppInfo.HIDDEN_DIRECTORY_NAME);
    }

    static Optional<FfmpegInstallation> installationAt(Path root, FfmpegInstallation.Source source) {
        if (root == null || !Files.isDirectory(root)) {
            return Optional.empty();
        }

        Path ffmpegPath = root.resolve(executableName());
        if (!Files.isRegularFile(ffmpegPath)) {
            return Optional.empty();
        }

        Path ffprobePath = probePath(root);
        return Optional.of(new FfmpegInstallation(
                ffmpegPath,
                ffprobePath,
                root,
                source
        ));
    }

    static Optional<FfmpegInstallation> managedInstallation() {
        return installationAt(managedToolsDir(), FfmpegInstallation.Source.MANAGED);
    }

    static Optional<FfmpegInstallation> bundledInstallation() {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        currentProcessRoot().ifPresent(roots::add);
        launcherRoot().ifPresent(roots::add);
        workingDirectory().ifPresent(roots::add);

        for (Path root : roots) {
            Optional<FfmpegInstallation> installation = installationAt(root, FfmpegInstallation.Source.BUNDLED);
            if (installation.isPresent()) {
                return installation;
            }
        }
        return Optional.empty();
    }

    static Optional<FfmpegInstallation> systemInstallation() {
        return findOnPath(executableName(), System.getenv("PATH"), Path.of(""), WINDOWS)
                .map(executable -> new FfmpegInstallation(executable, probePath(executable.getParent()),
                        executable.getParent(), FfmpegInstallation.Source.SYSTEM));
    }

    private static Path probePath(Path directory) {
        Path sibling = directory.resolve(probeExecutableName());
        if (Files.isRegularFile(sibling)) return sibling;
        return findOnPath(probeExecutableName(), System.getenv("PATH"), Path.of(""), WINDOWS).orElse(null);
    }

    static Optional<Path> findOnPath(String executable, String path, Path workingDirectory, boolean windows) {
        LinkedHashSet<Path> directories = new LinkedHashSet<>();
        if (windows) directories.add(workingDirectory);
        if (path != null) {
            for (String entry : path.split(java.util.regex.Pattern.quote(File.pathSeparator), -1)) {
                if (entry.length() >= 2 && entry.startsWith("\"") && entry.endsWith("\"")) {
                    entry = entry.substring(1, entry.length() - 1);
                }
                try { directories.add(entry.isEmpty() ? workingDirectory : workingDirectory.resolve(entry)); }
                catch (java.nio.file.InvalidPathException ignored) { /* 无效 PATH 项不遮挡后续安装。 */ }
            }
        }
        for (Path directory : directories) {
            Path candidate = directory.resolve(executable).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static Optional<Path> currentProcessRoot() {
        try {
            return ProcessHandle.current().info().command()
                    .map(Path::of)
                    .map(Path::getParent)
                    .filter(Files::isDirectory);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Path> packagedApplicationRoot() {
        Optional<Path> location = codeSourceFile();
        if (location.isEmpty()) {
            return Optional.empty();
        }
        Path file = location.get();
        if (Files.isRegularFile(file)) {
            Path parent = file.getParent();
            if (parent != null && parent.getFileName() != null
                    && "app".equalsIgnoreCase(parent.getFileName().toString())) {
                Path appRoot = parent.getParent();
                if (appRoot != null && Files.isDirectory(appRoot)) {
                    return Optional.of(appRoot);
                }
            }
            return Optional.ofNullable(parent).filter(Files::isDirectory);
        }
        return Optional.empty();
    }

    private static Optional<Path> launcherRoot() {
        Optional<Path> packagedRoot = packagedApplicationRoot();
        if (packagedRoot.isPresent()) {
            return packagedRoot;
        }
        return codeSourceFile().filter(Files::isDirectory);
    }

    /**
     * 解析当前类对应的磁盘文件/目录。
     *
     * <p>jpackage 启动的 Spring Boot fat-jar 经 {@code JarLauncher} 加载时，
     * code source 的 location 是 {@code jar:} / {@code nested:} 形式的嵌套 URL
     * （类位于 jar 内的嵌套 jar 中）。直接 {@code Path.of(uri)} 会被路由进
     * {@code jdk.zipfs} 并抛出未受检的 {@link java.nio.file.FileSystemNotFoundException}。
     * 这里剥掉 {@code jar:}/{@code nested:} 包裹与内部 entry，得到外层归档/目录的
     * 真实路径；任何异常都吞掉返回空，FFmpeg 缺失只应降级而非中断启动。
     */
    private static Optional<Path> codeSourceFile() {
        try {
            var codeSource = FfmpegLocator.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return Optional.empty();
            }
            String location = codeSource.getLocation().toString();
            if (location.startsWith("jar:")) {
                location = location.substring(4);
            }
            if (location.startsWith("nested:")) {
                location = location.substring(7);
            }
            // 经典 jar 形式用 "!/" 分隔内部 entry，Spring Boot nested 形式用 "/!"，
            // 截到第一个 '!' 再去掉可能残留的尾部 '/' 即可同时覆盖两者。
            int entrySeparator = location.indexOf('!');
            if (entrySeparator >= 0) {
                location = location.substring(0, entrySeparator);
            }
            while (location.endsWith("/") && !location.endsWith(":/")) {
                location = location.substring(0, location.length() - 1);
            }
            Path path = location.startsWith("file:")
                    ? Path.of(new URI(location))
                    : Path.of(location);
            return Optional.of(path);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Path> workingDirectory() {
        try {
            Path dir = Path.of(System.getProperty("user.dir", ""));
            return Files.isDirectory(dir) ? Optional.of(dir) : Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Path userHomeDir() {
        return Path.of(System.getProperty("user.home", "."));
    }
}
