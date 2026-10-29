package top.sywyar.pixivdownload.transfer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationFiles;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** 停写期间的文件恢复事务；目标由宿主解析，归档和恢复记录不能指定任意本机路径。 */
final class FileRestoreTransaction {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_JOURNAL_BYTES = 8 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024 * 1024;
    private final Path directory;
    private final Map<String, Path> targets;

    record Replacement(String target, Path content, String before) {}
    record Entry(String target, String locationDigest, boolean existed, String before, String after) {}
    enum Status { PREPARED, APPLYING, COMPLETE, ROLLED_BACK, RECOVERY_REQUIRED }
    record Journal(int version, Status status, int attempted, List<Entry> entries) {
        Journal {
            entries = List.copyOf(entries);
            if (version != 1 || status == null || entries.size() > 20_000
                    || attempted < 0 || attempted > entries.size()) throw new IllegalArgumentException("transfer.restore-invalid");
        }
    }

    FileRestoreTransaction(Path directory, Map<String, Path> targets) {
        this.directory = directory.toAbsolutePath().normalize();
        Map<String, Path> normalized = new LinkedHashMap<>();
        targets.forEach((key, value) -> normalized.put(key, value.toAbsolutePath().normalize()));
        this.targets = Map.copyOf(normalized);
    }

    void prepare(List<Replacement> replacements) throws IOException {
        if (replacements.size() > 20_000) throw new IOException("transfer.restore-limit");
        Set<Path> unique = new HashSet<>();
        for (var replacement : replacements) {
            if (!unique.add(target(replacement.target()))) throw new IOException("transfer.restore-invalid");
            PlainFilePathGuard.requirePlainRegularFile(replacement.content());
        }
        PlainFilePathGuard.requirePlainParent(directory, false);
        Files.createDirectory(directory);
        MigrationFiles.makePrivate(directory);
        List<Entry> entries = new ArrayList<>();
        long remaining = MAX_TOTAL_BYTES;
        for (var replacement : replacements) {
            Path destination = target(replacement.target());
            MigrationFiles.requirePlain(destination, false);
            boolean existed = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
            String before = replacement.before();
            if (!stateDigest(destination).equals(before)) throw new IOException("transfer.target-changed");
            int index = entries.size();
            if (existed) remaining -= copyNew(destination, backup(index), remaining);
            remaining -= copyNew(replacement.content(), staged(index), remaining);
            if (!before.equals(stateDigest(destination)) || existed && !before.equals(stateDigest(backup(index))))
                throw new IOException("transfer.target-changed");
            entries.add(new Entry(replacement.target(), locationDigest(destination), existed, before, stateDigest(staged(index))));
        }
        writeJournal(new Journal(1, Status.PREPARED, 0, entries));
    }

