package top.sywyar.pixivdownload.config.credential;

import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.config.RuntimeFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Host-owned encrypted store for owner-scoped plugin credentials.
 *
 * <p>Only decrypted values are returned. Root and derived keys remain inside the host and are never
 * added to a plugin property source or stable contract. All store instances serialize a complete
 * read-modify-write or rollback operation on the canonical credential path for that owner.
 */
@Service
public class PluginCredentialStore {

    private static final ConcurrentMap<Path, Object> PROCESS_LOCKS = new ConcurrentHashMap<>();
    private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final PluginCredentialCipher cipher;

    public PluginCredentialStore() {
        this(new PluginCredentialCipher(PluginCredentialKeyMaterial.load()));
    }

    PluginCredentialStore(PluginCredentialKeyMaterial keyMaterial) {
        this(new PluginCredentialCipher(keyMaterial));
    }

    PluginCredentialStore(PluginCredentialCipher cipher) {
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    /**
     * Reads this owner's credentials. Envelopes encrypted by the open-source fallback are
     * atomically rewritten with the current key after successful authenticated decode.
     */
    public Map<String, String> readAll(String ownerPluginId) throws IOException {
        Path path = credentialPath(ownerPluginId);
        synchronized (processLock(path)) {
            return readAllLocked(ownerPluginId, path);
        }
    }

    private Map<String, String> readAllLocked(String ownerPluginId, Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        byte[] content = Files.readAllBytes(path);
        try {
            PluginCredentialCipher.Decoded decoded = cipher.decode(ownerPluginId, content);
            Map<String, String> normalized = withoutBlankValues(decoded.values());
            if (decoded.rewriteWithCurrentKey() || !normalized.equals(decoded.values())) {
                try {
                    write(ownerPluginId, path, normalized);
                    verify(ownerPluginId, path, normalized);
                } catch (IOException | RuntimeException failure) {
                    restoreOriginal(path, true, content, failure);
                    throw failure;
                }
            }
            return normalized;
        } finally {
            Arrays.fill(content, (byte) 0);
        }
    }

    /**
     * Merges credential updates. A blank value is an explicit removal signal; callers that mean
     * “keep the existing secret” must omit that key.
     */
    public void update(String ownerPluginId, Map<String, String> updates) throws IOException {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        Map<String, String> safeUpdates = validatedUpdates(updates);
        Path path = credentialPath(ownerPluginId);
        synchronized (processLock(path)) {
            Map<String, String> current = readAllLocked(ownerPluginId, path);
            Map<String, String> next = new LinkedHashMap<>(current);
            for (Map.Entry<String, String> entry : safeUpdates.entrySet()) {
                if (entry.getValue().isBlank()) {
                    next.remove(entry.getKey());
                } else {
                    next.put(entry.getKey(), entry.getValue());
                }
            }
            Map<String, String> immutableNext = Map.copyOf(next);
            if (immutableNext.equals(current)) {
                return;
            }
            boolean existed = Files.exists(path);
            byte[] previous = existed ? Files.readAllBytes(path) : new byte[0];
            try {
                write(ownerPluginId, path, immutableNext);
                verify(ownerPluginId, path, immutableNext);
            } catch (IOException | RuntimeException failure) {
                restoreOriginal(path, existed, previous, failure);
                throw failure;
            } finally {
                Arrays.fill(previous, (byte) 0);
            }
        }
    }

    /**
     * Runs a compound host operation while holding every requested owner's process-wide store
     * lock. Paths are canonicalized, deduplicated, and acquired in a stable order so a caller can
     * safely combine credential snapshots with legacy-file cleanup and rollback.
     *
     * <p>The operation may call this store's methods for the declared owners; Java monitor locks
     * are reentrant. Callers must declare every credential owner they may access inside the
     * operation.
     */
    public void withOwnerLocks(Collection<String> ownerPluginIds, IoOperation operation)
            throws IOException {
        Objects.requireNonNull(ownerPluginIds, "ownerPluginIds");
        Objects.requireNonNull(operation, "operation");
        LinkedHashSet<Path> uniquePaths = new LinkedHashSet<>();
        for (String ownerPluginId : ownerPluginIds) {
            uniquePaths.add(credentialPath(ownerPluginId));
        }
        List<Path> orderedPaths = new ArrayList<>(uniquePaths);
        orderedPaths.sort(Comparator.comparing(Path::toString));
        runWithLocks(orderedPaths, 0, operation);
    }

    public Snapshot snapshot(String ownerPluginId) throws IOException {
        Path path = credentialPath(ownerPluginId);
        synchronized (processLock(path)) {
            boolean existed = Files.exists(path);
            return new Snapshot(existed, existed ? Files.readAllBytes(path) : new byte[0]);
        }
    }

    public void restore(String ownerPluginId, Snapshot snapshot) throws IOException {
        if (snapshot == null) {
            throw new IOException("Cannot restore plugin credentials without a snapshot");
        }
        Path path = credentialPath(ownerPluginId);
        synchronized (processLock(path)) {
            if (!snapshot.existed()) {
                Files.deleteIfExists(path);
                return;
            }
            writeBytesAtomically(path, snapshot.content());
        }
    }

    private void write(String ownerPluginId, Path path, Map<String, String> values) throws IOException {
        if (values.isEmpty()) {
            Files.deleteIfExists(path);
            return;
        }
        byte[] encrypted = cipher.encrypt(ownerPluginId, values);
        try {
            writeBytesAtomically(path, encrypted);
        } finally {
            Arrays.fill(encrypted, (byte) 0);
        }
    }

    private void verify(String ownerPluginId, Path path, Map<String, String> expected) throws IOException {
        if (expected.isEmpty()) {
            if (Files.exists(path)) {
                throw new IOException("Plugin credential deletion verification failed for owner: "
                        + ownerPluginId);
            }
            return;
        }
        byte[] content = Files.readAllBytes(path);
        try {
            PluginCredentialCipher.Decoded verified = cipher.decode(ownerPluginId, content);
            if (verified.rewriteWithCurrentKey()
                    || !expected.equals(withoutBlankValues(verified.values()))) {
                throw new IOException("Plugin credential verification failed for owner: " + ownerPluginId);
            }
        } finally {
            Arrays.fill(content, (byte) 0);
        }
    }

    private static Path credentialPath(String ownerPluginId) throws IOException {
        Path resolved = RuntimeFiles.resolvePluginCredentialPath(ownerPluginId)
                .toAbsolutePath()
                .normalize();
        Path parent = resolved.getParent();
        if (parent == null) {
            throw new IOException("Plugin credential path has no parent directory");
        }
        return parent.toRealPath().resolve(resolved.getFileName()).normalize();
    }

    private static Object processLock(Path credentialPath) {
        return PROCESS_LOCKS.computeIfAbsent(credentialPath, ignored -> new Object());
    }

    private static void runWithLocks(List<Path> paths, int index, IoOperation operation)
            throws IOException {
        if (index >= paths.size()) {
            operation.run();
            return;
        }
        synchronized (processLock(paths.get(index))) {
            runWithLocks(paths, index + 1, operation);
        }
    }

    private static Map<String, String> validatedUpdates(Map<String, String> updates) throws IOException {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (!key.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                throw new IOException("Invalid plugin credential key");
            }
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (value.indexOf('\0') >= 0) {
                throw new IOException(
                        "Plugin credential contains an unsupported NUL character for key: " + key);
            }
            if (result.putIfAbsent(key, value) != null) {
                throw new IOException("Duplicate normalized plugin credential key: " + key);
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> withoutBlankValues(Map<String, String> values) {
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                normalized.put(key, value);
            }
        });
        return Map.copyOf(normalized);
    }

    private static void restoreOriginal(
            Path path, boolean existed, byte[] content, Exception failure) {
        try {
            if (existed) {
                writeBytesAtomically(path, content);
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
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

    @FunctionalInterface
    public interface IoOperation {

        void run() throws IOException;
    }
}
