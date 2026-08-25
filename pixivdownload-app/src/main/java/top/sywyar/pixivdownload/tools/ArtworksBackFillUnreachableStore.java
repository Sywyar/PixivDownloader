package top.sywyar.pixivdownload.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
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
 * 持久化回填工具已确认不可达的 Pixiv 作品，避免后续运行重复请求。
 */
@Slf4j
final class ArtworksBackFillUnreachableStore {

    private final Path path;
    private final ObjectMapper mapper;
    private final Map<Long, Entry> entries = new LinkedHashMap<>();
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
                    id = Long.parseLong(field.getKey());
                } catch (NumberFormatException ignored) {
                    continue;
                }
                JsonNode value = field.getValue();
                String reason = value.path("reason").asText("");
                long firstSeenAt = value.path("firstSeenAt").asLong(0L);
                long lastSeenAt = value.path("lastSeenAt").asLong(firstSeenAt);
                int attempts = value.path("attempts").asInt(1);
                store.entries.put(id, new Entry(reason, firstSeenAt, lastSeenAt, attempts));
            }
        } catch (IOException e) {
            log.warn(message("artworks-backfill.unreachable.load-failed", path, e.getMessage()));
        }
        return store;
    }

    boolean contains(long artworkId) {
        return entries.containsKey(artworkId);
    }

    int size() {
        return entries.size();
    }

    void record(long artworkId, String reason) {
        long now = System.currentTimeMillis();
        Entry existing = entries.get(artworkId);
        String effectiveReason = reason == null || reason.isBlank()
                ? (existing != null ? existing.reason() : "")
                : reason;
        if (existing == null) {
            entries.put(artworkId, new Entry(effectiveReason, now, now, 1));
        } else {
            entries.put(artworkId, new Entry(
                    effectiveReason,
                    existing.firstSeenAt() > 0 ? existing.firstSeenAt() : now,
                    now,
                    existing.attempts() + 1
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
        List<Long> sortedIds = new ArrayList<>(entries.keySet());
        Collections.sort(sortedIds);
        for (Long id : sortedIds) {
            Entry entry = entries.get(id);
            ObjectNode value = root.putObject(String.valueOf(id));
            value.put("reason", entry.reason());
            value.put("firstSeenAt", entry.firstSeenAt());
            value.put("lastSeenAt", entry.lastSeenAt());
            value.put("attempts", entry.attempts());
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
        return MessageBundles.get(code, args);
    }

    private record Entry(String reason, long firstSeenAt, long lastSeenAt, int attempts) {}
}
