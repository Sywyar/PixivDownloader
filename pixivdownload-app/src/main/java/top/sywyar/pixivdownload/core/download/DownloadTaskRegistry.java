package top.sywyar.pixivdownload.core.download;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadEvent;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmission;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadTaskException;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTaskSnapshot;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTasks;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 进程内任务事实与幂等窗口；只持有有界纯值和未结束任务的精确取消句柄。 */
@Component
public final class DownloadTaskRegistry {
    static final int MAX_TASKS = 4096;
    static final Duration RETENTION = Duration.ofMinutes(5);
    private final UUID epoch = UUID.randomUUID();
    private final Clock clock;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Map<RequestKey, UUID> requests = new LinkedHashMap<>();
    private long revision;

    public DownloadTaskRegistry() { this(Clock.systemUTC()); }
    DownloadTaskRegistry(Clock clock) { this.clock = clock; }

    private record RequestKey(String owner, UUID requestId) {}
    record Reservation(DownloadTaskSnapshot task, boolean duplicate) {}
    private static final class Entry {
        DownloadTaskSnapshot value;
        final String owner;
        final RequestKey request;
        final byte[] fingerprint;
        QueueTaskTracker.Task task;
        DownloadEvent.Phase publishedPhase;
        Entry(DownloadTaskSnapshot value, String owner, RequestKey request, byte[] fingerprint) {
            this.value = value;
            this.owner = owner;
            this.request = request;
            this.fingerprint = fingerprint;
        }
    }

    synchronized Reservation reserve(DownloadSubmission command, String credential, RequestOwnerIdentity owner) {
        Objects.requireNonNull(owner, "owner");
        if (!owner.admin()) throw new DownloadTaskException(DownloadTaskException.Code.FORBIDDEN);
        expire();
        RequestKey key = new RequestKey(owner.ownerUuid(), command.requestId());
        byte[] fingerprint = fingerprint(command, credential);
        UUID existing = requests.get(key);
        if (existing != null) {
            Entry entry = entries.get(existing);
            if (!MessageDigest.isEqual(fingerprint, entry.fingerprint))
                throw new DownloadTaskException(DownloadTaskException.Code.CONFLICT);
            return new Reservation(entry.value, true);
        }
        requireCapacity();
        DownloadAttempt attempt = new DownloadAttempt(UUID.randomUUID(), command.workType(), command.workId());
        Entry entry = new Entry(value(attempt, "", attempt.workId(), DownloadEvent.Phase.ACCEPTED),
                owner.ownerUuid(), key, fingerprint);
        entries.put(attempt.attemptId(), entry);
        requests.put(key, attempt.attemptId());
        return new Reservation(entry.value, false);
    }

