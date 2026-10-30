package top.sywyar.pixivdownload.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 持久化不可达及受限作品；受限记录只暂缓启动自动请求，手动回填仍可重试。
 */
@Slf4j
final class ArtworksBackFillUnreachableStore {

    private final Path path;
    private final ObjectMapper mapper;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean dirty;

    private ArtworksBackFillUnreachableStore(Path path, ObjectMapper mapper) {
        this.path = path;
        this.mapper = mapper;
    }

    static ArtworksBackFillUnreachableStore load(Path path, ObjectMapper mapper) {
        ArtworksBackFillUnreachableStore store = new ArtworksBackFillUnreachableStore(path, mapper);
        if (path == null || !Files.isRegularFile(path)) {
            return store;
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0) {
                return store;
            }
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.isObject()) {
                return store;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                long id;
                try {
                    String key = field.getKey();
                    id = Long.parseLong(key.startsWith("novel:") ? key.substring(6) : key);
                    if (id <= 0) continue;
                } catch (NumberFormatException ignored) {
                    continue;
                }
                JsonNode value = field.getValue();
                String reason = value.path("reason").asText("");
                long firstSeenAt = value.path("firstSeenAt").asLong(0L);
                long lastSeenAt = value.path("lastSeenAt").asLong(firstSeenAt);
                int attempts = value.path("attempts").asInt(1);
                store.entries.put(key(field.getKey().startsWith("novel:") ? WorkType.NOVEL : WorkType.ARTWORK, id),
                        new Entry(reason, firstSeenAt, lastSeenAt, attempts, value.path("restricted").asBoolean(false)));
            }
        } catch (IOException e) {
            log.warn(message("artworks-backfill.unreachable.load-failed", path, e.getMessage()));
        }
        return store;
    }

    boolean contains(long artworkId) {
        return contains(WorkType.ARTWORK, artworkId);
    }

    boolean contains(WorkType type, long id) {
        Entry entry = entries.get(key(type, id));
        return entry != null && !entry.restricted();
    }

    boolean blocksNetwork(WorkType type, long id, boolean automatic) {
        Entry entry = entries.get(key(type, id));
        return entry != null && (!entry.restricted() || automatic);
    }

    void recordRestricted(WorkType type, long id, String reason) {
        record(type, id, reason, true);
    }

    void clearRestricted(WorkType type, long id) {
        String key = key(type, id);
        Entry entry = entries.get(key);
        if (entry != null && entry.restricted()) {
            entries.remove(key);
            dirty = true;
        }
    }

    private static String key(WorkType type, long id) {
        return type == WorkType.NOVEL ? "novel:" + id : String.valueOf(id);
    }

    int size() {
        return (int) entries.values().stream().filter(entry -> !entry.restricted()).count();
    }

    void record(long artworkId, String reason) {
        record(WorkType.ARTWORK, artworkId, reason);
    }

    void record(WorkType type, long artworkId, String reason) {
        record(type, artworkId, reason, false);
    }

    private void record(WorkType type, long artworkId, String reason, boolean restricted) {
        String key = key(type, artworkId);
        long now = System.currentTimeMillis();
        Entry existing = entries.get(key);
        String effectiveReason = reason == null || reason.isBlank()
                ? (existing != null ? existing.reason() : "")
                : reason;
        if (existing == null) {
            entries.put(key, new Entry(effectiveReason, now, now, 1, restricted));
        } else {
            entries.put(key, new Entry(
                    effectiveReason,
                    existing.firstSeenAt() > 0 ? existing.firstSeenAt() : now,
                    now,
                    existing.attempts() + 1,
                    restricted
            ));
        }
        dirty = true;
    }

    void save() throws IOException {
        if (!dirty) {
            return;
        }
        Files.createDirectories(path.getParent());
        ObjectNode root = mapper.createObjectNode();
        List<String> sortedIds = new ArrayList<>(entries.keySet());
        Collections.sort(sortedIds);
        for (String id : sortedIds) {
            Entry entry = entries.get(id);
            ObjectNode value = root.putObject(String.valueOf(id));
            value.put("reason", entry.reason());
            value.put("firstSeenAt", entry.firstSeenAt());
            value.put("lastSeenAt", entry.lastSeenAt());
            value.put("attempts", entry.attempts());
            if (entry.restricted()) value.put("restricted", true);
        }
        Path temporaryPath = path.resolveSibling(path.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(temporaryPath.toFile(), root);
        try {
            Files.move(
                    temporaryPath,
                    path,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (IOException atomicFailure) {
            Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }

    private static String message(String code, Object... args) {
        return MessageBundles.getForLog(code, args);
    }

    private record Entry(String reason, long firstSeenAt, long lastSeenAt, int attempts, boolean restricted) {}
}
