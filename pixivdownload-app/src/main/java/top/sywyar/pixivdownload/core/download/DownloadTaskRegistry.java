package top.sywyar.pixivdownload.core.download;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadEvent;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmission;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadTaskException;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTaskSnapshot;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTasks;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 进程内任务事实与幂等窗口；终态另存以供关闭页面后的精确核对。 */
@Component
public final class DownloadTaskRegistry {
    static final int MAX_TASKS = 4096;
    static final Duration RETENTION = Duration.ofMinutes(5);
    private final UUID epoch = UUID.randomUUID();
    private final Clock clock;
    private final DownloadTaskResultStore results;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Map<RequestKey, UUID> requests = new LinkedHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> registrations = new LinkedHashMap<>();
    private long revision;

    public DownloadTaskRegistry() { this(Clock.systemUTC()); }
    DownloadTaskRegistry(Clock clock) { this(clock, null); }
    @Autowired
    public DownloadTaskRegistry(DownloadTaskResultStore results) { this(Clock.systemUTC(), Objects.requireNonNull(results)); }
    DownloadTaskRegistry(Clock clock, DownloadTaskResultStore results) {
        this.clock = clock;
        this.results = results;
    }

    private record RequestKey(String owner, UUID requestId) {}
    record Reservation(DownloadTaskSnapshot task, boolean duplicate) {}
    private static final class Entry {
        DownloadTaskSnapshot value;
        final String owner;
        final RequestKey request;
        final byte[] fingerprint;
        QueueTaskTracker.Task task;
        DownloadEvent.Phase publishedPhase;
        boolean saved;
        boolean saving;
        boolean saveFailureLogged;
        Instant retrySaveAt = Instant.MIN;
        Entry(DownloadTaskSnapshot value, String owner, RequestKey request, byte[] fingerprint) {
            this.value = value;
            this.owner = owner;
            this.request = request;
            this.fingerprint = fingerprint;
        }
    }

    Reservation reserve(DownloadSubmission command, String credential, RequestOwnerIdentity owner) {
        Objects.requireNonNull(owner, "owner");
        if (!owner.admin()) throw new DownloadTaskException(DownloadTaskException.Code.FORBIDDEN);
        expire();
        RequestKey key = new RequestKey(owner.ownerUuid(), command.requestId());
        byte[] fingerprint = fingerprint(command, credential);
        synchronized (this) {
            UUID existing = requests.get(key);
            if (existing != null) {
                Entry entry = entries.get(existing);
                if (!MessageDigest.isEqual(fingerprint, entry.fingerprint))
                    throw new DownloadTaskException(DownloadTaskException.Code.CONFLICT);
                return new Reservation(entry.value, true);
            }
            requireCapacity();
            UUID id;
            do { id = UUID.randomUUID(); } while (entries.containsKey(id) || registrations.containsKey(id));
            DownloadAttempt attempt = new DownloadAttempt(id, command.workType(), command.workId());
            Entry entry = new Entry(value(attempt, "", attempt.workId(), DownloadEvent.Phase.ACCEPTED),
                    owner.ownerUuid(), key, fingerprint);
            entries.put(id, entry);
            requests.put(key, id);
            return new Reservation(entry.value, false);
        }
    }

