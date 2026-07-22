package top.sywyar.pixivdownload.plugin.runtime.install.transaction;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程级插件安装根租约。从启动恢复开始持有到 bootstrap session 关闭，防止另一实例在本进程的
 * prepare/commit/activate 窗口中恢复或覆盖同一安装根。
 */
public final class PluginDirectorySessionLock implements AutoCloseable {

    static final String LOCK_FILE_NAME = ".pixivdownload-runtime.lock";

    private static final ConcurrentMap<Path, PluginDirectorySessionLock> JVM_OWNERS = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_IDENTITY_POSITION = new AtomicLong(1L);

    private final Path pluginsRoot;
    private final Path lockPath;
    private final Runnable acquiredLocksProbe;
    private final long identityPosition = NEXT_IDENTITY_POSITION.getAndIncrement();
    private FileChannel channel;
    private FileLock lock;
    private FileLock identityLock;
    private RootIdentity rootIdentity;
    private boolean closed;

    public PluginDirectorySessionLock(Path pluginsRoot) {
        this(pluginsRoot, () -> { });
    }

    PluginDirectorySessionLock(Path pluginsRoot, Runnable acquiredLocksProbe) {
        this.pluginsRoot = Objects.requireNonNull(pluginsRoot, "pluginsRoot").toAbsolutePath().normalize();
        this.lockPath = this.pluginsRoot.resolve(LOCK_FILE_NAME);
        this.acquiredLocksProbe = Objects.requireNonNull(acquiredLocksProbe, "acquiredLocksProbe");
    }

    /** 安装根不存在时保持无副作用；存在时取得并持续持有独占锁。 */
    public synchronized boolean acquireIfRootExists() throws IOException {
        return acquire(false);
    }

    /** 安装入口可按需创建安装根，但必须在任何事务文件写入前取得独占锁。 */
    public synchronized void acquireForMutation() throws IOException {
        acquire(true);
    }

    public synchronized boolean held() {
        return lock != null && lock.isValid() && identityLock != null && identityLock.isValid();
    }

    public Path lockPath() {
        return lockPath;
    }

    private boolean acquire(boolean createRoot) throws IOException {
        if (closed) {
            throw new IOException("plugin directory session lock is closed");
        }
        if (held()) {
            if (JVM_OWNERS.get(pluginsRoot) != this) {
                throw new IOException("plugin directory JVM ownership changed while its lease was held");
            }
            if (!ensurePlainDirectoryChain(false)) {
                throw new IOException("plugins root disappeared while its session lock was held");
            }
            verifyRootIdentity();
            BasicFileAttributes currentAttributes = attributesIfPresent(lockPath);
            if (currentAttributes == null || currentAttributes.isSymbolicLink()
                    || currentAttributes.isOther() || !currentAttributes.isRegularFile()) {
                throw new IOException("plugin directory lock path changed while its lease was held: " + lockPath);
            }
            verifyPathStillNamesLockedFile();
            return true;
        }

        if (!ensurePlainDirectoryChain(createRoot)) {
            return false;
        }

        PluginDirectorySessionLock existingOwner = JVM_OWNERS.putIfAbsent(pluginsRoot, this);
        if (existingOwner != null && existingOwner != this) {
            throw new IOException("plugins root is already owned by another session in this process: "
                    + pluginsRoot);
        }

        BasicFileAttributes lockAttributes = attributesIfPresent(lockPath);
        if (lockAttributes != null && (lockAttributes.isSymbolicLink() || lockAttributes.isOther()
                || !lockAttributes.isRegularFile())) {
            throw new IOException("plugin directory lock path is unsafe: " + lockPath);
        }
        Set<OpenOption> options = Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        FileChannel candidateChannel;
        try {
            candidateChannel = FileChannel.open(lockPath, options);
        } catch (IOException | RuntimeException e) {
            JVM_OWNERS.remove(pluginsRoot, this);
            throw e;
        }
        FileLock candidateLock = null;
        FileLock candidateIdentityLock = null;
        try {
            BasicFileAttributes openedAttributes = attributesIfPresent(lockPath);
            if (openedAttributes == null || openedAttributes.isSymbolicLink() || openedAttributes.isOther()
                    || !openedAttributes.isRegularFile()) {
                throw new IOException("plugin directory lock path changed while opening: " + lockPath);
            }
            try {
                candidateLock = candidateChannel.tryLock(0L, 1L, false);
            } catch (OverlappingFileLockException e) {
                throw new IOException("plugins root is already owned by this process: " + pluginsRoot, e);
            }
            if (candidateLock == null) {
                throw new IOException("plugins root is already owned by another process: " + pluginsRoot);
            }
            try {
                candidateIdentityLock = candidateChannel.tryLock(identityPosition, 1L, false);
            } catch (OverlappingFileLockException e) {
                throw new IOException("plugin directory identity range is already owned in this process", e);
            }
            if (candidateIdentityLock == null) {
                throw new IOException("plugin directory identity range is already owned by another process");
            }
            acquiredLocksProbe.run();
            if (!ensurePlainDirectoryChain(false)) {
                throw new IOException("plugins root disappeared while acquiring its session lock");
            }
            BasicFileAttributes verifiedAttributes = attributesIfPresent(lockPath);
            if (verifiedAttributes == null || verifiedAttributes.isSymbolicLink()
                    || verifiedAttributes.isOther() || !verifiedAttributes.isRegularFile()) {
                throw new IOException("plugin directory lock path changed after locking: " + lockPath);
            }
            Object openedKey = openedAttributes.fileKey();
            Object verifiedKey = verifiedAttributes.fileKey();
            if (openedKey != null && verifiedKey != null && !openedKey.equals(verifiedKey)) {
                throw new IOException("plugin directory lock identity changed after opening: " + lockPath);
            }
            verifyPathStillNamesLockedFile();
            RootIdentity acquiredRootIdentity = readRootIdentity();
            channel = candidateChannel;
            lock = candidateLock;
            identityLock = candidateIdentityLock;
            rootIdentity = acquiredRootIdentity;
            return true;
        } catch (Throwable e) {
            if (candidateIdentityLock != null) {
                try {
                    candidateIdentityLock.release();
                } catch (Throwable releaseFailure) {
                    addSuppressedSafely(e, releaseFailure);
                }
            }
            if (candidateLock != null) {
                try {
                    candidateLock.release();
                } catch (Throwable releaseFailure) {
                    addSuppressedSafely(e, releaseFailure);
                }
            }
            try {
                candidateChannel.close();
            } catch (Throwable closeFailure) {
                addSuppressedSafely(e, closeFailure);
            }
            try {
                JVM_OWNERS.remove(pluginsRoot, this);
            } catch (Throwable releaseFailure) {
                addSuppressedSafely(e, releaseFailure);
            }
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (e instanceof Error error) {
                throw error;
            }
            throw new IOException("unexpected plugin directory session lock acquisition failure", e);
        }
    }

