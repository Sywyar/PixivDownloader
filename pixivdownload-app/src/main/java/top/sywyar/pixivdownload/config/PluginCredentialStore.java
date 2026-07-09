package top.sywyar.pixivdownload.config;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** Owner-scoped persistent store for plugin credentials. Values are never added to the parent Environment. */
@Service
public class PluginCredentialStore {

    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    public synchronized Map<String, String> readAll(String ownerPluginId) throws IOException {
        Path path = RuntimeFiles.resolvePluginCredentialPath(ownerPluginId);
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid plugin credential file for owner: " + ownerPluginId, e);
        }
        Map<String, String> result = new LinkedHashMap<>();
        properties.stringPropertyNames().stream().sorted().forEach(key -> {
            if (SAFE_KEY.matcher(key).matches()) {
                result.put(key, properties.getProperty(key, ""));
            }
        });
        return Map.copyOf(result);
    }

    public synchronized void update(String ownerPluginId, Map<String, String> updates) throws IOException {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        Map<String, String> safeUpdates = validated(updates);
        Map<String, String> next = new LinkedHashMap<>(readAll(ownerPluginId));
        for (Map.Entry<String, String> entry : safeUpdates.entrySet()) {
            if (entry.getValue().isBlank()) {
                next.remove(entry.getKey());
            } else {
                next.put(entry.getKey(), entry.getValue());
            }
        }
        write(ownerPluginId, next);
        Map<String, String> verified = readAll(ownerPluginId);
        for (Map.Entry<String, String> entry : safeUpdates.entrySet()) {
            String expected = entry.getValue().isBlank() ? null : entry.getValue();
            if (!java.util.Objects.equals(expected, verified.get(entry.getKey()))) {
                throw new IOException("Plugin credential verification failed for owner/key: "
                        + ownerPluginId + "/" + entry.getKey());
            }
        }
    }

    public synchronized Snapshot snapshot(String ownerPluginId) throws IOException {
        Path path = RuntimeFiles.resolvePluginCredentialPath(ownerPluginId);
        return new Snapshot(Files.exists(path), Files.exists(path) ? Files.readAllBytes(path) : new byte[0]);
    }

    public synchronized void restore(String ownerPluginId, Snapshot snapshot) throws IOException {
        if (snapshot == null) {
            throw new IOException("Cannot restore plugin credentials without a snapshot");
        }
        Path path = RuntimeFiles.resolvePluginCredentialPath(ownerPluginId);
        if (!snapshot.existed()) {
            Files.deleteIfExists(path);
            return;
        }
        writeBytesAtomically(path, snapshot.content());
    }

    private void write(String ownerPluginId, Map<String, String> values) throws IOException {
        Path path = RuntimeFiles.resolvePluginCredentialPath(ownerPluginId);
        if (values.isEmpty()) {
            Files.deleteIfExists(path);
            return;
        }
        List<String> lines = new ArrayList<>();
        values.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> lines.add(entry.getKey() + "=" + escape(entry.getValue())));
        writeBytesAtomically(path, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytesAtomically(Path path, byte[] content) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        Path parent = target.getParent();
        Files.createDirectories(parent);
        tightenDirectory(parent);
        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            tightenFile(temp);
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            tightenFile(target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Map<String, String> validated(Map<String, String> values) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (!SAFE_KEY.matcher(key).matches()) {
                throw new IOException("Invalid plugin credential key");
            }
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (value.indexOf('\0') >= 0) {
                throw new IOException("Plugin credential contains unsupported NUL character for key: " + key);
            }
            result.put(key, value);
        }
        return Map.copyOf(result);
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(ch);
            }
        }
        return escaped.toString();
    }

    private static void tightenDirectory(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, OWNER_DIRECTORY_PERMISSIONS);
        } catch (IOException | UnsupportedOperationException ignored) {
            java.io.File file = directory.toFile();
            file.setReadable(false, false);
            file.setWritable(false, false);
            file.setExecutable(false, false);
            file.setReadable(true, true);
            file.setWritable(true, true);
            file.setExecutable(true, true);
        }
    }

    private static void tightenFile(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_FILE_PERMISSIONS);
        } catch (IOException | UnsupportedOperationException ignored) {
            java.io.File file = path.toFile();
            file.setReadable(false, false);
            file.setWritable(false, false);
            file.setExecutable(false, false);
            file.setReadable(true, true);
            file.setWritable(true, true);
        }
    }

    public record Snapshot(boolean existed, byte[] content) {
        public Snapshot {
            content = content == null ? new byte[0] : content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
