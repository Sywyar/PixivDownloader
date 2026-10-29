package top.sywyar.pixivdownload.transfer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.config.credential.PluginCredentialStore;
import top.sywyar.pixivdownload.gui.config.DatabaseSwitchService;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationFiles;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.transfer.archive.TransferArchive;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 归档预览只读；确认后的导入在下一进程取得实例锁后、业务初始化前执行。 */
public final class EnvironmentTransferService implements DesktopEnvironmentTransfer {
    private static final ObjectMapper JSON = top.sywyar.pixivdownload.migration.database.MigrationSchemaJson.mapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final TransferArchive archive = new TransferArchive();
    private final ConfigurationTransfer configuration;
    private final DesktopUiHost host;
    private final BooleanSupplier restart;
    private final DatabaseTransfer databases;
    private final top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession session;
    private PendingPreview prepared;
    private final Supplier<List<DesktopUiPluginSnapshot>> plugins;

    @Override public Installation installation() { return top.sywyar.pixivdownload.update.WindowsInstallation.detect(); }

    private record PendingPreview(Path path, TransferArchive.Preview archive, ConfigurationTransfer.Preview config,
                                  DatabaseTransfer.Plan databases, EnvironmentFiles.Plan files, Preview display) {}
    record Job(int version, String id, Status status, String failureCode, long requester, List<String> targets,
               DatabaseTransfer.Plan databases, TransferArchive.Preview archive, EnvironmentFiles.Plan files) {
        Job {
            if (version != 1 || id == null || !UUID.fromString(id).toString().equals(id) || status == null || status == Status.NONE
                    || failureCode == null || failureCode.length() > 128 || requester < 1 || targets == null || targets.size() > 20_000)
                throw new IllegalArgumentException("transfer.restore-invalid");
            targets = List.copyOf(targets);
            if ((databases == null) != (archive == null)) throw new IllegalArgumentException("transfer.restore-invalid");
        }
        Job status(Status status, String failure) { return new Job(version, id, status, failure, requester, targets, databases, archive, files); }
    }

    public EnvironmentTransferService(DesktopUiHost host, Supplier<List<DesktopUiPluginSnapshot>> plugins, BooleanSupplier restart) {
        this(host, plugins, restart, null, new String[0]);
    }

    public EnvironmentTransferService(DesktopUiHost host, Supplier<List<DesktopUiPluginSnapshot>> plugins, BooleanSupplier restart,
            top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession session, String[] arguments) {
        this.host = host;
        this.plugins = plugins;
        this.configuration = new ConfigurationTransfer(host, plugins, new PluginCredentialStore(),
                new top.sywyar.pixivdownload.plugin.PluginRuntimeConfiguration().requiredPluginPolicy(
                        top.sywyar.pixivdownload.core.db.DatabaseTarget.configurationEnvironment(arguments)));
        this.restart = restart;
        this.databases = new DatabaseTransfer(host, session, arguments);
        this.session = session;
    }

    @Override public void exportConfiguration(Path destination, char[] password, boolean includeCredentials, boolean includeDatabases,
                                              boolean includeState, boolean includeResources) throws IOException {
        try (var ignored = OfflineRestoreLock.acquire()) {
            requireIdle();
            if ((includeCredentials || includeDatabases || includeState) && (password == null || password.length == 0)) throw new IOException("transfer.password-required");
            if (includeResources && !includeDatabases) throw new IOException("transfer.files-require-database");
            Path run = newRun("export-" + UUID.randomUUID());
            try (var pause = includeCredentials || includeDatabases || includeState || includeResources ? new TransferBackendPause() : null;
                 var snapshot = configuration.export(includeCredentials)) {
                var sources = new ArrayList<>(snapshot.sources());
                sources.add(TransferInventory.export(host, session, plugins.get()));
                if (includeDatabases) sources.addAll(databases.export(run));
                sources.addAll(new EnvironmentFiles(session).export(run.resolve("files"), includeCredentials,
                        includeDatabases, includeState, includeResources));
                archive.write(destination, password, host.applicationVersion(), sources);
            } catch (IOException failure) { throw failure; }
            catch (Exception failure) { throw new IOException("transfer.database-failed", failure); }
            finally { discardRun(run); }
        }
    }