    public synchronized void register(DownloadAttempt attempt, String owner, String queueType, String title) {
        Objects.requireNonNull(attempt, "attempt");
        String safeType = queueType == null ? "" : queueType;
        if (!safeType.isEmpty() && !safeType.matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("invalid queue type");
        String safeTitle = title == null || title.isBlank() ? attempt.workId() : title;
        if (safeTitle.length() > 512) safeTitle = safeTitle.substring(0, 512);
        expire();
        Entry entry = entries.get(attempt.attemptId());
        if (entry != null) {
            if (!entry.value.attempt().equals(attempt) || !Objects.equals(entry.owner, owner)
                    || entry.task != null || entry.value.terminal())
                throw new IllegalArgumentException("download task identity already registered");
            entry.value = value(attempt, safeType, safeTitle, entry.value.phase());
        } else {
            requireCapacity();
            entries.put(attempt.attemptId(), new Entry(
                    value(attempt, safeType, safeTitle, DownloadEvent.Phase.ACCEPTED), owner, null, null));
        }
    }

    public synchronized void attach(DownloadAttempt attempt, QueueTaskTracker.Task task) {
        Entry entry = entries.get(attempt.attemptId());
        if (entry == null || !entry.value.attempt().equals(attempt) || entry.task != null)
            throw new IllegalStateException("download task is not available");
        entry.task = Objects.requireNonNull(task, "task");
    }

    /** 返回 false 时事件已经过期或与终态冲突，不能再次通知观察者。 */
    public synchronized boolean update(DownloadEvent event) {
        Entry entry = entries.get(event.attempt().attemptId());
        if (entry == null) return true; // 兼容只使用在线观察的执行发布者。
        if (!entry.value.attempt().equals(event.attempt())) throw new IllegalArgumentException("download identity mismatch");
        if (entry.value.terminal()) return false;
        DownloadEvent.Phase before = entry.value.phase();
        if (entry.publishedPhase == event.phase()) return false;
        if (event.phase() == DownloadEvent.Phase.ACCEPTED && before != DownloadEvent.Phase.ACCEPTED) return false;
        if (event.phase() == DownloadEvent.Phase.QUEUED && before != DownloadEvent.Phase.ACCEPTED) return false;
        if (event.phase() == DownloadEvent.Phase.STARTED && before == DownloadEvent.Phase.STARTED) return false;
        entry.value = value(event.attempt(), entry.value.queueType(), entry.value.title(), event.phase());
        entry.publishedPhase = event.phase();
        if (entry.value.terminal()) entry.task = null;
        return true;
    }

    public synchronized Optional<DownloadTaskSnapshot> find(UUID taskId, RequestOwnerIdentity owner) {
        Objects.requireNonNull(owner, "owner");
        expire();
        Entry entry = entries.get(taskId);
        return entry != null && visible(entry, owner) ? Optional.of(entry.value) : Optional.empty();
    }

    public synchronized DownloadTasks.Snapshot snapshot(RequestOwnerIdentity owner) {
        Objects.requireNonNull(owner, "owner");
        expire();
        return new DownloadTasks.Snapshot(epoch, revision,
                entries.values().stream().filter(e -> visible(e, owner)).map(e -> e.value).toList());
    }

    public DownloadTasks.CancelResult cancel(UUID taskId, RequestOwnerIdentity owner) {
        Objects.requireNonNull(owner, "owner");
        QueueTaskTracker.Task task;
        synchronized (this) {
            expire();
            Entry entry = entries.get(taskId);
            if (entry == null || !visible(entry, owner)) return DownloadTasks.CancelResult.NOT_FOUND;
            if (entry.value.terminal()) return DownloadTasks.CancelResult.TERMINAL;
            task = entry.task;
            if (task == null) return DownloadTasks.CancelResult.NOT_CANCELLABLE;
        }
        // 捕获旧任务包装器；即使同作品再次下载，也不会取消 replacement。
        task.cancel();
        return DownloadTasks.CancelResult.REQUESTED;
    }

    private static boolean visible(Entry entry, RequestOwnerIdentity owner) {
        return owner.admin() || Objects.equals(owner.ownerUuid(), entry.owner);
    }

    private DownloadTaskSnapshot value(DownloadAttempt attempt, String queueType, String title, DownloadEvent.Phase phase) {
        return new DownloadTaskSnapshot(attempt, queueType, title, phase, clock.instant(), ++revision);
    }

    private void requireCapacity() {
        if (entries.size() >= MAX_TASKS)
            throw new DownloadTaskException(DownloadTaskException.Code.CAPACITY_EXCEEDED);
    }

    private void expire() {
        Instant cutoff = clock.instant().minus(RETENTION);
        entries.values().removeIf(entry -> {
            if (!entry.value.terminal() || !entry.value.updatedAt().isBefore(cutoff)) return false;
            if (entry.request != null) requests.remove(entry.request);
            revision++;
            return true;
        });
    }

    private static byte[] fingerprint(DownloadSubmission command, String credential) {
        if (credential != null && credential.length() > top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver.MAX_LENGTH)
            throw new IllegalArgumentException("download credential too large");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            hash(digest, command.workType());
            hash(digest, command.workId());
            command.options().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                hash(digest, entry.getKey());
                hash(digest, entry.getValue());
            });
            hash(digest, credential == null ? "" : credential);
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void hash(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
        Arrays.fill(bytes, (byte) 0);
    }
}
