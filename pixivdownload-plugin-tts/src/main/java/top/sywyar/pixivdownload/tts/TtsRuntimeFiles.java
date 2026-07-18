package top.sywyar.pixivdownload.tts;

import top.sywyar.pixivdownload.config.RuntimePathProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** TTS 插件自有运行期文件布局。 */
public final class TtsRuntimeFiles {

    private static final String CHROMIUM_VERSION_FILE_NAME = "chromium-version.txt";
    private static final String LEGACY_DATA_DIRECTORY_NAME = "_tts";

    private final RuntimePathProvider runtimePathProvider;

    public TtsRuntimeFiles(RuntimePathProvider runtimePathProvider) {
        this.runtimePathProvider = Objects.requireNonNull(runtimePathProvider, "runtimePathProvider");
    }

    public Path chromiumVersionFile() {
        Path dataDirectory = runtimePathProvider.resolvePluginDataDirectory(TtsPlugin.ID).normalize();
        Path target = dataDirectory.resolve(CHROMIUM_VERSION_FILE_NAME).normalize();
        Path dataRoot = dataDirectory.getParent();
        Path legacyDirectory = dataRoot == null
                ? Path.of(LEGACY_DATA_DIRECTORY_NAME)
                : dataRoot.resolveSibling(LEGACY_DATA_DIRECTORY_NAME).normalize();
        try {
            migrateLegacyDirectory(dataDirectory, legacyDirectory);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("migrate TTS runtime file failed: " + target, e);
        }
    }

    private static void migrateLegacyDirectory(Path target, Path legacy) throws IOException {
        if (!Files.isDirectory(legacy, LinkOption.NOFOLLOW_LINKS)
                || target.toAbsolutePath().normalize().equals(legacy.toAbsolutePath().normalize())) {
            return;
        }
        Files.createDirectories(target);
        List<Path> sources;
        try (Stream<Path> stream = Files.walk(legacy)) {
            sources = stream.sorted(Comparator.comparingInt(Path::getNameCount)).toList();
        }
        for (Path source : sources) {
            if (source.equals(legacy)) {
                continue;
            }
            Path destination = target.resolve(legacy.relativize(source)).normalize();
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                }
                continue;
            }
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || !ensureDirectory(destination.getParent())) {
                continue;
            }
            adoptLegacyFile(destination, source);
        }
        deleteEmptyDirectories(legacy);
    }

    private static boolean ensureDirectory(Path directory) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS);
        }
        Files.createDirectories(directory);
        return true;
    }

    private static void adoptLegacyFile(Path target, Path legacy) throws IOException {
        if (!Files.isRegularFile(legacy, LinkOption.NOFOLLOW_LINKS)
                || target.toAbsolutePath().normalize().equals(legacy.toAbsolutePath().normalize())) {
            return;
        }
        if (!Files.exists(target)) {
            moveWithoutOverwrite(legacy, target);
        }
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(legacy, LinkOption.NOFOLLOW_LINKS)
                && Files.size(target) == Files.size(legacy)
                && Files.mismatch(target, legacy) == -1) {
            Files.deleteIfExists(legacy);
        }
    }

    private static void moveWithoutOverwrite(Path source, Path target) throws IOException {
        try {
            Files.move(source, target);
            return;
        } catch (FileAlreadyExistsException ignored) {
            return;
        } catch (IOException moveFailure) {
            copyThroughTemporaryFile(source, target, moveFailure);
        }
    }

    private static void copyThroughTemporaryFile(Path source, Path target, IOException moveFailure) throws IOException {
        Path temporary = Files.createTempFile(
                target.getParent(), target.getFileName().toString() + ".migration-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target);
            } catch (FileAlreadyExistsException ignored) {
                return;
            }
            if (Files.size(target) == Files.size(source) && Files.mismatch(target, source) == -1) {
                Files.delete(source);
            }
        } catch (IOException copyFailure) {
            copyFailure.addSuppressed(moveFailure);
            throw copyFailure;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void deleteEmptyDirectories(Path legacy) throws IOException {
        List<Path> directories;
        try (Stream<Path> stream = Files.walk(legacy)) {
            directories = stream
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .toList();
        }
        for (Path directory : directories) {
            try {
                Files.deleteIfExists(directory);
            } catch (DirectoryNotEmptyException ignored) {
                // 保留未知文件或与 canonical 内容冲突的旧文件，避免迁移时丢失数据。
            }
        }
    }
}
