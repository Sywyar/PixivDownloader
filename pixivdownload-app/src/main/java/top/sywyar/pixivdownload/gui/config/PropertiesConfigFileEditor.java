package top.sywyar.pixivdownload.gui.config;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 按逻辑配置项编辑插件 properties，保留未修改项的物理行。
 */
public class PropertiesConfigFileEditor {

    // ponytail: 64 stripes bound storage; use managed per-file locks if collisions cause contention.
    private static final Object[] FILE_LOCKS = java.util.stream.IntStream.range(0, 64)
            .mapToObj(index -> new Object()).toArray();
    private final Path configPath;
    private final Object fileLock;

    public PropertiesConfigFileEditor(Path configPath) {
        this.configPath = configPath.toAbsolutePath().normalize();
        this.fileLock = FILE_LOCKS[Math.floorMod(this.configPath.hashCode(), FILE_LOCKS.length)];
    }

    public Map<String, String> readAll(Collection<String> keys) throws IOException {
        Set<String> keySet = ConfigFileEditor.validatedKeySet(keys);
        Map<String, String> result = new LinkedHashMap<>();
        if (keySet.isEmpty() || !Files.exists(configPath)) {
            return result;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (PropertyEntry entry : managedEntries(readLines(), keySet)) {
            values.put(entry.key(), ConfigFileEditor.requireSafeValue(entry.value()));
        }
        for (String key : keySet) {
            if (values.containsKey(key)) result.put(key, values.get(key));
        }
        return result;
    }

    public void writeAll(Map<String, String> values) throws IOException {
        synchronized (fileLock) { writeValues(values); }
    }

    /** Compare and save under the same lock as ordinary GUI writes. */
    public boolean writeIfBlank(String key, String value) throws IOException {
        synchronized (fileLock) {
            if (!readAll(List.of(key)).getOrDefault(key, "").isBlank()) return false;
            writeValues(Map.of(key, value));
            return true;
        }
    }

    private void writeValues(Map<String, String> values) throws IOException {
        Map<String, String> safeValues = ConfigFileEditor.validatedValues(values);
        if (safeValues.isEmpty()) {
            return;
        }
        List<String> lines = readLines();
        List<PropertyEntry> entries = managedEntries(lines, safeValues.keySet());
        writeLinesAtomically(replaceEntries(lines, entries, safeValues));
    }

    public void removeAll(Collection<String> keys) throws IOException {
        synchronized (fileLock) { removeValues(keys); }
    }

    private void removeValues(Collection<String> keys) throws IOException {
        Set<String> safeKeys = ConfigFileEditor.validatedKeySet(keys);
        if (safeKeys.isEmpty() || !Files.exists(configPath)) {
            return;
        }
        List<String> lines = readLines();
        List<PropertyEntry> entries = managedEntries(lines, safeKeys);
        writeLinesAtomically(replaceEntries(lines, entries, Map.of()));
    }

    private static List<String> replaceEntries(List<String> lines, List<PropertyEntry> entries, Map<String, String> values) {
        List<String> result = new ArrayList<>();
        Map<String, String> remaining = new LinkedHashMap<>(values);
        int cursor = 0;
        for (PropertyEntry entry : entries) {
            result.addAll(lines.subList(cursor, entry.start()));
            String value = remaining.remove(entry.key());
            if (value != null) result.add(entry.key() + "=" + escapeValue(value));
            cursor = entry.end();
        }
        result.addAll(lines.subList(cursor, lines.size()));
        remaining.forEach((key, value) -> result.add(key + "=" + escapeValue(value)));
        return result;
    }

    public FileSnapshot snapshot() throws IOException {
        synchronized (fileLock) {
            return new FileSnapshot(Files.exists(configPath), readLines());
        }
    }

    public void restore(FileSnapshot snapshot) throws IOException {
        synchronized (fileLock) { restoreSnapshot(snapshot); }
    }

    private void restoreSnapshot(FileSnapshot snapshot) throws IOException {
        if (snapshot == null) {
            throw new IOException("Cannot restore plugin config file without a snapshot");
        }
        if (!snapshot.existed()) {
            Files.deleteIfExists(configPath);
            return;
        }
        if (configPath.getParent() != null) {
            Files.createDirectories(configPath.getParent());
        }
        writeLinesAtomically(snapshot.lines());
    }

    private List<String> readLines() throws IOException {
        if (!Files.exists(configPath)) {
            return List.of();
        }
        return Files.readAllLines(configPath, StandardCharsets.UTF_8);
    }

    private void writeLinesAtomically(List<String> lines) throws IOException {
        Path target = configPath.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, lines, StandardCharsets.UTF_8);
            ConfigFileEditor.moveReplacing(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private List<PropertyEntry> managedEntries(List<String> lines, Set<String> managedKeys) throws IOException {
        List<PropertyEntry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        StringBuilder logical = new StringBuilder();
        Properties property = new Properties();
        int start = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int offset = 0;
            while (offset < line.length() && " \t\f".indexOf(line.charAt(offset)) >= 0) offset++;
            if (logical.isEmpty()) {
                start = i;
                if (offset == line.length() || line.charAt(offset) == '#' || line.charAt(offset) == '!') continue;
            }
            logical.append(line, offset, line.length());
            int backslashes = 0;
            for (int end = line.length() - 1; end >= offset && line.charAt(end) == '\\'; end--) backslashes++;
            if ((backslashes & 1) != 0) {
                logical.setLength(logical.length() - 1);
                if (i + 1 < lines.size()) continue;
            }
            // 只定位物理行范围；键和值的转义仍由 JDK properties 解析器处理。
            property.clear();
            try {
                property.load(new StringReader(logical.toString()));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid properties file: " + configPath, invalid);
            }
            for (String key : property.stringPropertyNames()) {
                if (!managedKeys.contains(key)) continue;
                if (!seen.add(key)) throw new IOException("Duplicate active plugin config key: " + key);
                entries.add(new PropertyEntry(start, i + 1, key, property.getProperty(key)));
            }
            logical.setLength(0);
        }
        return entries;
    }

    private record PropertyEntry(int start, int end, String key, String value) {}

    private static String escapeValue(String value) {
        String v = value == null ? "" : value;
        StringBuilder out = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            switch (ch) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch == ' ' && (i == 0 || i == v.length() - 1)) {
                        out.append("\\ ");
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    public record FileSnapshot(boolean existed, List<String> lines) {
        public FileSnapshot {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }
}
