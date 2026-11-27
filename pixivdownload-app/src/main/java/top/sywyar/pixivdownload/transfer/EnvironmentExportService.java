package top.sywyar.pixivdownload.transfer;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

import org.springframework.context.ConfigurableApplicationContext;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.provider.DatabaseBootstrap;
import top.sywyar.pixivdownload.gui.BackendLifecycleManager;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationFiles;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationValues;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.transfer.archive.TransferArchive;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** 进程持有导出工作者；关闭 Spring 后仍等待真实导出结束，再请求完整重启。 */
public final class EnvironmentExportService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(EnvironmentExportService.class);
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private static final tools.jackson.databind.ObjectMapper JSON = top.sywyar.pixivdownload.migration.database.MigrationSchemaJson.mapper();
    private final ConfigurableApplicationContext context;
    private final String[] arguments;
    private final Function<PluginBootstrapSession, EnvironmentTransferService> factory;
    private final BooleanSupplier restart;

    public EnvironmentExportService(ConfigurableApplicationContext context, String[] arguments,
                                    Function<PluginBootstrapSession, EnvironmentTransferService> factory, BooleanSupplier restart) {
        this.context = context; this.arguments = arguments.clone(); this.factory = factory; this.restart = restart;
    }

    public enum State { ACCEPTED, STOPPING, EXPORTING, SUCCEEDED, FAILED, INTERRUPTED }
    public record Request(String destination, char[] password, boolean includeCredentials, boolean includeDatabases,
                          boolean includeState, boolean includeResources, boolean confirmRestart) {
        @Override public String toString() { return "Request[redacted]"; }
    }
    public record View(UUID id, State state, String failureCode, long bytes, String sha256, @HttpApiNullable String downloadUrl) {}
    private record Job(UUID id, State state, long requester, String destination, String failureCode, long bytes, String sha256) {
        View view() { return new View(id, state, failureCode, bytes, sha256,
                state == State.SUCCEEDED ? "/api/settings/environment/exports/" + id + "/archive" : null); }
        Job state(State next, String failure) { return new Job(id, next, requester, destination, failure, bytes, sha256); }
    }

    public View start(Request request) throws IOException {
        if (request == null) throw new IllegalArgumentException();
        try {
            if (!request.confirmRestart() || request.destination() == null || request.destination().length() > 4096
                    || request.destination().chars().anyMatch(Character::isISOControl)
                    || request.password() != null && request.password().length > 4096) throw new IllegalArgumentException();
            Path destination = Path.of(request.destination());
            if (!destination.isAbsolute()) throw new IllegalArgumentException();
            PlainFilePathGuard.requirePlainParent(destination, false);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new IOException("transfer.target-changed");
            if ((request.includeCredentials() || request.includeDatabases() || request.includeState())
                    && (request.password() == null || request.password().length == 0)) throw new IOException("transfer.password-required");
            if (request.includeResources() && !request.includeDatabases()) throw new IOException("transfer.files-require-database");
            if (!ACTIVE.compareAndSet(false, true)) throw new IOException("transfer.busy");
            char[] password = request.password() == null ? null : request.password().clone();
            try {
                var job = new Job(UUID.randomUUID(), State.ACCEPTED, ProcessHandle.current().pid(), destination.normalize().toString(), "", 0, "");
                try (var lock = OfflineRestoreLock.acquire()) {
                    EnvironmentTransferService.requireIdle();
                    write(job);
                }
                Thread.ofPlatform().name("environment-export").daemon(false).start(() -> run(job, request, password));
                return job.view();
            } catch (IOException | RuntimeException | Error failure) {
                clear(password); ACTIVE.set(false); throw failure;
            }
        } finally { clear(request.password()); }
    }

    private void run(Job initial, Request request, char[] password) {
        Job job = initial;
        boolean stopped = false;
        LOG.info("Environment export started executionId={}", job.id());
        try (var lock = OfflineRestoreLock.acquire(); var reservation = BackendLifecycleManager.suspendBackendStarts()) {
            EnvironmentTransferService.requireIdle();
            write(job = job.state(State.STOPPING, ""));
            stopBackend(context);
            stopped = true;
            write(job = job.state(State.EXPORTING, ""));
            TransferArchive.VerifiedArchive published;
            try (var session = DatabaseBootstrap.openToolSession(DatabaseTarget.configurationEnvironment(arguments))) {
                published = factory.apply(session).exportStopped(Path.of(job.destination()), password, request.includeCredentials(),
                        request.includeDatabases(), request.includeState(), request.includeResources());
            }
            job = new Job(job.id(), State.SUCCEEDED, job.requester(), job.destination(), "",
                    published.bytes(), published.preview().archiveSha256());
            write(job);
            LOG.info("Environment export completed executionId={} bytes={}", job.id(), job.bytes());
        } catch (Exception failure) {
            String code = failure.getMessage();
            if (code == null || !code.matches("transfer\\.[a-z0-9-]{1,80}")) code = "transfer.failed";
            job = job.state(State.FAILED, code);
            try { write(job); }
            catch (IOException writeFailure) { LOG.error("Environment export outcome could not be persisted executionId={}", job.id()); }
            LOG.warn("Environment export failed executionId={} code={} type={}", job.id(), code, failure.getClass().getSimpleName());
        } finally {
            clear(password);
            if (!stopped) ACTIVE.set(false);
            boolean restarted = false;
            if (stopped) {
                try { restarted = restart.getAsBoolean(); }
                catch (RuntimeException failure) {
                    LOG.error("Environment export restart request failed executionId={} type={}", job.id(), failure.getClass().getSimpleName());
                }
            }
            if (stopped && !restarted) {
                LOG.error("Environment export restart failed executionId={}", job.id());
                synchronized (EnvironmentExportService.class) {
                    try { write(job.state(job.state() == State.SUCCEEDED ? State.SUCCEEDED : State.FAILED, "transfer.restart-failed")); }
                    catch (IOException failure) { LOG.error("Environment export restart failure could not be persisted executionId={} type={}", job.id(), failure.getClass().getSimpleName()); }
                    finally { ACTIVE.set(false); }
                }
            }
        }
    }

    static void stopBackend(ConfigurableApplicationContext context) throws IOException {
        if (BackendLifecycleManager.owns(context)) {
            var result = BackendLifecycleManager.closeBackendContext(30_000).completion().toCompletableFuture().join();
            if (!result.isContextConfirmedClosed()) throw new IOException("transfer.backend-not-stopped");
        } else {
            if (BackendLifecycleManager.state() != BackendLifecycleManager.State.STOPPED) throw new IOException("transfer.busy");
            try {
                ExternalPluginLifecycleCoordinator.stopBeforeContextClose(context);
                context.close();
            } catch (RuntimeException failure) {
                throw new IOException("transfer.backend-not-stopped", failure);
            }
            if (context.isActive()) throw new IOException("transfer.backend-not-stopped");
        }
    }

    public View current() throws IOException {
        Job job = read();
        if (job == null) return null;
        if (!Set.of(State.SUCCEEDED, State.FAILED, State.INTERRUPTED).contains(job.state())
                && (job.requester() != ProcessHandle.current().pid() || !ACTIVE.get()))
            return job.state(State.INTERRUPTED, "transfer.cancelled").view();
        return job.view();
    }

    public InputStream archive(UUID id) throws IOException {
        Job job = read();
        if (job == null || !job.id().equals(id) || job.state() != State.SUCCEEDED) throw new IOException("transfer.preview-changed");
        Snapshot snapshot;
        InputStream reader;
        synchronized (Snapshot.class) {
            if (activeSnapshot != null) {
                if (!activeSnapshot.job.id().equals(id)) throw new IOException("transfer.busy");
                if (activeSnapshot.job.bytes() != job.bytes() || !activeSnapshot.job.sha256().equals(job.sha256()))
                    throw new IOException("transfer.target-changed");
            } else {
                var channel = AsynchronousFileChannel.open(journal().resolveSibling(".download-" + UUID.randomUUID() + ".part"),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE,
                        StandardOpenOption.DELETE_ON_CLOSE, LinkOption.NOFOLLOW_LINKS);
                activeSnapshot = new Snapshot(job, channel);
            }
            snapshot = activeSnapshot;
            reader = snapshot.openReader();
        }
        try {
            snapshot.verify(job);
            return reader;
        } catch (Throwable failure) {
            try { reader.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static void verifyDestination(Job job, AsynchronousFileChannel output) throws IOException {
        var digest = MigrationValues.newDigest();
        try (var input = openDestination(Path.of(job.destination()))) {
            byte[] buffer = new byte[64 * 1024];
            long remaining = job.bytes();
            for (int n; (n = input.read(buffer)) != -1;) {
                if (n > remaining) throw new IOException("transfer.target-changed");
                if (output != null) {
                    var chunk = ByteBuffer.wrap(buffer, 0, n);
                    long offset = job.bytes() - remaining;
                    while (chunk.hasRemaining()) awaitIO(output.write(chunk, offset + chunk.position()));
                }
                remaining -= n;
                digest.update(buffer, 0, n);
            }
            if (remaining != 0 || !MigrationValues.finish(digest).equals(job.sha256()))
                throw new IOException("transfer.target-changed");
        }
    }

    // A submitted I/O owns its buffer until completion. An interrupted waiter never cancels the shared handle.
    private static int awaitIO(Future<Integer> operation) throws IOException {
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    int count = operation.get();
                    if (interrupted) throw new InterruptedIOException();
                    return count;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                } catch (ExecutionException failed) {
                    if (failed.getCause() instanceof IOException io) throw io;
                    throw new IOException(failed.getCause());
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static Snapshot activeSnapshot;

    private static final class Snapshot {
        final Job job;
        final AsynchronousFileChannel channel;
        int readers;
        private boolean populated;

        Snapshot(Job job, AsynchronousFileChannel channel) {
            this.job = job;
            this.channel = channel;
        }

        // Serialise disk scans without holding the lock used to close a response.
        synchronized void verify(Job request) throws IOException {
            verifyDestination(request, populated ? null : channel);
            populated = true;
        }

        InputStream openReader() {
            readers++;
            // Each response has an independent offset. The last response closes the delete-on-close handle.
            return Channels.newInputStream(new ReadableByteChannel() {
                private long position;
                private volatile boolean open = true;

                @Override public boolean isOpen() { return open; }

                @Override public int read(ByteBuffer target) throws IOException {
                    if (!open) throw new ClosedChannelException();
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    int count = awaitIO(channel.read(target, position));
                    if (count > 0) position += count;
                    return count;
                }

                @Override public void close() throws IOException {
                    synchronized (Snapshot.class) {
                        if (!open) return;
                        open = false;
                        if (--readers == 0) {
                            activeSnapshot = null;
                            channel.close();
                        }
                    }
                }
            });
        }
    }

    private static InputStream openDestination(Path destination) throws IOException {
        try {
            Path parent = destination.getParent();
            if (parent == null || parent.getParent() != null && !PlainFilePathGuard.requireDirectoryOpenPrecondition(parent).ready())
                throw new IOException("transfer.target-changed");
            var attributes = Files.readAttributes(destination, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isOther()) throw new IOException("transfer.target-changed");
            return Files.newInputStream(destination, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            throw new IOException("transfer.target-changed", missing);
        }
    }

    private static Path journal() { return RuntimeFiles.environmentTransferDirectory().resolve("api-export.json").toAbsolutePath().normalize(); }
    // Windows 上查询持有的文件句柄不能与本进程的原子替换交错。
    private static synchronized Job read() throws IOException {
        Path file = journal();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        PlainFilePathGuard.requirePlainRegularFile(file);
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(16 * 1024 + 1);
            if (bytes.length > 16 * 1024) throw new IOException("transfer.restore-limit");
            try {
                Job job = JSON.readValue(bytes, Job.class);
                if (job == null || job.id() == null || job.state() == null || job.destination() == null
                        || !Path.of(job.destination()).isAbsolute() || job.destination().length() > 4096
                        || job.bytes() < 0 || job.sha256() == null || job.failureCode() == null)
                    throw new IllegalArgumentException();
                return job;
            } catch (RuntimeException invalid) { throw new IOException("transfer.restore-invalid"); }
        }
    }
    private static synchronized void write(Job job) throws IOException {
        Path target = journal();
        PlainFilePathGuard.requirePlainParent(target, true);
        MigrationFiles.makePrivate(target.getParent());
        byte[] bytes = JSON.writeValueAsBytes(job);
        if (bytes.length > 16 * 1024) throw new IOException("transfer.restore-limit");
        Path temporary = Files.createTempFile(target.getParent(), ".api-export-", ".part");
        try {
            MigrationFiles.makePrivate(temporary);
            Files.write(temporary, bytes, LinkOption.NOFOLLOW_LINKS);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void clear(char[] value) { if (value != null) Arrays.fill(value, '\0'); }
}
