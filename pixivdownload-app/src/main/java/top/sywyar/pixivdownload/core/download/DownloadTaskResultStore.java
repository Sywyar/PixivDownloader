package top.sywyar.pixivdownload.core.download;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadEvent;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTaskSnapshot;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTaskProgress;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 终态按执行身份保存；历史结果不重放到活动快照。 */
@Component
public final class DownloadTaskResultStore {
    // Includes one full-length filesystem path, even when JSON escapes each UTF-16 code unit.
    static final int MAX_RECORD_BYTES = 256 * 1024;
    private final ObjectMapper mapper = JsonMapper.builderWithJackson2Defaults().build();
    private final Path directory;

    public DownloadTaskResultStore() { this(RuntimeFiles.downloadTaskResultsDirectory()); }
    DownloadTaskResultStore(Path directory) { this.directory = directory; }

    private record Result(int version, DownloadAttempt attempt, String owner, String queueType,
                          String title, DownloadEvent.Phase phase, String updatedAt,
                          DownloadEvent.FailureReason failureReason, String outputPath, DownloadTaskProgress progress) {}

    void save(DownloadTaskSnapshot task, String owner) throws IOException {
        if (!task.terminal()) throw new IllegalArgumentException("download result is not terminal");
        byte[] bytes = mapper.writeValueAsBytes(new Result(1, task.attempt(), owner, task.queueType(),
                task.title(), task.phase(), task.updatedAt().toString(), task.failureReason(), task.outputPath(), task.progress()));
        if (bytes.length > MAX_RECORD_BYTES) throw new IOException("download result too large");
        Path target = path(task.attempt().attemptId());
        Files.createDirectories(target.getParent());
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
    }

    Optional<DownloadTaskSnapshot> find(UUID id, RequestOwnerIdentity owner) {
        try (var input = Files.newInputStream(path(id))) {
            byte[] bytes = input.readNBytes(MAX_RECORD_BYTES + 1);
            if (bytes.length > MAX_RECORD_BYTES) throw new IOException("download result too large");
            Result result = mapper.readValue(bytes, Result.class);
            if (result == null || result.version() != 1 || result.attempt() == null
                    || !id.equals(result.attempt().attemptId()) || result.queueType() == null
                    || (!result.queueType().isEmpty() && !result.queueType().matches("[a-z][a-z0-9-]{0,63}"))
                    || result.title() == null || result.title().length() > 512 || result.updatedAt() == null)
                throw new IOException("invalid download result");
            DownloadTaskSnapshot task = new DownloadTaskSnapshot(result.attempt(), result.queueType(),
                    result.title(), result.phase(), Instant.parse(result.updatedAt()), 0, result.failureReason(), result.outputPath(), result.progress());
            if (!task.terminal()) throw new IOException("download result is not terminal");
            return owner.admin() || Objects.equals(owner.ownerUuid(), result.owner())
                    ? Optional.of(task) : Optional.empty();
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (JacksonException | IllegalArgumentException | java.time.DateTimeException invalid) {
            throw new UncheckedIOException(new IOException("invalid download result", invalid));
        }
    }

    private Path path(UUID id) {
        String key = Objects.requireNonNull(id, "taskId").toString();
        return directory.resolve(key.substring(0, 2)).resolve(key + ".json");
    }

    top.sywyar.pixivdownload.plugin.api.download.task.DownloadTasks.HistoryPage history(
            top.sywyar.pixivdownload.plugin.api.download.task.DownloadTasks.HistoryCursor before,
            int limit, RequestOwnerIdentity owner) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("invalid history page size");
        Objects.requireNonNull(owner);
        var order = java.util.Comparator.comparing(DownloadTaskSnapshot::updatedAt)
                .thenComparing(task -> task.attempt().attemptId().toString());
        var selected = new java.util.PriorityQueue<DownloadTaskSnapshot>(limit + 1, order);
        try {
            for (int shard = 0; shard < 256; shard++) {
                Path folder = directory.resolve(String.format(java.util.Locale.ROOT, "%02x", shard));
                if (Files.notExists(folder, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                var guard = top.sywyar.pixivdownload.common.PlainFilePathGuard.requireDirectoryOpenPrecondition(folder);
                if (!guard.ready()) throw new IOException("unsafe download history directory");
                try (var files = Files.newDirectoryStream(folder, "*.json")) {
                    for (Path file : files) {
                        String name = file.getFileName().toString();
                        if (!name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json")) continue;
                        UUID id = UUID.fromString(name.substring(0, 36));
                        if (!file.equals(path(id))) continue;
                        top.sywyar.pixivdownload.common.PlainFilePathGuard.requirePlainRegularFile(file);
                        var found = find(id, owner);
                        if (found.isEmpty()) continue;
                        var task = found.get();
                        if (before != null && (task.updatedAt().isAfter(before.updatedAt())
                                || task.updatedAt().equals(before.updatedAt()) && id.toString().compareTo(before.taskId().toString()) >= 0)) continue;
                        selected.add(task);
                        if (selected.size() > limit + 1) selected.poll();
                    }
                }
            }
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
        var page = new java.util.ArrayList<>(selected);
        page.sort(order.reversed());
        boolean more = page.size() > limit;
        if (more) page.removeLast();
        var last = page.isEmpty() ? null : page.getLast();
        return new top.sywyar.pixivdownload.plugin.api.download.task.DownloadTasks.HistoryPage(page,
                more ? new top.sywyar.pixivdownload.plugin.api.download.task.DownloadTasks.HistoryCursor(last.updatedAt(), last.attempt().attemptId()) : null);
    }
}