    public void register(DownloadAttempt attempt, String owner, String queueType, String title) {
        Objects.requireNonNull(attempt, "attempt");
        String safeType = queueType == null ? "" : queueType;
        if (!safeType.isEmpty() && !safeType.matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("invalid queue type");
        String safeTitle = title == null || title.isBlank() ? attempt.workId() : title;
        if (safeTitle.length() > 512) safeTitle = safeTitle.substring(0, 512);
        expire();
        UUID id = attempt.attemptId();
        CompletableFuture<Void> registration;
        while (true) {
            synchronized (this) {
                registration = registrations.get(id);
                if (registration == null) {
                    Entry entry = entries.get(id);
                    if (entry != null) {
                        if (!entry.value.attempt().equals(attempt) || !Objects.equals(entry.owner, owner)
                                || entry.task != null || entry.value.terminal())
                            throw new IllegalArgumentException("download task identity already registered");
                        entry.value = value(attempt, safeType, safeTitle, entry.value.phase());
                        return;
                    }
                    requireCapacity();
                    registration = new CompletableFuture<>();
                    registrations.put(id, registration);
                    break;
                }
            }
            // 只有相同执行的登记等待历史核对；不占用全局锁，也不公开未核实的任务。
            registration.join();
        }
        try {
            if (archived(attempt.attemptId(), RequestOwnerIdentity.adminScope()).isPresent())
                throw new IllegalArgumentException("download task identity already registered");
            synchronized (this) {
                entries.put(id, new Entry(
                        value(attempt, safeType, safeTitle, DownloadEvent.Phase.ACCEPTED), owner, null, null));
                registrations.remove(id);
            }
        } finally {
            synchronized (this) { registrations.remove(id, registration); }
            registration.complete(null);
        }
    }

    public synchronized void attach(DownloadAttempt attempt, QueueTaskTracker.Task task) {
        Entry entry = entries.get(attempt.attemptId());
        if (entry == null || !entry.value.attempt().equals(attempt) || entry.task != null)
            throw new IllegalStateException("download task is not available");
        entry.task = Objects.requireNonNull(task, "task");
    }

    /** 返回 false 时事件已经过期或与终态冲突，不能再次通知观察者。 */
    public boolean update(DownloadEvent event) {
        Entry entry;
        boolean terminal;
        synchronized (this) {
            entry = entries.get(event.attempt().attemptId());
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
            terminal = entry.value.terminal();
            if (terminal) entry.task = null;
        }
        if (terminal) saveResult(entry);
        return true;
    }

    public Optional<DownloadTaskSnapshot> find(UUID taskId, RequestOwnerIdentity owner) {
        Objects.requireNonNull(owner, "owner");
        expire();
        synchronized (this) {
            Entry entry = entries.get(taskId);
            if (entry != null) return visible(entry, owner) ? Optional.of(entry.value) : Optional.empty();
        }
        return archived(taskId, owner);
    }

    public DownloadTasks.Snapshot snapshot(RequestOwnerIdentity owner) {
        Objects.requireNonNull(owner, "owner");
        expire();
        synchronized (this) {
            return new DownloadTasks.Snapshot(epoch, revision,
                entries.values().stream().filter(e -> visible(e, owner)).map(e -> e.value).toList());
        }
    }

    public DownloadTasks.CancelResult cancel(UUID taskId, RequestOwnerIdentity owner) {
        Objects.requireNonNull(owner, "owner");
        expire();
        QueueTaskTracker.Task task;
        synchronized (this) {
            Entry entry = entries.get(taskId);
            if (entry == null) task = null;
            else {
                if (!visible(entry, owner)) return DownloadTasks.CancelResult.NOT_FOUND;
                if (entry.value.terminal()) return DownloadTasks.CancelResult.TERMINAL;
                task = entry.task;
                if (task == null) return DownloadTasks.CancelResult.NOT_CANCELLABLE;
            }
        }
        if (task == null) return archived(taskId, owner).isPresent()
                ? DownloadTasks.CancelResult.TERMINAL : DownloadTasks.CancelResult.NOT_FOUND;
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
        if (entries.size() + registrations.size() >= MAX_TASKS)
            throw new DownloadTaskException(DownloadTaskException.Code.CAPACITY_EXCEEDED);
    }

    private void expire() {
        Instant cutoff = clock.instant().minus(RETENTION);
        List<Entry> expired;
        synchronized (this) {
            expired = entries.values().stream()
                    .filter(entry -> entry.value.terminal() && entry.value.updatedAt().isBefore(cutoff)).toList();
        }
        for (Entry entry : expired) {
            if (!saveResult(entry)) continue;
            synchronized (this) {
                if (entries.remove(entry.value.attempt().attemptId(), entry)) {
                    if (entry.request != null) requests.remove(entry.request);
                    revision++;
                }
            }
        }
    }

    private Optional<DownloadTaskSnapshot> archived(UUID id, RequestOwnerIdentity owner) {
        return results == null ? Optional.empty() : results.find(id, owner);
    }

    private boolean saveResult(Entry entry) {
        synchronized (this) {
            if (results == null || entry.saved) return true;
            if (entry.saving || clock.instant().isBefore(entry.retrySaveAt)) return false;
            entry.saving = true;
        }
        try {
            results.save(entry.value, entry.owner);
            synchronized (this) { entry.saved = true; }
            return true;
        } catch (IOException failure) {
            boolean logFailure;
            synchronized (this) {
                entry.retrySaveAt = clock.instant().plus(RETENTION);
                logFailure = !entry.saveFailureLogged;
                entry.saveFailureLogged = true;
            }
            // 保存失败不能改写执行结果；保留内存事实，下次过期检查重试。
            if (logFailure) {
                LoggerFactory.getLogger(DownloadTaskRegistry.class).warn(
                        MessageBundles.getForLog("download.tasks.result-save-failed", entry.value.attempt().attemptId()), failure);
            }
            return false;
        } finally {
            synchronized (this) { entry.saving = false; }
        }
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
