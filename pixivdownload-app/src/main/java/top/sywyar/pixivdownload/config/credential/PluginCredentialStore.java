package top.sywyar.pixivdownload.config.credential;

import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.config.RuntimeFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final PluginCredentialCipher cipher;

    public PluginCredentialStore() {
        this.cipher = null;
    }

    PluginCredentialStore(PluginCredentialKeyMaterial keyMaterial) {
        this(new PluginCredentialCipher(keyMaterial));
    }

    PluginCredentialStore(PluginCredentialCipher cipher) {
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    /** 普通读取先完成旧信封迁移；只读归档入口单独保留无副作用语义。 */
    public Map<String, String> readAll(String ownerPluginId) throws IOException {
        if (cipher == null) {
            Path existing = RuntimeFiles.peekPluginCredentialPath(ownerPluginId);
            top.sywyar.pixivdownload.plugin.api.database.migration.MigrationFiles.requirePlain(existing, false);
            if (!Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return Map.of();
            return writableStore(true).readAll(ownerPluginId);
        }
        Path path = credentialPath(ownerPluginId);
        synchronized (processLock(path)) {
            return readAllLocked(ownerPluginId, path);
        }
    }

    /** 迁移读取不创建目录、不升级信封；归档加密由调用方负责。 */
    public Map<String, String> readForTransfer(String ownerPluginId) throws IOException {
        Path path = RuntimeFiles.peekPluginCredentialPath(ownerPluginId).toAbsolutePath().normalize();
        top.sywyar.pixivdownload.plugin.api.database.migration.MigrationFiles.requirePlain(path, false);
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return Map.of();
        try (var input = Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(8 * 1024 * 1024 + 1);
            try {
                if (bytes.length > 8 * 1024 * 1024) throw new IOException("transfer.archive-limit");
                PluginCredentialCipher reader = cipher == null ? InstallationCredentialKeys.forRead(bytes) : cipher;
                return withoutBlankValues(reader.decode(ownerPluginId, bytes).values());
            } finally { Arrays.fill(bytes, (byte) 0); }
        }
    }

    /** 按目标环境密钥生成合并后的信封，尚不改变正在使用的凭据。 */
    public byte[] prepareTransfer(String ownerPluginId, Map<String, String> updates) throws IOException {
        Map<String, String> merged = new LinkedHashMap<>(readForTransfer(ownerPluginId));
        for (var entry : validatedUpdates(updates).entrySet()) {
            if (entry.getValue().isBlank()) throw new IOException("transfer.credential-empty");
            merged.put(entry.getKey(), entry.getValue());
        }
        PluginCredentialCipher writer = cipher == null ? InstallationCredentialKeys.forWrite(false) : cipher;
        return writer.encrypt(ownerPluginId, merged);
    }

    private Map<String, String> readAllLocked(String ownerPluginId, Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        byte[] content = InstallationCredentialKeys.readBounded(path, InstallationCredentialKeys.MAX_CREDENTIAL_BYTES);
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
        if (cipher == null) {
            writableStore(true).update(ownerPluginId, safeUpdates);
            return;
        }
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
        if (cipher == null && Files.exists(RuntimeFiles.pluginCredentialKeyPath())) {
            writableStore(true).withOwnerLocks(ownerPluginIds, operation);
            return;
        }
        LinkedHashSet<Path> uniquePaths = new LinkedHashSet<>();
        for (String ownerPluginId : ownerPluginIds) {
            uniquePaths.add(credentialPath(ownerPluginId));
        }
        List<Path> orderedPaths = new ArrayList<>(uniquePaths);
        orderedPaths.sort(Comparator.comparing(Path::toString));
        runWithLocks(orderedPaths, 0, operation);
    }

    public Snapshot snapshot(String ownerPluginId) throws IOException {
        if (cipher == null && Files.exists(RuntimeFiles.peekPluginCredentialPath(ownerPluginId))) {
            return writableStore(true).snapshot(ownerPluginId);
        }
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
        top.sywyar.pixivdownload.plugin.api.database.migration.MigrationFiles.requirePlain(
                RuntimeFiles.peekPluginCredentialPath(ownerPluginId), false);
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

    private static PluginCredentialStore writableStore(boolean migrate) throws IOException {
        return new PluginCredentialStore(InstallationCredentialKeys.forWrite(migrate));
    }

    static void writeBytesAtomically(Path path, byte[] content) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        Path parent = target.getParent();
        top.sywyar.pixivdownload.common.PlainFilePathGuard.requirePlainParent(target, true);
        top.sywyar.pixivdownload.plugin.api.database.migration.MigrationFiles.requirePlain(target, false);
        top.sywyar.pixivdownload.plugin.api.database.migration.MigrationFiles.makePrivate(parent);
        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            top.sywyar.pixivdownload.plugin.api.database.migration.MigrationFiles.makePrivate(temp);
            try (var output = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                var buffer = java.nio.ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
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