    /** 调用方必须已经取得单实例锁并停止所有目标文件写入者。 */
    void commit() throws IOException {
        Journal journal = readJournal();
        if (journal.status() == Status.COMPLETE) return;
        validatePreparedTargets(journal);
        try {
            for (int index = 0; index < journal.entries().size(); index++) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("transfer.cancelled");
                journal = new Journal(1, Status.APPLYING, index + 1, journal.entries());
                writeJournal(journal);
                Entry entry = journal.entries().get(index);
                Path destination = target(entry.target());
                if (!entry.before().equals(stateDigest(destination))) throw new IOException("transfer.target-changed");
                replace(staged(index), destination);
                if (!entry.after().equals(stateDigest(destination))) throw new IOException("transfer.restore-verification-failed");
            }
            writeJournal(new Journal(1, Status.COMPLETE, journal.entries().size(), journal.entries()));
        } catch (IOException | RuntimeException failed) {
            try { rollback(journal); }
            catch (IOException | RuntimeException recovery) { failed.addSuppressed(recovery); }
            throw failed;
        }
    }

    /** 数据库提交前先验证所有文件，避免已知文件冲突造成部分环境恢复。 */
    void validatePreparedTargets() throws IOException { validatePreparedTargets(readJournal()); }

    private void validatePreparedTargets(Journal journal) throws IOException {
        if (journal.status() != Status.PREPARED) throw new IOException("transfer.restore-recovery-required");
        validate(journal);
        for (var entry : journal.entries()) {
            if (!entry.before().equals(stateDigest(target(entry.target())))) throw new IOException("transfer.target-changed");
        }
    }

    /** 中断后的启动恢复不自动重放导入；只恢复本次已尝试写入的文件。 */
    Status recover() throws IOException {
        Journal journal = readJournal();
        if (journal.status() == Status.APPLYING || journal.status() == Status.RECOVERY_REQUIRED) {
            rollback(journal);
            return Status.ROLLED_BACK;
        }
        return journal.status();
    }

    private void rollback(Journal journal) throws IOException {
        validate(journal);
        IOException failure = null;
        for (int index = journal.attempted() - 1; index >= 0; index--) {
            try {
                Entry entry = journal.entries().get(index);
                Path destination = target(entry.target());
                String actual = stateDigest(destination);
                if (actual.equals(entry.before())) continue;
                if (!actual.equals(entry.after())) throw new IOException("transfer.target-changed");
                if (entry.existed()) replace(backup(index), destination);
                else {
                    PlainFilePathGuard.requirePlainRegularFile(destination);
                    Files.delete(destination);
                }
                if (!entry.before().equals(stateDigest(destination))) throw new IOException("transfer.restore-verification-failed");
            } catch (IOException problem) {
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        writeJournal(new Journal(1, failure == null ? Status.ROLLED_BACK : Status.RECOVERY_REQUIRED,
                journal.attempted(), journal.entries()));
        if (failure != null) throw failure;
    }

    private void validate(Journal journal) throws IOException {
        Set<Path> unique = new HashSet<>();
        for (int index = 0; index < journal.entries().size(); index++) {
            Entry entry = journal.entries().get(index);
            Path destination = target(entry.target());
            if (!unique.add(destination) || !locationDigest(destination).equals(entry.locationDigest())
                    || !entry.after().matches("[a-f0-9]{64}")
                    || !entry.after().equals(stateDigest(staged(index)))
                    || entry.existed() && !entry.before().equals(stateDigest(backup(index)))
                    || !entry.existed() && !entry.before().equals("ABSENT"))
                throw new IOException("transfer.restore-invalid");
        }
    }

    private Path target(String id) throws IOException {
        Path target = targets.get(id);
        if (target == null) throw new IOException("transfer.restore-unknown-target");
        return target;
    }
    private Path backup(int index) { return directory.resolve(index + ".before"); }
    private Path staged(int index) { return directory.resolve(index + ".after"); }

    private Journal readJournal() throws IOException {
        Path file = directory.resolve("journal.json");
        PlainFilePathGuard.requirePlainRegularFile(file);
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_JOURNAL_BYTES + 1);
            if (bytes.length > MAX_JOURNAL_BYTES) throw new IOException("transfer.restore-limit");
            try {
                Journal result = JSON.readValue(bytes, Journal.class);
                if (result == null) throw new IOException("transfer.restore-invalid");
                return result;
            } catch (RuntimeException invalid) { throw new IOException("transfer.restore-invalid", invalid); }
        }
    }

    private void writeJournal(Journal journal) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(journal);
        if (bytes.length > MAX_JOURNAL_BYTES) throw new IOException("transfer.restore-limit");
        Path destination = directory.resolve("journal.json");
        PlainFilePathGuard.requirePlainParent(destination, false);
        Path temporary = Files.createTempFile(directory, ".journal-", ".part");
        try {
            MigrationFiles.makePrivate(temporary);
            Files.write(temporary, bytes, LinkOption.NOFOLLOW_LINKS);
            force(temporary);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static long copyNew(Path source, Path destination, long maximum) throws IOException {
        PlainFilePathGuard.requirePlainRegularFile(source);
        PlainFilePathGuard.requirePlainParent(destination, false);
        long size = 0;
        try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
             var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            MigrationFiles.makePrivate(destination);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > maximum - size) throw new IOException("transfer.restore-limit");
                size += read;
                output.write(buffer, 0, read);
            }
        }
        force(destination);
        return size;
    }

    private static void replace(Path source, Path destination) throws IOException {
        PlainFilePathGuard.requirePlainParent(destination, true);
        MigrationFiles.requirePlain(destination, false);
        Path temporary = destination.resolveSibling(".restore-" + UUID.randomUUID() + ".part");
        try {
            copyNew(source, temporary, MAX_TOTAL_BYTES);
            MigrationFiles.requirePlain(destination, false);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    static String stateDigest(Path path) throws IOException {
        MigrationFiles.requirePlain(path, false);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return "ABSENT";
        PlainFilePathGuard.requirePlainRegularFile(path);
        var digest = top.sywyar.pixivdownload.plugin.api.database.migration.MigrationValues.newDigest();
        try (var input = new java.security.DigestInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS), digest)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            for (int read; (read = input.read(buffer)) != -1;) {
                total += read;
                if (total > MAX_TOTAL_BYTES) throw new IOException("transfer.restore-limit");
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String locationDigest(Path path) {
        var digest = top.sywyar.pixivdownload.plugin.api.database.migration.MigrationValues.newDigest();
        return HexFormat.of().formatHex(digest.digest(path.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static void force(Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
    }
}
