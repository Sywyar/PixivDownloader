package top.sywyar.pixivdownload.core.download;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadEvent;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTaskSnapshot;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTaskProgress;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;

import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTasks.HistoryCursor;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTasks.HistoryPage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreException;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.type.StringDataType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 终态按执行身份保存；历史结果不重放到活动快照。 */
@Component
public final class DownloadTaskResultStore implements AutoCloseable {
    // Includes one full-length filesystem path, even when JSON escapes each UTF-16 code unit.
    static final int MAX_RECORD_BYTES = 256 * 1024;
    private static final Duration RECEIPT_RETRY_DELAY = Duration.ofMinutes(5);
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DownloadTaskResultStore.class);
    private final ObjectMapper mapper = JsonMapper.builderWithJackson2Defaults().build();
    // MVStore 的文件锁覆盖整个句柄生命周期；同一进程的两个存储对象也不能重复打开。
    private static final java.util.Map<Path, SharedIndex> OPEN_INDEXES = new java.util.HashMap<>();
    private static final class SharedIndex {
        // 历史读取者串行补登；发布者只持有句柄锁更新有序树。
        final Object historyLock = new Object();
        MVStore store;
        long failedPublications;
        int users;
        boolean reconciled;
        final java.util.Map<Path, Instant> receiptRetries = new java.util.LinkedHashMap<>();
        java.util.Set<Path> failedShards = java.util.Set.of();
    }

    private SharedIndex shared;
    private volatile boolean closed;
    private final Path directory;
    private final Clock clock;

    public DownloadTaskResultStore() { this(RuntimeFiles.downloadTaskResultsDirectory()); }
    DownloadTaskResultStore(Path directory) { this(directory, Clock.systemUTC()); }
    DownloadTaskResultStore(Path directory, Clock clock) {
        this.directory = directory.toAbsolutePath().normalize();
        this.clock = clock;
    }

    private record Result(int version, DownloadAttempt attempt,
                          @com.fasterxml.jackson.annotation.JsonProperty(required = true) String owner, String queueType,
                          String title, DownloadEvent.Phase phase, String updatedAt,
                          DownloadEvent.FailureReason failureReason, String outputPath, DownloadTaskProgress progress) {}

    void save(DownloadTaskSnapshot task, String owner) throws IOException {
        if (!task.terminal()) throw new IllegalArgumentException("download result is not terminal");
        byte[] bytes = mapper.writeValueAsBytes(new Result(1, task.attempt(), owner, task.queueType(),
                task.title(), task.phase(), task.updatedAt().toString(), task.failureReason(), task.outputPath(), task.progress()));
        if (bytes.length > MAX_RECORD_BYTES) throw new IOException("download result too large");
        requireOpen();
        Path target = path(task.attempt().attemptId());

        ensureParent(target);
        Path temporary = Files.createTempFile(target.getParent(), ".result-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        // 回执已耐久发布；索引故障只影响历史查询，重新打开后按 UUID 补登。
        try {
            var handle = sharedIndex();
            synchronized (handle) {
                var index = acquireIndex(handle, false);
                indexReceipt(index, task.updatedAt(), task.attempt().attemptId(), owner);
                index.commit();
                index.sync();
            }
        } catch (IOException | RuntimeException failure) {
            // 写 JSON 期间最后一个旧句柄可能已关闭；标记此刻正在服务查询的索引。
            SharedIndex active;
            synchronized (OPEN_INDEXES) { active = OPEN_INDEXES.get(indexPath()); }
            if (active != null) {
                synchronized (active) {
                    active.failedPublications++;
                    active.reconciled = false;
                }
            }
            LOG.warn("Download receipt saved with pending index attemptId={}", task.attempt().attemptId(), failure);
        }
    }

    public static final class ReceiptUnavailable extends UncheckedIOException {
        ReceiptUnavailable(String message, IOException cause) { super(message, cause); }
    }

    Optional<DownloadTaskSnapshot> find(UUID id, RequestOwnerIdentity owner) {
        try {
            Result result = read(id);
            var task = snapshot(id, result);
            if (!owner.admin() && !Objects.equals(owner.ownerUuid(), result.owner())) return Optional.empty();
            return Optional.of(task);
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        } catch (IOException failure) {
            if (!owner.admin() && !indexedOwner(id, owner.ownerUuid())) {
                skipped(id, failure);
                return Optional.empty();
            }
            LOG.warn("Cannot read download receipt attemptId={} failureType={}", id, failure.getClass().getSimpleName());
            throw new ReceiptUnavailable("Cannot read download receipt " + id, failure);
        }
    }

    InputStream openReceipt(UUID id) throws IOException {
        Path file = path(id);
        var parent = PlainFilePathGuard.requireDirectoryOpenPrecondition(file.getParent());
        if (parent.rejected()) throw new IOException("Unsafe download receipt directory");
        if (!parent.ready()) throw new NoSuchFileException(file.toString());
        var attrs = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) throw new IOException("Unsafe download receipt");
        return Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
    }

    private Result read(UUID id) throws IOException {
        try (var input = openReceipt(id)) {
            byte[] bytes = input.readNBytes(MAX_RECORD_BYTES + 1);
            if (bytes.length > MAX_RECORD_BYTES) throw new IOException("oversized download receipt");
            Result result = mapper.readValue(bytes, Result.class);
            if (result == null) throw new IOException("empty download receipt");
            return result;
        } catch (JacksonException invalid) {
            throw new IOException("invalid download receipt", invalid);
        }
    }

    private static DownloadTaskSnapshot snapshot(UUID id, Result result) throws IOException {
        if (result.version() != 1 || result.attempt() == null || !id.equals(result.attempt().attemptId())
                || result.queueType() == null || (!result.queueType().isEmpty() && !result.queueType().matches("[a-z][a-z0-9-]{0,63}"))
                || result.title() == null || result.title().length() > 512 || result.updatedAt() == null || result.phase() == null)
            throw new IOException("invalid download result");
        try {
            var task = new DownloadTaskSnapshot(result.attempt(), result.queueType(), result.title(), result.phase(),
                    Instant.parse(result.updatedAt()), 0, result.failureReason(), result.outputPath(), result.progress());
            if (!task.terminal()) throw new IOException("download result is not terminal");
            return task;
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            throw new IOException("invalid download result", invalid);
        }
    }

    private Path path(UUID id) {
        String key = Objects.requireNonNull(id, "taskId").toString();
        return directory.resolve(key.substring(0, 2)).resolve(key + ".json");
    }

    HistoryPage history(HistoryCursor before, int limit, RequestOwnerIdentity owner) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("invalid history page size");
        String scope = (owner.admin() ? "all" : ownerKey(owner.ownerUuid())) + "/";
        var page = new ArrayList<DownloadTaskSnapshot>(limit);
        String boundary = before == null ? null : scope + entryName(before.updatedAt(), before.taskId());
        try {
            for (;;) {
                int count = limit + 1 - page.size();
                List<String> keys = indexPage(scope, boundary, count);
                for (String key : keys) {
                    UUID id = UUID.fromString(key.substring(key.length() - 36));
                    try {
                        Result result = read(id);
                        if (!owner.admin() && !Objects.equals(result.owner(), owner.ownerUuid())) continue;
                        var task = snapshot(id, result);
                        if (!key.equals(scope + entryName(task.updatedAt(), id))) continue;
                        if (page.size() == limit) {
                            var last = page.getLast();
                            return new HistoryPage(page, new HistoryCursor(last.updatedAt(), last.attempt().attemptId()));
                        }
                        page.add(task);
                    } catch (NoSuchFileException missing) {
                        // 旧索引可能仍引用缺失的回执。
                    } catch (IOException invalid) { skipped(id, invalid); }
                }
                if (keys.size() < count) return new HistoryPage(page, null);
                boundary = keys.getLast();
            }
        } catch (MVStoreException | IOException failure) {
            throw new ReceiptUnavailable("Cannot read download history index", new IOException(failure));
        }
    }

    // 上下界均包含 owner 前缀，直接在树中定位游标。
    List<String> indexPage(String scope, String before, int count) throws IOException {
        var handle = sharedIndex();
        synchronized (handle.historyLock) {
            try {
                return readPage(handle, scope, before, count);
            } catch (MVStoreException failure) {
                int code = failure.getErrorCode();
                boolean truncated = code == DataUtils.ERROR_READING_FAILED && failure.getCause() instanceof java.io.EOFException;
                if (!truncated && code != DataUtils.ERROR_FILE_CORRUPT && code != DataUtils.ERROR_CHUNK_NOT_FOUND
                        && code != DataUtils.ERROR_BLOCK_NOT_FOUND) throw failure;
                synchronized (handle) {
                    requireOpen();
                    // 只替换已确认损坏的派生文件；权限或文件锁故障保留原文件。
                    handle.reconciled = false;
                    if (handle.store != null) handle.store.closeImmediately();
                    Path path = indexPath();
                    PlainFilePathGuard.requirePlainRegularFile(path);
                    Path damaged = path.resolveSibling("history.mv.corrupt-" + UUID.randomUUID());
                    Files.move(path, damaged);
                    LOG.warn("Rebuilding damaged download history index backup={}", damaged.getFileName(), failure);
                }
                return readPage(handle, scope, before, count);
            }
        }
    }

    private List<String> readPage(SharedIndex handle, String scope, String before, int count) throws IOException {
        reconcileReceipts(handle, before == null);
        synchronized (handle) {
            var ordering = map(acquireIndex(handle, true), "time");
            var cursor = ordering.cursor(before == null ? scope + "~" : before, scope, true);
            return readIndexKeys(cursor, before, count);
        }
    }

    List<String> readIndexKeys(java.util.Iterator<String> cursor, String before, int count) {
        var keys = new ArrayList<String>(count);
        while (keys.size() < count && cursor.hasNext()) {
            String key = cursor.next();
            if (!key.equals(before)) keys.add(key);
        }
        return keys;
    }

    private Path indexPath() { return directory.resolve("history.mv"); }

    private void requireOpen() throws IOException {
        if (closed) throw new IOException("Download history store is closed");
    }

    private synchronized SharedIndex sharedIndex() throws IOException {
        requireOpen();
        if (shared == null) {
            synchronized (OPEN_INDEXES) {
                shared = OPEN_INDEXES.computeIfAbsent(indexPath(), ignored -> new SharedIndex());
                shared.users++;
            }
        }
        return shared;
    }

    // 只在对应的句柄锁内打开、升级和使用 MVStore，目录枚举与回执读取可在锁外进行。
    private MVStore acquireIndex(SharedIndex handle, boolean readOnly) throws IOException {
        requireOpen();
        if (handle.store != null && !readOnly && handle.store.isReadOnly()) {
            handle.store.close();
            handle.store = null;
        }
        if (handle.store == null || handle.store.isClosed()) {
            handle.store = openIndex(readOnly);
            handle.reconciled = false;
        }
        return handle.store;
    }

    @Override
    @jakarta.annotation.PreDestroy
    public synchronized void close() {
        closed = true;
        if (shared == null) return;
        synchronized (OPEN_INDEXES) {
            if (--shared.users == 0) {
                OPEN_INDEXES.remove(indexPath());
                synchronized (shared) {
                    if (shared.store != null) shared.store.close();
                }
            }
            shared = null;
        }
    }

    MVStore openIndex(boolean readOnly) throws IOException {
        Path path = indexPath();
        if (readOnly) PlainFilePathGuard.requirePlainRegularFile(path);
        else {
            ensureParent(path);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) PlainFilePathGuard.requirePlainRegularFile(path);
        }
        var builder = new MVStore.Builder().fileName(path.toString()).autoCommitDisabled();
        try {
            return (readOnly ? builder.readOnly() : builder).open();
        } catch (java.nio.channels.NonWritableChannelException empty) {
            // MVStore 尝试给零字节文件写入新文件头，即使以只读方式打开。
            if (!readOnly || Files.size(path) != 0) throw empty;
            throw DataUtils.newMVStoreException(DataUtils.ERROR_FILE_CORRUPT, "Empty download history index", empty);
        }
    }

    private static MVMap<String, String> map(MVStore index, String name) {
        return index.openMap(name, new MVMap.Builder<String, String>()
                .keyType(StringDataType.INSTANCE).valueType(StringDataType.INSTANCE));
    }

    private boolean indexedOwner(UUID id, String owner) {
        try {
            if (Files.notExists(indexPath(), LinkOption.NOFOLLOW_LINKS)) return false;
            var handle = sharedIndex();
            synchronized (handle) {
                return ownerKey(owner).equals(map(acquireIndex(handle, true), "owners").get(id.toString()));
            }
        } catch (IOException | MVStoreException failure) {
            skipped(id, new IOException("Cannot verify download receipt owner", failure));
            return false;
        }
    }

    private static String entryName(Instant time, UUID id) {
        return java.util.HexFormat.of().toHexDigits(time.getEpochSecond() ^ Long.MIN_VALUE)
                + java.util.HexFormat.of().toHexDigits(time.getNano()) + id;
    }

    private static String ownerKey(String owner) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(owner.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static void indexReceipt(MVStore index, Instant time, UUID id, String owner) {
        String key = entryName(time, id);
        var ordering = map(index, "time");
        ordering.put("all/" + key, "");
        String scope = owner == null ? "all" : ownerKey(owner);
        if (owner != null) ordering.put(scope + "/" + key, "");
        map(index, "owners").put(id.toString(), scope);
    }

    private static void ensureParent(Path path) throws IOException {
        var precondition = PlainFilePathGuard.requireDirectoryOpenPrecondition(path.getParent());
        if (precondition.rejected()) throw new IOException("unsafe download history directory");
        if (!precondition.ready()) Files.createDirectories(path.getParent());
        PlainFilePathGuard.requirePlainParent(path, false);
    }

    private void reconcileReceipts(SharedIndex handle, boolean firstPage) throws IOException {
        long failedAtStart;
        boolean fullScan;
        Path retry = null;
        synchronized (handle) {
            var index = acquireIndex(handle, handle.reconciled || Files.exists(indexPath(), LinkOption.NOFOLLOW_LINKS));
            fullScan = !handle.reconciled;
            if (!fullScan) {
                if (!firstPage) return;
                var now = clock.instant();
                for (var entry : handle.receiptRetries.entrySet()) {
                    if (!now.isBefore(entry.getValue())) {
                        retry = entry.getKey();
                        break;
                    }
                }
                if (retry == null && handle.failedShards.isEmpty()) return;
            }
            if (!index.hasMap("owners") || !index.hasMap("time")) {
                index = acquireIndex(handle, false);
                map(index, "owners");
                map(index, "time");
            }
            failedAtStart = handle.failedPublications;
        }

        var scan = new ReceiptScan(handle);
        PlainFilePathGuard.requirePlainParent(directory.resolve(".check"), false);
        if (fullScan) {
            try (var shards = Files.newDirectoryStream(directory, "[0-9a-f][0-9a-f]")) {
                for (Path shard : shards) scan.shard(shard);
            } catch (java.nio.file.DirectoryIteratorException failure) {
                throw failure.getCause();
            }
        } else {
            for (Path shard : handle.failedShards) scan.shard(shard);
            if (retry != null && !handle.failedShards.contains(retry)) scan.shard(retry);
        }
        synchronized (handle) {
            var current = acquireIndex(handle, true);
            if (!current.isReadOnly()) {
                current.commit();
                current.sync();
            }
            // 并发发布若只写成了 JSON，本轮枚举可能已经过了它的分片。
            handle.reconciled = failedAtStart == handle.failedPublications;
            if (fullScan) handle.receiptRetries.clear();
            else handle.receiptRetries.remove(retry);
            for (Path shard : scan.unreadableShards) {
                // 首次失败留给下一次首页；持续损坏的分片排到队尾并进入冷却。
                handle.receiptRetries.put(shard, shard.equals(retry) ? clock.instant().plus(RECEIPT_RETRY_DELAY) : Instant.MIN);
            }
            handle.failedShards = scan.failedShards;
        }
    }

    // 每次核对只保留失败分片名（至多 256 个）；单条回执的读取不占用发布者的句柄锁。
    private final class ReceiptScan {
        final SharedIndex handle;
        final java.util.Set<Path> failedShards = new java.util.LinkedHashSet<>();
        final java.util.Set<Path> unreadableShards = new java.util.LinkedHashSet<>();
        int added;

        ReceiptScan(SharedIndex handle) { this.handle = handle; }

        void shard(Path shard) throws IOException {
            java.util.stream.Stream<Path> files;
            try {
                var precondition = PlainFilePathGuard.requireDirectoryOpenPrecondition(shard);
                if (precondition.rejected()) {
                    failedShard(shard, precondition.rejectedNode().kind().name());
                    return;
                }
                if (!precondition.ready()) return;
                files = Files.list(shard);
            } catch (IOException failure) {
                failedShard(shard, failure.getClass().getSimpleName());
                return;
            }
            try (files) {
                var entries = files.iterator();
                while (entries.hasNext()) addMissing(entries.next());
            } catch (UncheckedIOException failure) {
                failedShard(shard, failure.getCause().getClass().getSimpleName());
            }
        }

        private void failedShard(Path shard, String reason) {
            failedShards.add(shard);
            if (!handle.failedShards.contains(shard))
                LOG.warn("Skipping unreadable download receipt shard={} reason={}", shard.getFileName(), reason);
        }

        private void addMissing(Path file) throws IOException {
            String name = file.getFileName().toString();
            if (name.length() != 41 || !name.endsWith(".json")) return;
            UUID id;
            try { id = UUID.fromString(name.substring(0, 36)); }
            catch (IllegalArgumentException invalid) { return; }
            if (!file.equals(path(id))) return;
            synchronized (handle) {
                if (map(acquireIndex(handle, true), "owners").containsKey(id.toString())) return;
            }
            Result receipt;
            DownloadTaskSnapshot task;
            try { receipt = read(id); task = snapshot(id, receipt); }
            catch (IOException invalid) {
                skipped(id, invalid);
                unreadableShards.add(file.getParent());
                return;
            }
            synchronized (handle) {
                // 读取期间发布者可能已登记同一 UUID，且只读句柄可能已升级。
                var current = acquireIndex(handle, false);
                if (map(current, "owners").containsKey(id.toString())) return;
                indexReceipt(current, task.updatedAt(), id, receipt.owner());
                if (++added % 1024 == 0) current.commit();
            }
        }
    }

    private static void skipped(UUID id, IOException failure) {
        LOG.warn("Skipping unreadable download receipt attemptId={} failureType={}", id, failure.getClass().getSimpleName());
    }
}