    @Override public Preview previewConfiguration(Path path, char[] password) throws IOException {
        try (var ignored = OfflineRestoreLock.acquire()) {
            requireIdle();
            prepared = null;
            Path source = path.toAbsolutePath().normalize();
            var preview = archive.inspect(source, password);
            try (var contents = archive.readSelected(source, password, preview, indices(preview))) {
                var config = configuration.preview(preview, contents);
                var files = new EnvironmentFiles(session).preview(preview);
                var inventory = TransferInventory.read(preview, contents);
                DatabaseTransfer.Plan data = null;
                String databaseFailure = "";
                try { data = databases.preview(preview, contents); }
                catch (Exception unavailable) { databaseFailure = "transfer.database-unavailable"; }
                var display = new Preview(UUID.randomUUID().toString(), preview.manifest().applicationVersion(), preview.encrypted(),
                        config.changes().stream().map(change -> new Change(key(change.key()), change.sensitive(),
                                change.path(), change.before(), change.after())).toList(),
                        config.unavailable().stream().map(EnvironmentTransferService::key).collect(java.util.stream.Collectors.toSet()),
                        data == null ? List.of() : data.stores().stream().map(store -> new DatabaseStore(store.owner(), store.rows(),
                                !store.replacementRevision().isEmpty())).toList(), databaseFailure, fileGroups(files, preview), files.unavailable(),
                        data == null ? List.of() : data.paths().prefixes().entrySet().stream()
                                .map(entry -> new DatabasePath(entry.getKey(), entry.getValue())).toList(),
                        TransferInventory.preview(inventory, session, plugins.get()), inventory.repositories());
                prepared = new PendingPreview(source, preview, config, data, files, display);
                return display;
            }
        }
    }

    @Override public void importConfiguration(String token, Set<FieldKey> selected, Map<FieldKey, String> paths, char[] password,
                                              boolean includeDatabases, Set<FieldKey> fileGroups, Map<Long, String> databasePaths) throws IOException {
        try (var ignored = OfflineRestoreLock.acquire()) {
            requireIdle();
            PendingPreview pending = prepared;
            if (pending == null || !pending.display().token().equals(token)) throw new IOException("transfer.preview-changed");
            if (selected.isEmpty() && !includeDatabases && fileGroups.isEmpty()) throw new IOException("transfer.config-selection-invalid");
            if (includeDatabases && pending.databases() == null) throw new IOException("transfer.database-unavailable");
            if (!includeDatabases && !databasePaths.isEmpty()) throw new IOException("transfer.config-selection-invalid");
            Map<ConfigurationTransfer.Key, String> mapped = new LinkedHashMap<>();
            paths.forEach((key, value) -> mapped.put(key(key), value));
            try (var contents = archive.readSelected(pending.path(), password, pending.archive(), indices(pending.archive()))) {
                if (!pending.config().equals(configuration.preview(pending.archive(), contents))) throw new IOException("transfer.target-changed");
                var fileCatalog = new EnvironmentFiles(session);
                if (!pending.files().equals(fileCatalog.preview(pending.archive()))) throw new IOException("transfer.target-changed");
                String id = UUID.randomUUID().toString();
                Path run = newRun(id);
                try {
                    DatabaseTransfer.Plan data = null;
                    if (includeDatabases || !fileGroups.isEmpty()) {
                        archive.extract(pending.path(), password, run.resolve("archive"), pending.archive());
                    }
                    if (includeDatabases) {
                        data = databases.prepareTargets(pending.databases(), pending.archive(), contents).withPaths(databasePaths);
                    }
                    Path staging = Files.createDirectory(run.resolve("staging"));
                    MigrationFiles.makePrivate(staging);
                    var rendered = configuration.render(pending.archive(), contents,
                            includeDatabases ? configuration.preview(pending.archive(), contents) : pending.config(),
                            selected.stream().map(EnvironmentTransferService::key).collect(java.util.stream.Collectors.toSet()), mapped, staging);
                    var targets = new LinkedHashMap<>(rendered.targets());
                    var replacements = new ArrayList<>(rendered.replacements());
                    fileCatalog.render(pending.files(), fileGroups, includeDatabases, run.resolve("archive"), staging, targets, replacements);
                    var chosenFiles = new EnvironmentFiles.Plan(pending.files().entries().stream()
                            .filter(entry -> fileGroups.contains(entry.group())).toList(), Set.of());
                    var transaction = new FileRestoreTransaction(run.resolve("files"), targets);
                    transaction.prepare(replacements);
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("transfer.cancelled");
                    var job = new Job(1, id, Status.PENDING, "", ProcessHandle.current().pid(), List.copyOf(targets.keySet()),
                            data, includeDatabases ? pending.archive() : null, chosenFiles);
                    writeJob(job);
                    prepared = null;
                    if (!restart.getAsBoolean()) {
                        writeJob(job.status(Status.FAILED, "transfer.restart-failed"));
                        throw new IOException("transfer.restart-failed");
                    }
                } finally {
                    Job scheduled = readJob();
                    if (scheduled == null || !scheduled.id().equals(id)) discardRun(run);
                }
            } catch (IOException failure) { throw failure; }
            catch (Exception failure) { throw new IOException("transfer.database-failed", failure); }
        }
    }

