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
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
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
            try (var session = DatabaseBootstrap.openToolSession(DatabaseTarget.configurationEnvironment(arguments))) {
                factory.apply(session).exportStopped(Path.of(job.destination()), password, request.includeCredentials(),
                        request.includeDatabases(), request.includeState(), request.includeResources());
            }
            Path destination = Path.of(job.destination());
            job = new Job(job.id(), State.SUCCEEDED, job.requester(), job.destination(), "", Files.size(destination), MigrationFiles.digest(destination));
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
                try { write(job.state(job.state() == State.SUCCEEDED ? State.SUCCEEDED : State.FAILED, "transfer.restart-failed")); }
                catch (IOException failure) { LOG.error("Environment export restart failure could not be persisted executionId={} type={}", job.id(), failure.getClass().getSimpleName()); }
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

    public Path archive(UUID id) throws IOException {
        Job job = read();
        if (job == null || !job.id().equals(id) || job.state() != State.SUCCEEDED) throw new IOException("transfer.preview-changed");
        Path destination = Path.of(job.destination());
        PlainFilePathGuard.requirePlainRegularFile(destination);
        if (Files.size(destination) != job.bytes() || !MigrationFiles.digest(destination).equals(job.sha256()))
            throw new IOException("transfer.target-changed");
        return destination;
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
