package top.sywyar.pixivdownload.config.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationFiles;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Arrays;

/** 先保存可解锁的安装密钥，再逐文件迁移；完成标记与密钥一起认证保护。 */
final class InstallationCredentialKeys {
    static final int MAX_CREDENTIAL_BYTES = 8 * 1024 * 1024;
    private static final Logger LOG = LoggerFactory.getLogger(InstallationCredentialKeys.class);

    private InstallationCredentialKeys() {}

    static synchronized PluginCredentialCipher forWrite(boolean migrate) throws IOException {
        Path keyPath = RuntimeFiles.pluginCredentialKeyPath().toAbsolutePath().normalize();
        Path lockPath = RuntimeFiles.pluginCredentialKeyLockPath().toAbsolutePath().normalize();
        PlainFilePathGuard.requirePlainParent(keyPath, true);
        MigrationFiles.requirePlain(keyPath, false);
        MigrationFiles.requirePlain(lockPath, false);
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            MigrationFiles.makePrivate(lockPath);
            try (var lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Credential key initialization is already in progress");
                try (KeyState state = loadOrCreate(keyPath)) {
                    PluginCredentialCipher current = state.cipher();
                    if (migrate && state.pending()) migrate(keyPath, state, current);
                    return current;
                }
            } catch (OverlappingFileLockException busy) {
                throw new IOException("Credential key initialization is already in progress");
            }
        }
    }

    /** 归档预览不创建密钥、不写入迁移状态，也不修改凭据文件。 */
    static PluginCredentialCipher forRead(byte[] content) throws IOException {
        Path keyPath = RuntimeFiles.pluginCredentialKeyPath();
        try (KeyState state = readKey(keyPath)) {
            if (PluginCredentialCipher.isLegacy(content)) {
                if (state != null && !state.pending()) throw new IOException("Legacy plugin credential downgrade rejected");
                return legacyCipher();
            }
            if (state == null) throw new IOException("Installation credential key is missing; restore an encrypted environment archive");
            return state.cipher();
        }
    }

    private static KeyState loadOrCreate(Path keyPath) throws IOException {
        KeyState existing = readKey(keyPath);
        if (existing != null) return existing;
        // 存在新格式凭据时绝不重建密钥，否则会掩盖密钥丢失并破坏恢复。
        visitCredentials((owner, path, content) -> {
            if (!PluginCredentialCipher.isLegacy(content))
                throw new IOException("Installation credential key is missing; existing credentials were preserved");
        }, false);
        byte mode = CredentialKeyProtection.mode();
        byte[] root = new byte[32];
        new SecureRandom().nextBytes(root);
        var state = new KeyState(mode, true, root);
        try {
            writeKey(keyPath, state, true);
            return state;
        } catch (IOException | RuntimeException failure) {
            state.close();
            throw failure;
        }
    }

    private static void migrate(Path keyPath, KeyState state, PluginCredentialCipher current) throws IOException {
        int[] converted = {0};
        LOG.info("Plugin credential migration started");
        try {
            int failed = visitCredentials((owner, path, original) -> {
                if (!PluginCredentialCipher.isLegacy(original)) {
                    current.decode(owner, original);
                    return;
                }
                var values = legacyCipher().decode(owner, original).values();
                byte[] encrypted = current.encrypt(owner, values);
                try {
                    if (!current.decode(owner, encrypted).values().equals(values))
                        throw new IOException("Credential migration verification failed");
                    PluginCredentialStore.writeBytesAtomically(path, encrypted);
                    byte[] saved = readBounded(path, MAX_CREDENTIAL_BYTES);
                    try {
                        if (!current.decode(owner, saved).values().equals(values))
                            throw new IOException("Credential migration verification failed");
                    } finally { Arrays.fill(saved, (byte) 0); }
                    converted[0]++;
                } catch (IOException | RuntimeException failure) {
                    try { PluginCredentialStore.writeBytesAtomically(path, original); }
                    catch (IOException restoreFailure) { failure.addSuppressed(restoreFailure); }
                    throw failure;
                } finally { Arrays.fill(encrypted, (byte) 0); }
            }, true);
            if (failed != 0) {
                LOG.warn("Plugin credential migration incomplete: converted={}, failed={}; migration remains pending",
                        converted[0], failed);
                return;
            }
            writeKey(keyPath, state, false);
            LOG.info("Plugin credential migration completed: converted={}", converted[0]);
        } catch (IOException | RuntimeException failure) {
            LOG.warn("Plugin credential migration incomplete: converted={}, reason={}",
                    converted[0], failure.getClass().getSimpleName());
            throw failure;
        }
    }

    private static PluginCredentialCipher legacyCipher() throws IOException {
        try { return PluginCredentialCipher.legacy(PluginCredentialKeyMaterial.loadLegacy()); }
        catch (IllegalStateException failure) { throw new IOException("Legacy credential migration key is unavailable", failure); }
    }

    private static KeyState readKey(Path path) throws IOException {
        MigrationFiles.requirePlain(path, false);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        byte[] content = readBounded(path, CredentialKeyProtection.MAX_WRAPPED_BYTES);
        byte[] payload = null;
        try {
            byte mode = CredentialKeyProtection.storedMode(content);
            payload = CredentialKeyProtection.unprotect(content);
            if (payload.length != 33 || payload[0] != 0 && payload[0] != 1)
                throw new IOException("Invalid installation credential key payload");
            return new KeyState(mode, payload[0] == 1, Arrays.copyOfRange(payload, 1, payload.length));
        } finally {
            Arrays.fill(content, (byte) 0);
            if (payload != null) Arrays.fill(payload, (byte) 0);
        }
    }

    private static void writeKey(Path path, KeyState state, boolean pending) throws IOException {
        byte[] payload = ByteBuffer.allocate(33).put((byte) (pending ? 1 : 0)).put(state.root()).array();
        byte[] protectedBytes = null;
        Path temporary = null;
        try {
            protectedBytes = CredentialKeyProtection.protect(payload, state.mode());
            byte[] verified = CredentialKeyProtection.unprotect(protectedBytes);
            try {
                if (!Arrays.equals(payload, verified)) throw new IOException("Installation credential key verification failed");
            } finally { Arrays.fill(verified, (byte) 0); }
            MigrationFiles.requirePlain(path, false);
            temporary = Files.createTempFile(path.getParent(), ".credentials-key-", ".tmp");
            MigrationFiles.makePrivate(temporary);
            try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(protectedBytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            // 密钥发布必须原子化；不支持时保留原密钥与凭据并明确失败。
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (KeyState saved = readKey(path)) {
                if (saved == null || saved.pending() != pending || !Arrays.equals(state.root(), saved.root()))
                    throw new IOException("Installation credential key verification failed");
            }
        } finally {
            Arrays.fill(payload, (byte) 0);
            if (protectedBytes != null) Arrays.fill(protectedBytes, (byte) 0);
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }

    private static int visitCredentials(CredentialVisitor visitor, boolean isolateFailures) throws IOException {
        Path directory = RuntimeFiles.configDirectory().resolve(RuntimeFiles.PLUGIN_CREDENTIAL_DIR);
        MigrationFiles.requirePlain(directory, true);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return 0;
        int failed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.properties")) {
            for (Path path : files) {
                String filename = path.getFileName().toString();
                String owner = filename.substring(0, filename.length() - ".properties".length());
                try {
                    try { RuntimeFiles.peekPluginCredentialPath(owner); }
                    catch (IllegalArgumentException invalid) { throw new IOException("Invalid credential migration owner"); }
                    byte[] content = readBounded(path, MAX_CREDENTIAL_BYTES);
                    try { visitor.visit(owner, path, content); }
                    finally { Arrays.fill(content, (byte) 0); }
                } catch (IOException | RuntimeException failure) {
                    if (!isolateFailures) throw failure;
                    failed++;
                }
            }
        }
        return failed;
    }

    static byte[] readBounded(Path path, int maximum) throws IOException {
        MigrationFiles.requirePlain(path, false);
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length <= maximum) return bytes;
            Arrays.fill(bytes, (byte) 0);
            throw new IOException("Credential file exceeds its size limit");
        }
    }

    private record KeyState(byte mode, boolean pending, byte[] root) implements AutoCloseable {
        PluginCredentialCipher cipher() { return new PluginCredentialCipher(PluginCredentialKeyMaterial.installed(root)); }
        @Override public void close() { Arrays.fill(root, (byte) 0); }
    }

    @FunctionalInterface
    private interface CredentialVisitor {
        void visit(String owner, Path path, byte[] content) throws IOException;
    }
}
