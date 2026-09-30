package top.sywyar.pixivdownload.core.asset;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 文件删除和替换共用耐久备份；主库提交标记决定启动时复原还是清理。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StagedFileDeletion {
    private static final int MAX_FILES = 10_000;
    private final AppMessages messages;
    private final FileOperationJournal journal;

    @PostConstruct
    public void recoverPending() {
        for (String operation : journal.operations()) {
            try {
                Path directory = stagingDirectory(operation);
                List<FileOperationJournal.Entry> entries = journal.entries(operation);
                cleanInstallTemporaries(entries, directory);
                if (!journal.committed(operation)) restore(entries, directory);
                cleanStaging(directory);
                journal.forget(operation);
            } catch (IOException failure) {
                throw new IllegalStateException("File operation recovery failed: " + operation, failure);
            }
        }
    }

    public boolean deleteAtomically(Collection<Path> files) {
        return deleteAtomically(files, () -> {});
    }

    /** 回调内的主库写入与提交标记同事务完成，回调不得开启独立事务。 */
    public boolean deleteAtomically(Collection<Path> files, Runnable commitRecord) {
        Map<Path, Path> targets = new LinkedHashMap<>();
        if (files != null) for (Path file : files) {
            if (file == null) continue;
            Path target = file.toAbsolutePath().normalize();
            if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) continue;
            requireSafeDeleteTarget(target);
            targets.put(target, null);
        }
        try {
            changeFiles(targets, commitRecord);
            return true;
        } catch (IOException failure) {
            log.warn(messages.getForLog("download.delete.log.stage-failed", failure.getMessage()));
            return false;
        }
    }

    /** 将已完成的暂存文件发布到目标路径；失败恢复旧文件，记录提交后才释放备份。 */
    public void publishFiles(Map<Path, Path> stagedByTarget, Runnable commitRecord) throws IOException {
        Map<Path, Path> targets = new LinkedHashMap<>();
        for (var file : stagedByTarget.entrySet()) {
            Path target = file.getKey().toAbsolutePath().normalize();
            Path staged = file.getValue().toAbsolutePath().normalize();
            if (target.equals(staged) || targets.putIfAbsent(target, staged) != null) {
                throw new IOException("Duplicate file publication target");
            }
            PlainFilePathGuard.requirePlainRegularFile(staged);
        }
        changeFiles(targets, commitRecord);
    }

    private void changeFiles(Map<Path, Path> targets, Runnable commitRecord) throws IOException {
        Objects.requireNonNull(commitRecord);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("File publication must own its database transaction");
        }
        if (targets.size() > MAX_FILES) throw new IOException("Too many files in one file operation");
        String operation = UUID.randomUUID().toString();
        if (targets.isEmpty()) {
            journal.commit(operation, commitRecord);
            return;
        }
        Path directory = stagingDirectory(operation);
        Files.createDirectories(directory);
        PlainFilePathGuard.requirePlainParent(directory.resolve("check"), false);
        var entries = new ArrayList<FileOperationJournal.Entry>();
        boolean prepared = false;
        boolean committed = false;
        try {
            for (var target : targets.entrySet()) {
                Path file = target.getKey();
                PlainFilePathGuard.requirePlainParent(file, true);
                String oldHash = null;
                int index = entries.size();
                if (!Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
                    requireSafeDeleteTarget(file);
                    Path backup = directory.resolve(index + ".old");
                    durableCopy(file, backup);
                    oldHash = digest(backup);
                }
                String newHash = null;
                if (target.getValue() != null) {
                    Path replacement = directory.resolve(index + ".new");
                    durableCopy(target.getValue(), replacement);
                    newHash = digest(replacement);
                }
                entries.add(new FileOperationJournal.Entry(index, file, oldHash, newHash));
            }
            journal.prepare(operation, entries);
            prepared = true;
            for (var entry : entries) {
                requireContent(entry.target(), entry.oldHash());
                if (entry.newHash() == null) deleteFile(entry.target());
                else install(directory.resolve(entry.index() + ".new"), entry.target());
            }
            journal.commit(operation, commitRecord);
            committed = true;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                // COMMIT 响应异常时重新读取耐久标记，不能把已提交的文件复原。
                if (prepared && !journal.committed(operation)) restore(entries, directory);
                cleanStaging(directory);
                journal.forget(operation);
            } catch (Exception recoveryFailure) {
                failure.addSuppressed(recoveryFailure);
                log.error(messages.getForLog("download.delete.log.staging-retained", directory));
            }
            throw failure;
        }
        if (committed) {
            try {
                cleanStaging(directory);
                journal.forget(operation);
            } catch (IOException | RuntimeException failure) {
                // 提交已完成，残留只作清理，不把成功反报为失败或复原文件。
                log.warn(messages.getForLog("download.delete.log.staging-cleanup-failed", directory), failure);
            }
        }
    }

    private void restore(List<FileOperationJournal.Entry> entries, Path directory) throws IOException {
        cleanInstallTemporaries(entries, directory);
        IOException failure = null;
        for (var entry : entries) {
            try {
                String current = currentDigest(entry.target());
                if (Objects.equals(current, entry.oldHash())) continue;
                if (current != null && !Objects.equals(current, entry.newHash())) {
                    throw new IOException("Recovery target changed: " + entry.target());
                }
                if (entry.oldHash() == null) {
                    if (current != null) deleteFile(entry.target());
                } else {
                    Path backup = directory.resolve(entry.index() + ".old");
                    requireContent(backup, entry.oldHash());
                    restoreFile(backup, entry.target());
                }
            } catch (IOException | UnsafeDeletionPathException error) {
                if (failure == null) failure = new IOException("File rollback incomplete");
                failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    protected void deleteFile(Path original) throws IOException {
        requireSafeDeleteTarget(original);
        Files.delete(original);
    }

    protected void restoreFile(Path staged, Path original) throws IOException {
        install(staged, original);
    }

    private static void install(Path staged, Path target) throws IOException {
        PlainFilePathGuard.requirePlainRegularFile(staged);
        PlainFilePathGuard.requirePlainParent(target, true);
        Path temporary = installTemporary(staged, target);
        Files.createFile(temporary);
        try {
            Files.copy(staged, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            force(temporary);
            // 不支持原子替换的文件系统拒绝发布，不能退化为可能留下半个文件的覆盖复制。
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path installTemporary(Path staged, Path target) {
        return target.resolveSibling(".work-file-" + staged.getParent().getFileName()
                + "-" + staged.getFileName() + ".part");
    }

    /** 清理同卷发布中断留下的本操作临时文件；备份仍在独立数据目录。 */
    private static void cleanInstallTemporaries(List<FileOperationJournal.Entry> entries, Path directory) throws IOException {
        for (var entry : entries) {
            for (String suffix : List.of(".old", ".new")) {
                Path file = installTemporary(directory.resolve(entry.index() + suffix), entry.target());
                if (!Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
                    PlainFilePathGuard.requirePlainRegularFile(file);
                    Files.delete(file);
                }
            }
        }
    }

    private static void durableCopy(Path source, Path target) throws IOException {
        PlainFilePathGuard.requirePlainRegularFile(source);
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
        force(target);
    }

    private static void force(Path file) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    private static void requireContent(Path file, String expected) throws IOException {
        if (!Objects.equals(currentDigest(file), expected)) throw new IOException("File operation source changed: " + file);
    }

    private static String currentDigest(Path file) throws IOException {
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        PlainFilePathGuard.requirePlainRegularFile(file);
        return digest(file);
    }

    private static String digest(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static Path stagingDirectory(String operation) throws IOException {
        if (!UUID.fromString(operation).toString().equals(operation)) throw new IOException("Invalid file operation ID");
        return RuntimeFiles.deleteStagingDirectory().resolve(operation);
    }

    private static void cleanStaging(Path directory) throws IOException {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        if (!PlainFilePathGuard.isPlainDirectory(directory)) throw new IOException("Unsafe staging directory");
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                PlainFilePathGuard.requirePlainRegularFile(file);
                Files.delete(file);
            }
        }
        Files.delete(directory);
    }

    private static void requireSafeDeleteTarget(Path path) {
        if (!PlainFilePathGuard.isPlainRegularFile(path)) throw new UnsafeDeletionPathException(path);
    }

    public static final class UnsafeDeletionPathException extends RuntimeException {
        private final String path;
        public UnsafeDeletionPathException(Path path) {
            this(path == null ? "" : path.toAbsolutePath().normalize().toString());
        }
        public UnsafeDeletionPathException(String path) {
            super("Unsafe deletion path: " + path);
            this.path = path == null ? "" : path;
        }
        public String path() { return path; }
    }
}
