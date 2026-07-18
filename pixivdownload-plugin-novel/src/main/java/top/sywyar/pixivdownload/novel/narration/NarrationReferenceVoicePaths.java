package top.sywyar.pixivdownload.novel.narration;

import top.sywyar.pixivdownload.config.RuntimePathProvider;
import top.sywyar.pixivdownload.novel.NovelPlugin;

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
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/** 小说插件自有参考音文件布局。 */
public final class NarrationReferenceVoicePaths {

    private static final String DATA_DIRECTORY_NAME = "narration-voice";

    private final RuntimePathProvider runtimePathProvider;

    public NarrationReferenceVoicePaths(RuntimePathProvider runtimePathProvider) {
        this.runtimePathProvider = Objects.requireNonNull(runtimePathProvider, "runtimePathProvider");
    }

    public Path castDirectory(long castId) {
        Path directory = rootDirectory().resolve(Long.toString(castId)).normalize();
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("resolve narration reference voice directory failed: " + directory, e);
        }
        return directory;
    }

    public Path file(long castId, int characterId, String extension) {
        String normalizedExtension = extension == null ? "wav" : extension.trim().toLowerCase(Locale.ROOT);
        if (normalizedExtension.isEmpty()) {
            normalizedExtension = "wav";
        }
        if (!normalizedExtension.matches("[a-z0-9]{1,16}")) {
            throw new IllegalArgumentException("narration voice extension must be an alphanumeric token");
        }
        Path directory = castDirectory(castId);
        Path target = directory.resolve(characterId + "." + normalizedExtension).normalize();
        if (!target.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("narration voice file resolves outside the cast directory");
        }
        return target;
    }

    private Path rootDirectory() {
        Path pluginDataDirectory = runtimePathProvider.resolvePluginDataDirectory(NovelPlugin.ID).normalize();
        Path target = pluginDataDirectory.resolve(DATA_DIRECTORY_NAME).normalize();
        Path dataRoot = pluginDataDirectory.getParent();
        Path legacy = dataRoot == null
                ? Path.of(DATA_DIRECTORY_NAME)
                : dataRoot.resolve(DATA_DIRECTORY_NAME).normalize();
        try {
            migrateLegacyDirectory(target, legacy);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("migrate narration reference voice directory failed: " + target, e);
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
        if (!Files.isRegularFile(legacy, LinkOption.NOFOLLOW_LINKS)) {
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
