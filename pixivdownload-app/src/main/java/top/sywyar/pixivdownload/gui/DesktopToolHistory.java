package top.sywyar.pixivdownload.gui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.ToolHistoryEntry;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.ToolId;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.ToolOutcome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool.Identity;

/** 桌面维护工具终态历史的有界持久化。 */
final class DesktopToolHistory {
    static final int MAX_ENTRIES = 100;
    static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int SCHEMA_VERSION = 2;
    private static final String FILE_NAME = "tool-history.json";
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "entries");
    private static final Set<String> ENTRY_FIELDS = Set.of(
            "toolId", "startedAtEpochMs", "finishedAtEpochMs", "outcome",
            "processedCount", "changedCount", "failedCount", "logFileName", "executionId", "sourceIdentity");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(DesktopToolHistory.class);

    private final Path file;
    private List<ToolHistoryEntry> entries;
    private boolean writable = true;

    DesktopToolHistory(Path guiStateDirectory) {
        file = guiStateDirectory.resolve(FILE_NAME);
        entries = load();
    }

    synchronized List<ToolHistoryEntry> entries() {
        return List.copyOf(entries);
    }

    synchronized void record(ToolId toolId, ToolOutcome outcome, long startedAtEpochMs,
                             Integer processedCount, Integer changedCount, Integer failedCount,
                             Path logPath) {
        record(toolId, outcome, startedAtEpochMs, processedCount, changedCount, failedCount,
                logPath, UUID.randomUUID(), null);
    }

    synchronized void record(ToolId toolId, ToolOutcome outcome, long startedAtEpochMs,
                             Integer processedCount, Integer changedCount, Integer failedCount,
                             Path logPath, UUID executionId, Identity sourceIdentity) {
        if (!writable) return;
        if (executionId == null) throw new IllegalArgumentException("executionId");
        if (entries.stream().anyMatch(entry -> executionId.equals(entry.executionId()))) return;
        if (toolId == null || outcome == null || startedAtEpochMs < 0L) {
            LOG.warn("拒绝写入无效的桌面工具历史记录：toolId={}, outcome={}", toolId, outcome);
            return;
        }
        long finishedAtEpochMs = Math.max(startedAtEpochMs, System.currentTimeMillis());
        ToolHistoryEntry entry;
        try {
            entry = new ToolHistoryEntry(toolId, startedAtEpochMs, finishedAtEpochMs, outcome,
                    checkedCount(processedCount), checkedCount(changedCount), checkedCount(failedCount),
                    safeLogFileName(logPath), executionId, sourceIdentity);
        } catch (IllegalArgumentException invalid) {
            LOG.warn("拒绝写入无效的桌面工具历史记录：toolId={}, outcome={}", toolId, outcome);
            return;
        }
        List<ToolHistoryEntry> next = new ArrayList<>(Math.min(MAX_ENTRIES, entries.size() + 1));
        next.add(entry);
        next.addAll(entries.subList(0, Math.min(entries.size(), MAX_ENTRIES - 1)));
        try {
            entries = persist(next);
        } catch (IOException failure) {
            LOG.warn("无法持久化桌面工具历史", failure);
        }
    }

    private List<ToolHistoryEntry> load() {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > MAX_FILE_BYTES) {
                throw new IOException("invalid tool history file");
            }
            byte[] bytes;
            try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_FILE_BYTES + 1); }
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("invalid tool history file");
            JsonNode root = MAPPER.readTree(bytes);
            requireObjectWithFields(root, ROOT_FIELDS);
            if (!root.path("schemaVersion").canConvertToInt()
                    || root.path("schemaVersion").intValue() < 1
                    || root.path("schemaVersion").intValue() > SCHEMA_VERSION) {
                throw new IOException("unsupported tool history schema");
            }
            JsonNode values = root.path("entries");
            if (!values.isArray() || values.size() > MAX_ENTRIES) {
                throw new IOException("invalid tool history entries");
            }
            List<ToolHistoryEntry> loaded = new ArrayList<>(values.size());
            for (JsonNode value : values) loaded.add(readEntry(value));
            return List.copyOf(loaded);
        } catch (Exception failure) {
            quarantine(failure);
            return List.of();
        }
    }

    private ToolHistoryEntry readEntry(JsonNode value) throws IOException {
        requireObjectWithFields(value, ENTRY_FIELDS);
        ToolId toolId = enumValue(ToolId.class, requiredText(value, "toolId"));
        ToolOutcome outcome = enumValue(ToolOutcome.class, requiredText(value, "outcome"));
        long started = requiredLong(value, "startedAtEpochMs");
        long finished = requiredLong(value, "finishedAtEpochMs");
        if (started < 0 || finished < started) throw new IOException("invalid tool history timestamps");
        Integer processed = optionalCount(value, "processedCount");
        Integer changed = optionalCount(value, "changedCount");
        Integer failed = optionalCount(value, "failedCount");
        String logFileName = optionalText(value, "logFileName");
        if (logFileName != null && !validLogFileName(logFileName)) {
            throw new IOException("invalid tool history log file name");
        }
        String execution = optionalText(value, "executionId");
        UUID executionId = execution == null ? null : UUID.fromString(execution);
        Identity source = null;
        if (value.has("sourceIdentity")) {
            JsonNode owner = value.get("sourceIdentity");
            requireObjectWithFields(owner, Set.of("pluginId", "packageId", "generation", "publication"));
            String pluginId = requiredText(owner, "pluginId");
            String packageId = requiredText(owner, "packageId");
            long generation = requiredLong(owner, "generation");
            long publication = requiredLong(owner, "publication");
            if (executionId == null || generation < 0 || publication <= 0) throw new IOException("invalid source identity");
            source = new Identity(pluginId, packageId, generation, publication);
        }
        return new ToolHistoryEntry(toolId, started, finished, outcome, processed, changed, failed, logFileName, executionId, source);
    }

    private List<ToolHistoryEntry> persist(List<ToolHistoryEntry> values) throws IOException {
        Files.createDirectories(file.getParent());
        List<ToolHistoryEntry> bounded = new ArrayList<>(values);
        byte[] bytes = serialize(bounded);
        while (bytes.length > MAX_FILE_BYTES && bounded.size() > 1) {
            bounded.remove(bounded.size() - 1);
            bytes = serialize(bounded);
        }
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("tool history exceeds byte limit");
        Path temporary = Files.createTempFile(file.getParent(), "tool-history-", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return List.copyOf(bounded);
    }

    private static byte[] serialize(List<ToolHistoryEntry> entries) throws IOException {
        List<Map<String, Object>> values = new ArrayList<>(entries.size());
        for (ToolHistoryEntry entry : entries) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("toolId", entry.toolId().name());
            value.put("startedAtEpochMs", entry.startedAtEpochMs());
            value.put("finishedAtEpochMs", entry.finishedAtEpochMs());
            value.put("outcome", entry.outcome().name());
            if (entry.processedCount() != null) value.put("processedCount", entry.processedCount());
            if (entry.changedCount() != null) value.put("changedCount", entry.changedCount());
            if (entry.failedCount() != null) value.put("failedCount", entry.failedCount());
            if (entry.logFileName() != null) value.put("logFileName", entry.logFileName());
            if (entry.executionId() != null) value.put("executionId", entry.executionId().toString());
            if (entry.sourceIdentity() != null) {
                Identity owner = entry.sourceIdentity();
                value.put("sourceIdentity", Map.of("pluginId", owner.pluginId(), "packageId", owner.packageId(),
                        "generation", owner.generation(), "publication", owner.publication()));
            }
            values.add(value);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("entries", values);
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
    }

    private void quarantine(Exception failure) {
        long epoch = Math.max(0L, System.currentTimeMillis());
        try {
            Path target;
            do {
                target = file.resolveSibling("tool-history.corrupt-" + epoch++ + ".json");
            } while (Files.exists(target, LinkOption.NOFOLLOW_LINKS));
            Files.move(file, target);
            LOG.warn("桌面工具历史不可读取，已保留为 {}", target.getFileName(), failure);
        } catch (Exception quarantineFailure) {
            writable = false;
            LOG.error("桌面工具历史不可读取且无法保留原文件，已禁用本次会话写入", quarantineFailure);
        }
    }

    private static void requireObjectWithFields(JsonNode value, Set<String> allowed) throws IOException {
        if (value == null || !value.isObject()) throw new IOException("expected object");
        Iterator<String> names = value.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) throw new IOException("unexpected field");
        }
    }

    private static String requiredText(JsonNode value, String field) throws IOException {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual() || node.textValue().isBlank()) throw new IOException("invalid text");
        return node.textValue();
    }

    private static String optionalText(JsonNode value, String field) throws IOException {
        JsonNode node = value.get(field);
        if (node == null) return null;
        if (!node.isTextual() || node.textValue().isBlank()) throw new IOException("invalid optional text");
        return node.textValue();
    }

    private static long requiredLong(JsonNode value, String field) throws IOException {
        JsonNode node = value.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) throw new IOException("invalid long");
        return node.longValue();
    }

    private static Integer optionalCount(JsonNode value, String field) throws IOException {
        JsonNode node = value.get(field);
        if (node == null) return null;
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0) {
            throw new IOException("invalid count");
        }
        return node.intValue();
    }

    private static Integer checkedCount(Integer value) {
        if (value != null && value < 0) throw new IllegalArgumentException("count must be non-negative");
        return value;
    }

    private static String safeLogFileName(Path logPath) {
        if (logPath == null) return null;
        Path fileName = logPath.getFileName();
        String value = fileName == null ? "" : fileName.toString();
        return validLogFileName(value) ? value : null;
    }

    private static boolean validLogFileName(String value) {
        return DesktopToolLogs.validFileName(value) && !value.endsWith("-latest.html");
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value) throws IOException {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid enum", invalid);
        }
    }

}
