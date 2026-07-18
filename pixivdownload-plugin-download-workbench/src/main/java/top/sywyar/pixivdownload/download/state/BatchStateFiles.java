package top.sywyar.pixivdownload.download.state;

import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.config.RuntimePathProvider;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** 下载工作台自有批量状态文件布局。 */
public final class BatchStateFiles {

    private static final String STATE_FILE_NAME = "batch_state.json";

    private final Path stateFile;

    public BatchStateFiles(RuntimePathProvider runtimePathProvider, DownloadSettings downloadSettings) {
        Objects.requireNonNull(runtimePathProvider, "runtimePathProvider");
        Objects.requireNonNull(downloadSettings, "downloadSettings");
        Path stateDirectory = runtimePathProvider
                .resolvePluginStateDirectory(DownloadWorkbenchPlugin.ID)
                .normalize();
        this.stateFile = stateDirectory.resolve(STATE_FILE_NAME).normalize();
        migrateLegacyState(stateDirectory, downloadSettings.getRootFolder());
    }

    public Path stateFile() {
        return stateFile;
    }

    private void migrateLegacyState(Path stateDirectory, String downloadRoot) {
        try {
            Files.createDirectories(stateDirectory);
            for (Path legacy : legacyCandidates(stateDirectory, downloadRoot)) {
                adoptLegacyFile(stateFile, legacy);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("migrate batch state failed: " + stateFile, e);
        }
    }

    private static Set<Path> legacyCandidates(Path ownerStateDirectory, String downloadRoot) {
        Set<Path> candidates = new LinkedHashSet<>();
        Path stateRoot = ownerStateDirectory.getParent();
        if (stateRoot != null) {
            candidates.add(stateRoot.resolve(STATE_FILE_NAME).normalize());
            Path workingDirectory = stateRoot.getParent();
            candidates.add((workingDirectory == null
                    ? Path.of(STATE_FILE_NAME)
                    : workingDirectory.resolve(STATE_FILE_NAME)).normalize());
        } else {
            candidates.add(Path.of(STATE_FILE_NAME));
        }
        if (downloadRoot != null && !downloadRoot.isBlank()) {
            candidates.add(Path.of(downloadRoot).resolve(STATE_FILE_NAME).normalize());
        }
        return candidates;
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
}
