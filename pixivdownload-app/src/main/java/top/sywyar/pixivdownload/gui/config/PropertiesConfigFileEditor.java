package top.sywyar.pixivdownload.gui.config;

import java.io.IOException;
import java.io.Reader;
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
 * Lightweight line-preserving editor for plugin-owned {@code .properties} config files.
 */
public class PropertiesConfigFileEditor {

    private final Path configPath;

    public PropertiesConfigFileEditor(Path configPath) {
        this.configPath = configPath;
    }

    public Map<String, String> readAll(Collection<String> keys) throws IOException {
        Set<String> keySet = ConfigFileEditor.validatedKeySet(keys);
        Map<String, String> result = new LinkedHashMap<>();
        if (keySet.isEmpty() || !Files.exists(configPath)) {
            return result;
        }
        List<String> lines = new ArrayList<>(readLines());
        rejectDuplicateManagedKeys(lines, keySet);
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid properties file: " + configPath, e);
        }
        for (String key : keySet) {
            if (properties.containsKey(key)) {
                result.put(key, ConfigFileEditor.requireSafeValue(properties.getProperty(key, "")));
            }
        }
        return result;
    }

    public synchronized void writeAll(Map<String, String> values) throws IOException {
        Map<String, String> safeValues = ConfigFileEditor.validatedValues(values);
        if (safeValues.isEmpty()) {
            return;
        }
        if (configPath.getParent() != null) {
            Files.createDirectories(configPath.getParent());
        }

        List<String> lines = new ArrayList<>(readLines());
        rejectDuplicateManagedKeys(lines, safeValues.keySet());
        Set<String> written = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String key = activeKey(lines.get(i));
            if (key != null && safeValues.containsKey(key)) {
                lines.set(i, key + "=" + escapeValue(safeValues.get(key)));
                written.add(key);
            }
        }
        for (Map.Entry<String, String> entry : safeValues.entrySet()) {
            if (!written.contains(entry.getKey())) {
                lines.add(entry.getKey() + "=" + escapeValue(entry.getValue()));
            }
        }
        writeLinesAtomically(lines);
    }

    public synchronized void removeAll(Collection<String> keys) throws IOException {
        Set<String> safeKeys = ConfigFileEditor.validatedKeySet(keys);
        if (safeKeys.isEmpty() || !Files.exists(configPath)) {
            return;
        }
        List<String> lines = new ArrayList<>(readLines());
        rejectDuplicateManagedKeys(lines, safeKeys);
        lines.removeIf(line -> {
            String key = activeKey(line);
            return key != null && safeKeys.contains(key);
        });
        writeLinesAtomically(lines);
    }

    public synchronized FileSnapshot snapshot() throws IOException {
        return new FileSnapshot(Files.exists(configPath), new ArrayList<>(readLines()));
    }

    public synchronized void restore(FileSnapshot snapshot) throws IOException {
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

    private static void rejectDuplicateManagedKeys(List<String> lines, Set<String> managedKeys) throws IOException {
        Set<String> seen = new HashSet<>();
        for (String line : lines) {
            String key = activeKey(line);
            if (key == null || !managedKeys.contains(key)) {
                continue;
            }
            if (!seen.add(key)) {
                throw new IOException("Duplicate active plugin config key: " + key);
            }
        }
    }

    private static String activeKey(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
            return null;
        }
        int separator = separatorIndex(trimmed);
        if (separator < 0) {
            return null;
        }
        String key = trimmed.substring(0, separator).trim();
        return key.isEmpty() ? null : key;
    }

    private static int separatorIndex(String line) {
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '=' || ch == ':') {
                return i;
            }
        }
        return -1;
    }

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