    @Override public RestoreState restoreState() throws IOException {
        Job job = readJob();
        return job == null ? new RestoreState(Status.NONE, "") : new RestoreState(job.status(), job.failureCode());
    }

    /** 重启请求前已发布任务，正常关闭不得再改写本次选中的窗口状态。 */
    public static boolean windowStateRestorePending() throws IOException {
        Job job = readJob();
        return job != null && !Set.of(Status.SUCCEEDED, Status.FAILED).contains(job.status())
                && job.targets().contains("files/host/window/window-state.json");
    }

    public static boolean pending() {
        try {
            Job job = readJob();
            return job != null && !Set.of(Status.SUCCEEDED, Status.FAILED).contains(job.status());
        } catch (IOException | RuntimeException invalid) { return true; }
    }

    /** 调用方已经取得进程实例锁，所有配置与业务对象尚未加载。 */
    public static void runPending(String... arguments) throws IOException {
        try (var ignored = OfflineRestoreLock.acquire()) {
            Job job = readJob();
            if (job == null || Set.of(Status.SUCCEEDED, Status.FAILED).contains(job.status())) return;
            if (ProcessHandle.of(job.requester()).filter(ProcessHandle::isAlive).isPresent())
                throw new IOException("transfer.source-still-running");
            Map<String, Path> targets = new LinkedHashMap<>();
            if (job.files() != null && !job.files().entries().isEmpty()) {
                var environment = top.sywyar.pixivdownload.core.db.DatabaseTarget.configurationEnvironment(arguments);
                try (var session = top.sywyar.pixivdownload.core.db.provider.DatabaseBootstrap.openToolSession(environment)) {
                    targets.putAll(new EnvironmentFiles(session).resolveTargets(job.files()));
                }
            }
            for (String id : job.targets()) if (!targets.containsKey(id)) targets.put(id, resolveTarget(id));
            if (!targets.keySet().equals(new HashSet<>(job.targets()))) throw new IOException("transfer.restore-invalid");
            var transaction = new FileRestoreTransaction(RuntimeFiles.environmentTransferDirectory().resolve(job.id()).resolve("files"), targets);
            if (job.status() != Status.PENDING) {
                recover(job, transaction, "transfer.restore-interrupted");
                return;
            }
            writeJob(job.status(Status.APPLYING, ""));
            try {
                transaction.validatePreparedTargets();
                if (job.databases() != null) DatabaseTransfer.restore(RuntimeFiles.environmentTransferDirectory().resolve(job.id()),
                        job.databases(), job.archive(), arguments);
                transaction.commit();
                writeJob(job.status(Status.SUCCEEDED, ""));
            } catch (Exception failed) {
                recover(job, transaction, "transfer.restore-failed");
            }
        }
    }

    private static void recover(Job job, FileRestoreTransaction transaction, String failureCode) throws IOException {
        Status status;
        try {
            var recovered = transaction.recover();
            if (recovered == FileRestoreTransaction.Status.COMPLETE) status = Status.SUCCEEDED;
            else if (job.databases() != null && DatabaseTransfer.requiresRecovery(RuntimeFiles.environmentTransferDirectory().resolve(job.id())))
                status = Status.RECOVERY_REQUIRED;
            else status = Status.FAILED;
        } catch (IOException | RuntimeException failed) { status = Status.RECOVERY_REQUIRED; }
        writeJob(job.status(status, status == Status.SUCCEEDED ? ""
                : status == Status.RECOVERY_REQUIRED ? "transfer.restore-recovery-required" : failureCode));
        // 启动日志只传播受控错误码，数据库异常正文可能包含行内容。
        if (status == Status.RECOVERY_REQUIRED) throw new IOException("transfer.restore-recovery-required");
    }

