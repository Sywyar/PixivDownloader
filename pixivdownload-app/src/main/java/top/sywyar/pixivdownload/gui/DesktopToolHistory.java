package top.sywyar.pixivdownload.gui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.regex.Pattern;

/** 桌面维护工具终态历史的有界持久化。 */
final class DesktopToolHistory {
    static final int MAX_ENTRIES = 100;
    static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int SCHEMA_VERSION = 1;
    private static final String FILE_NAME = "tool-history.json";
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "entries");
    private static final Set<String> ENTRY_FIELDS = Set.of(
            "toolId", "startedAtEpochMs", "finishedAtEpochMs", "outcome",
            "processedCount", "changedCount", "failedCount", "logFileName");
    private static final Pattern SAFE_LOG_NAME = Pattern.compile(
            "(?:artworks-backfill|json-to-sqlite-migration)_[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{6}\\.html");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(DesktopToolHistory.class);

    private final Path file;
    private List<Entry> entries;
    private boolean writable = true;

    DesktopToolHistory(Path guiStateDirectory) {
        file = guiStateDirectory.resolve(FILE_NAME);
        entries = load();
    }

    synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    synchronized void record(ToolId toolId, Outcome outcome, long startedAtEpochMs,
                             Integer processedCount, Integer changedCount, Integer failedCount,
                             Path logPath) {
        if (!writable) return;
        if (toolId == null || outcome == null || startedAtEpochMs < 0L) {
            LOG.warn("拒绝写入无效的桌面工具历史记录：toolId={}, outcome={}", toolId, outcome);
            return;
        }
        long finishedAtEpochMs = Math.max(startedAtEpochMs, System.currentTimeMillis());
        Entry entry;
        try {
            entry = new Entry(toolId, startedAtEpochMs, finishedAtEpochMs, outcome,
                    checkedCount(processedCount), checkedCount(changedCount), checkedCount(failedCount),
                    safeLogFileName(toolId, logPath));
        } catch (IllegalArgumentException invalid) {
            LOG.warn("拒绝写入无效的桌面工具历史记录：toolId={}, outcome={}", toolId, outcome);
            return;
        }
        List<Entry> next = new ArrayList<>(Math.min(MAX_ENTRIES, entries.size() + 1));
        next.add(entry);
        next.addAll(entries.subList(0, Math.min(entries.size(), MAX_ENTRIES - 1)));
        try {
            entries = persist(next);
        } catch (IOException failure) {
            LOG.warn("无法持久化桌面工具历史", failure);
        }
    }

    private List<Entry> load() {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > MAX_FILE_BYTES) {
                throw new IOException("invalid tool history file");
            }
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("invalid tool history file");
            JsonNode root = MAPPER.readTree(bytes);
            requireObjectWithFields(root, ROOT_FIELDS);
            if (!root.path("schemaVersion").canConvertToInt()
                    || root.path("schemaVersion").intValue() != SCHEMA_VERSION) {
                throw new IOException("unsupported tool history schema");
            }
            JsonNode values = root.path("entries");
            if (!values.isArray() || values.size() > MAX_ENTRIES) {
                throw new IOException("invalid tool history entries");
            }
            List<Entry> loaded = new ArrayList<>(values.size());
            for (JsonNode value : values) loaded.add(readEntry(value));
            return List.copyOf(loaded);
        } catch (Exception failure) {
            quarantine(failure);
            return List.of();
        }
    }

    private Entry readEntry(JsonNode value) throws IOException {
        requireObjectWithFields(value, ENTRY_FIELDS);
        ToolId toolId = enumValue(ToolId.class, requiredText(value, "toolId"));
        Outcome outcome = enumValue(Outcome.class, requiredText(value, "outcome"));
        long started = requiredLong(value, "startedAtEpochMs");
        long finished = requiredLong(value, "finishedAtEpochMs");
        if (started < 0 || finished < started) throw new IOException("invalid tool history timestamps");
        Integer processed = optionalCount(value, "processedCount");
        Integer changed = optionalCount(value, "changedCount");
        Integer failed = optionalCount(value, "failedCount");
        String logFileName = optionalText(value, "logFileName");
        if (logFileName != null && !validLogFileName(toolId, logFileName)) {
            throw new IOException("invalid tool history log file name");
        }
        return new Entry(toolId, started, finished, outcome, processed, changed, failed, logFileName);
    }

    private List<Entry> persist(List<Entry> values) throws IOException {
        Files.createDirectories(file.getParent());
        List<Entry> bounded = new ArrayList<>(values);
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

    private static byte[] serialize(List<Entry> entries) throws IOException {
        List<Map<String, Object>> values = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("toolId", entry.toolId().name());
            value.put("startedAtEpochMs", entry.startedAtEpochMs());
            value.put("finishedAtEpochMs", entry.finishedAtEpochMs());
            value.put("outcome", entry.outcome().name());
            if (entry.processedCount() != null) value.put("processedCount", entry.processedCount());
            if (entry.changedCount() != null) value.put("changedCount", entry.changedCount());
            if (entry.failedCount() != null) value.put("failedCount", entry.failedCount());
            if (entry.logFileName() != null) value.put("logFileName", entry.logFileName());
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

    private static String safeLogFileName(ToolId toolId, Path logPath) {
        if (logPath == null) return null;
        Path fileName = logPath.getFileName();
        String value = fileName == null ? "" : fileName.toString();
        return validLogFileName(toolId, value) ? value : null;
    }

    private static boolean validLogFileName(ToolId toolId, String value) {
        if (!SAFE_LOG_NAME.matcher(value).matches()) return false;
        return switch (toolId) {
            case ARTWORKS_BACKFILL -> value.startsWith("artworks-backfill_");
            case JSON_TO_SQLITE_MIGRATION -> value.startsWith("json-to-sqlite-migration_");
            case IMAGE_CLASSIFIER, FOLDER_CHECKER -> false;
        };
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value) throws IOException {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid enum", invalid);
        }
    }

    enum ToolId { IMAGE_CLASSIFIER, FOLDER_CHECKER, ARTWORKS_BACKFILL, JSON_TO_SQLITE_MIGRATION }
    enum Outcome { SUCCEEDED, FAILED, CANCELLED, CLOSED }

    record Entry(ToolId toolId, long startedAtEpochMs, long finishedAtEpochMs, Outcome outcome,
                 Integer processedCount, Integer changedCount, Integer failedCount, String logFileName) {}
}