    /** 从文件系统根逐组件 NOFOLLOW 校验；创建时也只逐层 CREATE_NEW 目录，不跟随中间 junction。 */
    private boolean ensurePlainDirectoryChain(boolean createMissing) throws IOException {
        Path current = pluginsRoot.getRoot();
        if (current == null) {
            throw new IOException("plugins root must be absolute: " + pluginsRoot);
        }
        requirePlainDirectory(current);
        for (Path component : pluginsRoot) {
            current = current.resolve(component.toString());
            BasicFileAttributes attributes = attributesIfPresent(current);
            if (attributes == null) {
                if (!createMissing) {
                    return false;
                }
                Files.createDirectory(current);
                attributes = attributesIfPresent(current);
            }
            if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                    || !attributes.isDirectory()) {
                throw new IOException("plugin directory path component is not a plain directory: " + current);
            }
        }
        return true;
    }

    private static void requirePlainDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = attributesIfPresent(directory);
        if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                || !attributes.isDirectory()) {
            throw new IOException("plugin directory filesystem root is not a plain directory: " + directory);
        }
    }

    private RootIdentity readRootIdentity() throws IOException {
        BasicFileAttributes attributes = attributesIfPresent(pluginsRoot);
        if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                || !attributes.isDirectory()) {
            throw new IOException("plugins root is not a plain directory: " + pluginsRoot);
        }
        return new RootIdentity(attributes.fileKey(), attributes.creationTime());
    }

    private void verifyRootIdentity() throws IOException {
        RootIdentity current = readRootIdentity();
        if (rootIdentity == null || !rootIdentity.equals(current)) {
            throw new IOException("plugins root identity changed while its session lock was held: " + pluginsRoot);
        }
    }

    /**
     * 不写入锁文件也能复核路径身份：从当前路径打开第二个 channel；若它仍指向已锁定文件，JVM 必须以
     * {@link OverlappingFileLockException} 拒绝重叠锁。若竟取得锁或只观察到别的进程锁，说明路径已换绑，
     * 不能把旧 inode 上的 lease 当作当前插件根的互斥证明。
     */
    private void verifyPathStillNamesLockedFile() throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try (FileChannel probe = FileChannel.open(lockPath, options)) {
            FileLock unexpected = null;
            try {
                unexpected = probe.tryLock(identityPosition, 1L, false);
            } catch (OverlappingFileLockException expected) {
                return;
            }
            if (unexpected != null) {
                unexpected.release();
            }
            throw new IOException("plugin directory lock path no longer names the locked file: " + lockPath);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        if (identityLock != null) {
            try {
                identityLock.release();
            } catch (IOException e) {
                failure = e;
            }
        }
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        JVM_OWNERS.remove(pluginsRoot, this);
        identityLock = null;
        lock = null;
        channel = null;
        rootIdentity = null;
        if (failure != null) {
            throw failure;
        }
    }

    private static BasicFileAttributes attributesIfPresent(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    private static void addSuppressedSafely(Throwable primary, Throwable suppressed) {
        if (primary == null || suppressed == null || primary == suppressed) {
            return;
        }
        try {
            primary.addSuppressed(suppressed);
        } catch (Throwable ignored) {
            // 保留原始失败对象身份。
        }
    }

    /** Windows 默认 provider 的 fileKey 可能为空；创建时间只作为该平台上的换绑检测退路。 */
    private record RootIdentity(Object fileKey, FileTime creationTime) {

        private RootIdentity {
            creationTime = Objects.requireNonNull(creationTime, "creationTime");
        }
    }
}
