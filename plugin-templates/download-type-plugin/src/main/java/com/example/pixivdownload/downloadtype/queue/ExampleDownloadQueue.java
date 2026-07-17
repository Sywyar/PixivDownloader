package com.example.pixivdownload.downloadtype.queue;

import top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Deterministic in-memory domain action used by the template.
 *
 * <p>It has no asynchronous work, so the {@link QueueOperations} default completed drain is truthful. A real
 * asynchronous downloader must override the quiesce methods and expose a real positive-generation drain.</p>
 */
@PluginManagedBean
public final class ExampleDownloadQueue implements QueueOperations {

    public static final String QUEUE_TYPE = "example-download";
    private static final String ADMIN_OWNER_KEY = "host:admin";

    private final ConcurrentMap<QueueKey, QueueItem> items = new ConcurrentHashMap<>();

    @Override
    public String queueType() {
        return QUEUE_TYPE;
    }

    /** 手工请求只接收宿主已解析的可信 owner 作用域。 */
    public QueueItem complete(String id, String title, RequestOwnerIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return completeForOwner(id, title, identity.admin() ? ADMIN_OWNER_KEY : identity.ownerUuid());
    }

    /** 计划执行器使用自身的稳定 owner 键写入非 HTTP 任务。 */
    public QueueItem completeForOwner(String id, String title, String ownerKey) {
        String normalizedId = requireNumericId(id);
        String normalizedOwner = requireOwner(ownerKey);
        QueueItem item = new QueueItem(
                normalizedId,
                normalize(title, "Example " + normalizedId),
                normalizedOwner,
                "completed",
                Instant.now().toString());
        items.put(new QueueKey(normalizedOwner, normalizedId), item);
        return item;
    }

    /** 访客只读自身条目；管理员可跨 owner 读取同 workKey 的最新条目。 */
    public Optional<QueueItem> find(String id, RequestOwnerIdentity identity) {
        String workKey = requireNumericId(id);
        Objects.requireNonNull(identity, "identity");
        if (!identity.admin()) {
            return Optional.ofNullable(items.get(new QueueKey(identity.ownerUuid(), workKey)));
        }
        return items.entrySet().stream()
                .filter(entry -> entry.getKey().workKey().equals(workKey))
                .map(java.util.Map.Entry::getValue)
                .sorted(ITEM_ORDER)
                .findFirst();
    }

    public List<QueueItem> snapshot() {
        return items.values().stream()
                .sorted(ITEM_ORDER)
                .toList();
    }

    @Override
    public void cancel(String workKey, String ownerUuid, boolean admin) {
        String normalizedWorkKey;
        try {
            normalizedWorkKey = requireNumericId(workKey);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        if (admin) {
            items.keySet().removeIf(key -> key.workKey().equals(normalizedWorkKey));
            return;
        }
        if (ownerUuid != null && !ownerUuid.isBlank()) {
            items.remove(new QueueKey(ownerUuid.trim(), normalizedWorkKey));
        }
    }

    @Override
    public int clearAll() {
        int size = items.size();
        items.clear();
        return size;
    }

    @Override
    public int clearForOwner(String ownerUuid) {
        if (ownerUuid == null) {
            return 0;
        }
        int before = items.size();
        String normalizedOwner = ownerUuid.trim();
        items.keySet().removeIf(key -> normalizedOwner.equals(key.ownerKey()));
        return before - items.size();
    }

    private static String requireNumericId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[0-9]{1,18}")) {
            throw new IllegalArgumentException("example id must contain 1 to 18 digits");
        }
        return normalized;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String requireOwner(String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()) {
            throw new IllegalArgumentException("example owner key is required");
        }
        return ownerKey.trim();
    }

    private static final Comparator<QueueItem> ITEM_ORDER = Comparator
            .comparing(QueueItem::completedAt).reversed()
            .thenComparing(QueueItem::ownerKey)
            .thenComparing(QueueItem::id);

    private record QueueKey(String ownerKey, String workKey) {
    }

    public record QueueItem(
            String id,
            String title,
            String ownerKey,
            String status,
            String completedAt
    ) {
    }
}
