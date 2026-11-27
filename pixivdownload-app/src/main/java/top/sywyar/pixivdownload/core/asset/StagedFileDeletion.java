package top.sywyar.pixivdownload.core.asset;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.core.database.DatabaseUnavailableException;
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
public class StagedFileDeletion implements FileTransactionService {
    private static final int MAX_FILES = 10_000;
    private final AppMessages messages;
    private final FileOperationJournal journal;

    @PostConstruct
    public void recoverPending() {
        if (journal.isUnavailable()) return;
        for (String operation : journal.operations()) {
            try {
                requireDatabase();
                Path directory = stagingDirectory(operation);
                List<FileOperationJournal.Entry> entries = journal.entries(operation);
                boolean committed = journal.committed(operation);
                requireDatabase();
                if (!committed) restore(entries, directory);
                cleanInstallTemporaries(entries, directory);
                cleanStaging(directory);
                requireDatabase();
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
        try {
            changeFiles(Map.of(), files == null ? List.of() : files, commitRecord);
            return true;
        } catch (IOException failure) {
            log.warn(messages.getForLog("download.delete.log.stage-failed", failure.getMessage()));
            return false;
        }
    }

    /** 仅允许删除 ZIP 中记录的路径，备份内容必须与实际写入 ZIP 的摘要相同。 */
    public boolean deleteArchivedFiles(Map<Path, String> archived, Runnable commitRecord) {
        if (archived.isEmpty()) throw new IllegalArgumentException("Empty archive deletion");
        Map<Path, Path> targets = new LinkedHashMap<>();
        archived.keySet().forEach(path -> targets.put(path, null));
        try {
            applyChanges(targets, Set.of(), archived, commitRecord);
            return true;
        } catch (IOException failure) {
            log.warn(messages.getForLog("download.delete.log.stage-failed", failure.getMessage()));
            return false;
        }
    }

    public static void verifyArchivedFiles(Map<Path, String> archived) throws IOException {
        for (var source : archived.entrySet()) requireContent(source.getKey(), source.getValue());
    }

    /** 将已完成的暂存文件发布到目标路径；失败恢复旧文件，记录提交后才释放备份。 */
    public void publishFiles(Map<Path, Path> stagedByTarget, Runnable commitRecord) throws IOException {
        changeFiles(stagedByTarget, List.of(), commitRecord);
    }

    @Override
    public void changeFiles(Map<Path, Path> stagedByTarget, Collection<Path> deletions,
                            Runnable commitRecord) throws IOException {
        changeFiles(stagedByTarget, deletions, Set.of(), commitRecord);
    }

    @Override
    public void changeFiles(Map<Path, Path> stagedByTarget, Collection<Path> deletions, Set<Path> createOnly,
                            Runnable commitRecord) throws IOException {
        changeFiles(stagedByTarget, deletions, createOnly, Map.of(), commitRecord);
    }

    @Override
    public void changeFiles(Map<Path, Path> stagedByTarget, Collection<Path> deletions, Set<Path> createOnly,
                            Map<Path, String> expectedHashes, Runnable commitRecord) throws IOException {
        Map<Path, Path> targets = new LinkedHashMap<>();
        for (var file : stagedByTarget.entrySet()) {
            Path target = file.getKey().toAbsolutePath().normalize();
            Path staged = file.getValue().toAbsolutePath().normalize();
            if (target.equals(staged) || targets.putIfAbsent(target, staged) != null) {
                throw new IOException("Duplicate file publication target");
            }
            PlainFilePathGuard.requirePlainRegularFile(staged);
        }
        Set<Path> newTargets = new HashSet<>();
        for (Path file : createOnly) {
            Path target = file.toAbsolutePath().normalize();
            if (!targets.containsKey(target)) throw new IOException("Create-only path is not a publication target");
            if (!Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
            newTargets.add(target);
        }
        for (Path file : deletions) {
            if (file == null) continue;
            Path target = file.toAbsolutePath().normalize();
            if (targets.containsKey(target) && targets.get(target) != null) {
                throw new IOException("Conflicting file operation target");
            }
            if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) continue;
            requireSafeDeleteTarget(target);
            targets.put(target, null);
        }
        var expected = new HashMap<Path, String>();
        for (var entry : expectedHashes.entrySet()) {
            Path path = entry.getKey().toAbsolutePath().normalize();
            if (!targets.containsKey(path) || newTargets.contains(path)) throw new IOException("Invalid verified publication target");
            expected.put(path, Objects.requireNonNull(entry.getValue()));
        }
        applyChanges(targets, newTargets, expected, commitRecord);
    }

    private void applyChanges(Map<Path, Path> targets, Set<Path> createOnly, Map<Path, String> expectedHashes,
                              Runnable commitRecord) throws IOException {
        requireDatabase();
        Objects.requireNonNull(commitRecord);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("File publication must own its database transaction");
        }
        if (targets.size() > MAX_FILES) throw new IOException("Too many files in one file operation");
        String operation = UUID.randomUUID().toString();
        if (targets.isEmpty()) {
            requireDatabase();
            journal.commit(operation, commitRecord);
            return;
        }
        Path directory = stagingDirectory(operation);
        requireDatabase();
        Files.createDirectories(directory);
        PlainFilePathGuard.requirePlainParent(directory.resolve("check"), false);
        var entries = new ArrayList<FileOperationJournal.Entry>();
        boolean prepared = false;
        int applied = 0;
        try {
            for (var target : targets.entrySet()) {
                requireDatabase();
                Path file = target.getKey();
                PlainFilePathGuard.requirePlainParent(file, true);
                String oldHash = null;
                int index = entries.size();
                if (!Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
                    if (createOnly.contains(file)) throw new FileAlreadyExistsException(file.toString());
                    requireSafeDeleteTarget(file);
                    Path backup = directory.resolve(index + ".old");
                    durableCopy(file, backup);
                    oldHash = digest(backup);
                }
                if (expectedHashes.containsKey(file)) requireVerifiedContent(file, expectedHashes.get(file), oldHash);
                String newHash = null;
                if (target.getValue() != null) {
                    Path replacement = directory.resolve(index + ".new");
                    durableCopy(target.getValue(), replacement);
                    newHash = digest(replacement);
                }
                entries.add(new FileOperationJournal.Entry(index, file, oldHash, newHash));
            }
            requireDatabase();
            journal.prepare(operation, entries);
            prepared = true;
            for (var entry : entries) {
                boolean newTarget = entry.oldHash() == null && entry.newHash() != null;
                Path staged = entry.newHash() == null ? null : directory.resolve(entry.index() + ".new");
                Path temporary = staged == null ? null : prepareInstall(staged, entry.target());
                requireDatabase();
                // 同目录临时副本已经落盘；校验失败时本条目从未改动，回退仅覆盖前面的条目。
                if (expectedHashes.containsKey(entry.target()))
                    requireVerifiedContent(entry.target(), entry.oldHash(), currentDigest(entry.target()));
                else if (!newTarget) requireContent(entry.target(), entry.oldHash());
                applied = entry.index() + 1;
                if (staged == null) deleteFile(entry.target());
                else if (newTarget) publishNew(staged, temporary, entry.target());
                else Files.move(temporary, entry.target(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            requireDatabase();
            journal.commit(operation, commitRecord);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                // 无法读取提交事实时保留全部备份，交给下次数据库可用时恢复。
                requireDatabase();
                // COMMIT 响应异常时重新读取耐久标记，不能把已提交的文件复原。
                if (prepared && !journal.committed(operation)) restore(entries.subList(0, applied), directory);
                if (prepared) cleanInstallTemporaries(entries, directory);
                cleanStaging(directory);
                requireDatabase();
                journal.forget(operation);
            } catch (Exception recoveryFailure) {
                failure.addSuppressed(recoveryFailure);
                log.error(messages.getForLog("download.delete.log.staging-retained", directory));
            }
            throw failure;
        }
        try {
            cleanInstallTemporaries(entries, directory);
            cleanStaging(directory);
            requireDatabase();
            journal.forget(operation);
        } catch (IOException | RuntimeException failure) {
            // 提交已完成，残留只作清理，不把成功反报为失败或复原文件。
            log.warn(messages.getForLog("download.delete.log.staging-cleanup-failed", directory), failure);
        }
    }

    private void restore(List<FileOperationJournal.Entry> entries, Path directory) throws IOException {
        IOException failure = null;
        int unclaimed = 0;
        for (var entry : entries) {
            try {
                requireDatabase();
                if (entry.oldHash() == null) {
                    // An old journal or an interrupted prepare cannot prove who created the target.
                    if (entry.newHash() != null && !rollbackNewFile(entry, directory.resolve(entry.index() + ".new"))) unclaimed++;
                    continue;
                }
                String current = currentDigest(entry.target());
                if (Objects.equals(current, entry.oldHash())) continue;
                if (current != null && !Objects.equals(current, entry.newHash())) {
                    throw new IOException("Recovery target changed: " + entry.target());
                }
                Path backup = directory.resolve(entry.index() + ".old");
                requireContent(backup, entry.oldHash());
                cleanTemporary(installTemporary(backup, entry.target()));
                restoreFile(backup, entry.target());
            } catch (IOException | UnsafeDeletionPathException error) {
                if (failure == null) failure = new IOException("File rollback incomplete");
                failure.addSuppressed(error);
            }
        }
        if (unclaimed != 0) log.warn(messages.getForLog("download.delete.log.unclaimed-targets", directory.getFileName(), unclaimed));
        if (failure != null) throw failure;
    }

    /** Returns false if a current file must be left in place. */
    private boolean rollbackNewFile(FileOperationJournal.Entry entry, Path staged) throws IOException {
        Path target = entry.target();
        if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) return true;
        if (!PlainFilePathGuard.isPlainRegularFile(target)) return false;
        Path witness = installTemporary(staged, target);
        if (PlainFilePathGuard.isPlainRegularFile(witness)) {
            if (!entry.newHash().equals(digest(witness)) || !Files.isSameFile(witness, target)) return false;
            deleteFile(target);
            return true;
        }
        // A no-replace move consumed the temporary. Without a durable object identity the current target is ambiguous.
        Path intent = moveIntent(staged);
        if (PlainFilePathGuard.isPlainRegularFile(intent)) {
            String identity = fileIdentity(target);
            if (!identity.isEmpty() && Files.size(intent) == identity.length()
                    && identity.equals(Files.readString(intent, java.nio.charset.StandardCharsets.UTF_8))
                    && entry.newHash().equals(digest(target)) && identity.equals(fileIdentity(target))) {
                deleteFile(target);
                return true;
            }
        }
        return false;
    }

    protected void deleteFile(Path original) throws IOException {
        requireSafeDeleteTarget(original);
        requireDatabase();
        Files.delete(original);
    }

    protected void restoreFile(Path staged, Path original) throws IOException {
        Path temporary = prepareInstall(staged, original);
        try {
            requireDatabase();
            Files.move(temporary, original, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException | Error failure) {
            try { Files.deleteIfExists(temporary); }
            catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    private Path prepareInstall(Path staged, Path target) throws IOException {
        PlainFilePathGuard.requirePlainRegularFile(staged);
        PlainFilePathGuard.requirePlainParent(target, true);
        Path temporary = installTemporary(staged, target);
        requireDatabase();
        Files.createFile(temporary);
        try {
            Files.copy(staged, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            force(temporary);
            return temporary;
        } catch (IOException | RuntimeException | Error failure) {
            try { Files.deleteIfExists(temporary); }
            catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    private void publishNew(Path staged, Path temporary, Path target) throws IOException {
        try {
            // The private name remains linked to the exact published file until the transaction is resolved.
            Files.createLink(target, temporary);
        } catch (FileAlreadyExistsException occupied) {
            throw targetOccupied(target, occupied);
        } catch (IOException | UnsupportedOperationException unableToLink) {
            // Some removable and remote volumes support same-directory moves but not hard links.
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw targetOccupied(target, unableToLink);
            Path intent = moveIntent(staged);
            Files.writeString(intent, fileIdentity(temporary), java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            force(intent);
            requireDatabase();
            try { Files.move(temporary, target); }
            catch (FileAlreadyExistsException occupied) { throw targetOccupied(target, occupied); }
            catch (IOException moveFailed) { moveFailed.addSuppressed(unableToLink); throw moveFailed; }
        }
    }

    private static FileAlreadyExistsException targetOccupied(Path target, Exception cause) {
        var failure = new FileAlreadyExistsException(target.toString());
        failure.initCause(cause);
        return failure;
    }

    private static Path moveIntent(Path staged) {
        return staged.resolveSibling(staged.getFileName() + ".move");
    }

    private static String fileIdentity(Path file) throws IOException {
        // Numeric Unix device/inode and birth time survive a same-directory move. Other providers need a hard-link witness.
        if (!file.getFileSystem().supportedFileAttributeViews().contains("unix")) return "";
        var attributes = Files.readAttributes(file, "unix:dev,ino,creationTime", LinkOption.NOFOLLOW_LINKS);
        return attributes.get("dev") + ":" + attributes.get("ino") + ":" + attributes.get("creationTime");
    }

    private static Path installTemporary(Path staged, Path target) {
        return target.resolveSibling(".work-file-" + staged.getParent().getFileName()
                + "-" + staged.getFileName() + ".part");
    }

    /** 清理同卷发布中断留下的本操作临时文件；备份仍在独立数据目录。 */
    private void cleanInstallTemporaries(List<FileOperationJournal.Entry> entries, Path directory) throws IOException {
        for (var entry : entries) {
            for (String suffix : List.of(".old", ".new")) {
                cleanTemporary(installTemporary(directory.resolve(entry.index() + suffix), entry.target()));
            }
        }
    }

    private void cleanTemporary(Path file) throws IOException {
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return;
        PlainFilePathGuard.requirePlainRegularFile(file);
        requireDatabase();
        Files.delete(file);
    }

    private void durableCopy(Path source, Path target) throws IOException {
        PlainFilePathGuard.requirePlainRegularFile(source);
        requireDatabase();
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
        force(target);
    }

    private static void force(Path file) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    private static void requireContent(Path file, String expected) throws IOException {
        if (!Objects.equals(currentDigest(file), expected)) throw new IOException("File operation source changed: " + file);
    }

    private static void requireVerifiedContent(Path file, String expected, String actual) throws IOException {
        if (Objects.equals(expected, actual)) return;
        if (actual == null) throw new NoSuchFileException(file.toString());
        throw new FileAlreadyExistsException(file.toString(), null, "File contents changed since verification");
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

    private void cleanStaging(Path directory) throws IOException {
        requireDatabase();
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        if (!PlainFilePathGuard.isPlainDirectory(directory)) throw new IOException("Unsafe staging directory");
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                PlainFilePathGuard.requirePlainRegularFile(file);
                requireDatabase();
                Files.delete(file);
            }
        }
        requireDatabase();
        Files.delete(directory);
    }

    private void requireDatabase() {
        if (journal.isUnavailable()) throw new DatabaseUnavailableException();
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