    private static Path resolveTarget(String id) throws IOException {
        if (id.equals("host/config")) return RuntimeFiles.peekConfigYamlPath();
        String[] parts = id.split("/", -1);
        if (parts.length != 2 || parts[0].equals("host")) throw new IOException("transfer.restore-unknown-target");
        try {
            return switch (parts[1]) {
                case "config" -> RuntimeFiles.peekPluginConfigPath(parts[0]);
                case "credentials" -> RuntimeFiles.peekPluginCredentialPath(parts[0]);
                default -> throw new IOException("transfer.restore-unknown-target");
            };
        } catch (IllegalArgumentException invalid) { throw new IOException("transfer.restore-unknown-target"); }
    }
    private static void requireIdle() throws IOException {
        if (DatabaseSwitchService.pending()) throw new IOException("transfer.busy");
        Job job = readJob();
        if (job != null && !Set.of(Status.SUCCEEDED, Status.FAILED).contains(job.status())) throw new IOException("transfer.busy");
    }
    private static FieldKey key(ConfigurationTransfer.Key key) { return new FieldKey(key.owner(), key.name()); }
    private static ConfigurationTransfer.Key key(FieldKey key) { return new ConfigurationTransfer.Key(key.owner(), key.key()); }

    private static List<FileGroup> fileGroups(EnvironmentFiles.Plan plan, TransferArchive.Preview archive) {
        var groups = new LinkedHashMap<FieldKey, List<EnvironmentFiles.Entry>>();
        plan.entries().forEach(entry -> groups.computeIfAbsent(entry.group(), ignored -> new ArrayList<>()).add(entry));
        return groups.entrySet().stream().map(group -> {
            var definition = group.getValue().get(0).binding().definition();
            return new FileGroup(group.getKey(), definition.kind().name(), group.getValue().size(),
                    group.getValue().stream().mapToLong(entry -> archive.items().get(entry.index()).size()).sum(), definition.requiresDatabase());
        }).toList();
    }

    private static Set<Integer> indices(TransferArchive.Preview preview) throws IOException {
        var indices = new LinkedHashSet<>(ConfigurationTransfer.indices(preview));
        indices.addAll(DatabaseTransfer.indices(preview));
        indices.addAll(TransferInventory.indices(preview));
        return indices;
    }
    private static Path newRun(String name) throws IOException {
        Path root = RuntimeFiles.environmentTransferDirectory().toAbsolutePath().normalize();
        PlainFilePathGuard.requirePlainParent(root.resolve("unused"), true);
        MigrationFiles.makePrivate(root);
        Path run = Files.createDirectory(root.resolve(name));
        MigrationFiles.makePrivate(run);
        return run;
    }
    private static void discardRun(Path run) throws IOException {
        Path root = RuntimeFiles.environmentTransferDirectory().toAbsolutePath().normalize();
        if (!run.getParent().equals(root) || !run.getFileName().toString().matches("(?:export-)?[a-f0-9-]{36}"))
            throw new IOException("transfer.restore-invalid");
        Files.walkFileTree(run, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                MigrationFiles.requirePlain(dir, true); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                PlainFilePathGuard.requirePlainRegularFile(file); Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Job readJob() throws IOException {
        Path file = RuntimeFiles.environmentTransferDirectory().resolve("pending.json");
        MigrationFiles.requirePlain(file, false);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        PlainFilePathGuard.requirePlainRegularFile(file);
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(8 * 1024 * 1024 + 1);
            if (bytes.length > 8 * 1024 * 1024) throw new IOException("transfer.restore-limit");
            try {
                Job job = JSON.readValue(bytes, Job.class);
                if (job == null) throw new IOException("transfer.restore-invalid");
                return job;
            } catch (RuntimeException invalid) { throw new IOException("transfer.restore-invalid", invalid); }
        }
    }
    private static void writeJob(Job job) throws IOException {
        Path target = RuntimeFiles.environmentTransferDirectory().resolve("pending.json").toAbsolutePath().normalize();
        PlainFilePathGuard.requirePlainParent(target, false);
        byte[] bytes = JSON.writeValueAsBytes(job);
        if (bytes.length > 8 * 1024 * 1024) throw new IOException("transfer.restore-limit");
        Path temporary = Files.createTempFile(target.getParent(), ".pending-", ".part");
        try {
            MigrationFiles.makePrivate(temporary);
            Files.write(temporary, bytes, LinkOption.NOFOLLOW_LINKS);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
