package top.sywyar.pixivdownload.gui.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.provider.DatabaseBootstrap;
import top.sywyar.pixivdownload.core.db.schema.PrivateDatabaseMigrationState;
import top.sywyar.pixivdownload.migration.DatabaseMigration;
import top.sywyar.pixivdownload.migration.database.MigrationEndpoint;
import top.sywyar.pixivdownload.migration.database.ProviderDatabaseMigration;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings.*;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** 确认后的任务先耐久保存；替代进程在任何业务装配前执行，配置是最后的提交点。 */
public final class DatabaseSwitchService {
    private static final Object LOCK = new Object();
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final ObjectMapper JSON = top.sywyar.pixivdownload.migration.database.MigrationSchemaJson.mapper();
    private static final MigrationControl CONTROL = (table, rows) -> {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
    };
    private final DatabaseSettingsService settings;
    private final DesktopUiHost.ConfigFile config;
    private final PluginBootstrapSession session;
    private final BooleanSupplier restart;
    private Pending prepared;

    DatabaseSwitchService(DatabaseSettingsService settings, DesktopUiHost.ConfigFile config,
                          PluginBootstrapSession session, BooleanSupplier restart) {
        this.settings = settings; this.config = config; this.session = session; this.restart = restart;
    }

    public MigrationPlan preview(Map<String, String> updates) throws IOException {
        synchronized (LOCK) {
            prepared = null;
            var entries = new ArrayList<MigrationStore>();
            try {
                Job previous = readJob();
                if (previous != null && Set.of("PENDING", "RUNNING", "SWITCHING").contains(previous.status()))
                    throw new IllegalStateException("MIGRATION_BUSY");
                var configuration = settings.state();
                if (!configuration.overriddenKeys().isEmpty()) throw new IllegalStateException("CONFIG_OVERRIDDEN");
                if (configuration.restartRequired()) throw new IllegalStateException("DATABASE_RESTART_REQUIRED");
                var baseline = config.snapshot();
                Map<String, String> draft = settings.draft(updates);
                var target = settings.draftTarget(draft).migrationEndpoint();
                MigrationEndpoint source;
                boolean repair = false;
                try {
                    source = settings.savedTarget().migrationEndpoint();
                } catch (IOException invalid) {
                    if (settings.hasActiveTarget()) throw invalid;
                    PrivateDatabaseMigrationState.requireReady(settings.draftTarget(draft), session.databaseProviders());
                    source = target;
                    repair = true;
                }
                var catalog = DatabaseMigration.declarations(session, null);
                String token = UUID.randomUUID().toString();
                Path run = RuntimeFiles.databaseMigrationDirectory().resolve(token);
                boolean same = source.owner().equals(target.owner()) && source.target().description().equals(target.target().description());
                List<ProviderDatabaseMigration.Store> stores;
                try {
                    stores = DatabaseMigration.stores(catalog, same ? target : source, target, run,
                            run.resolve("source.backup"), Set.of(), false);
                } catch (Exception incompatible) {
                    entries.add(new MigrationStore("main", source.target().description(),
                            target.target().description(), "BLOCKED", List.of()));
                    for (String owner : source.provider().privateDatabaseOwners(source.target(),
                            RuntimeFiles.dataDirectory(), top.sywyar.pixivdownload.config.InstallIdentity.get()).stream().sorted().toList()) {
                        entries.add(new MigrationStore("private:" + owner, source.target().description(),
                                target.target().description(), "BLOCKED", List.of()));
                    }
                    throw incompatible;
                }
                var reports = previous == null ? Map.<String, ProviderDatabaseMigration.StoreReport>of() : readReport(previous.id());
                var reuse = new LinkedHashMap<String, ProviderDatabaseMigration.StoreReport>();
                var rebuild = new LinkedHashMap<String, Set<String>>();
                var replacements = new LinkedHashMap<String, MigrationReplacement>();
                String failure = "";
                for (var store : stores) {
                    String phase = "READY";
                    Set<String> objects = Set.of();
                    try {
                        if (same) {
                            store.target().target().checkConnection();
                            phase = repair ? "CONNECTION_REPAIR" : "SAME_TARGET";
                        } else {
                            var sourceInspection = store.source().inspect(store.request().schema());
                            objects = sourceInspection.rebuildObjects();
                            rebuild.put(store.owner(), objects);
                            var inspection = store.target().provider().inspectMigration(store.target().target(), store.request().schema(), false);
                            if (sourceInspection.empty() && store.owner().equals("main")) phase = "EMPTY_SOURCE";
                            var retained = reports.get(store.owner());
                            boolean previouslyCommitted = retained != null
                                    && Set.of(ProviderDatabaseMigration.Phase.COMMITTED, ProviderDatabaseMigration.Phase.COMMITTING,
                                            ProviderDatabaseMigration.Phase.UNKNOWN).contains(retained.phase())
                                    && store.source().description().equals(retained.source())
                                    && store.target().target().description().equals(retained.target());
                            if (previouslyCommitted) {
                                requireRetained(store, retained);
                                store.source().verifyRevision(retained.snapshot().sourceRevision());
                                store.target().provider().verifyImported(store.target().target(), retained.snapshot(), CONTROL);
                                reuse.put(store.owner(), retained);
                                phase = "COMMITTED";
                            } else if (!inspection.empty()) {
                                String revision = store.target().provider().inspectReplacement(store.target().target(), store.request().schema());
                                replacements.put(store.owner(), new MigrationReplacement(revision,
                                        store.request().backup().resolveSibling("target-" + store.request().backup().getFileName())));
                                phase = "REPLACE_TARGET";
                            }
                        }
                    } catch (Exception rejected) {
                        phase = "BLOCKED";
                        if (failure.isEmpty()) failure = ProviderDatabaseMigration.safeFailure(rejected);
                    }
                    entries.add(new MigrationStore(store.owner(), store.source().description(),
                            store.target().target().description(), phase, objects.stream().sorted().toList()));
                }
                if (!baseline.equals(config.snapshot())) throw new IllegalStateException("CONFIG_CHANGED");
                var plan = new MigrationPlan(token, failure.isEmpty(), entries, failure);
                if (plan.ready()) prepared = new Pending(plan, baseline, draft, catalog.manifest(), rebuild, reuse, replacements, same);
                return plan;
            } catch (Exception failure) {
                return new MigrationPlan("", false, entries, ProviderDatabaseMigration.safeFailure(failure));
            }
        }
    }

    public void start(String token) throws IOException {
        start(token, () -> {});
    }

    public void start(String token, DesktopUiHost.IoOperation saveOtherSettings) throws IOException {
        try (var ignored = top.sywyar.pixivdownload.transfer.OfflineRestoreLock.acquire()) {
          synchronized (LOCK) {
            if (top.sywyar.pixivdownload.transfer.EnvironmentTransferService.pending()) throw new IOException("MIGRATION_BUSY");
            Pending pending = prepared;
            if (pending == null || !pending.plan().token().equals(token)) throw new IOException("MIGRATION_PLAN_CHANGED");
            config.withWriteLock(() -> {
                if (!pending.baseline().equals(config.snapshot())) throw new IOException("CONFIG_CHANGED");
                Job active = readJob();
                if (active != null && Set.of("PENDING", "RUNNING", "SWITCHING").contains(active.status()))
                    throw new IOException("MIGRATION_BUSY");
                String id = pending.plan().token();
                Path run = directory(id);
                MigrationFiles.requirePlain(run, true);
                Files.createDirectories(run);
                MigrationFiles.makePrivate(run);
                Path source = run.resolve("source.yaml");
                Path target = run.resolve("target.yaml");
                Path current = RuntimeFiles.peekConfigYamlPath().toAbsolutePath().normalize();
                MigrationFiles.requirePlain(current, false);
                write(source, boundedBytes(current));
                if (!pending.same()) new ConfigFileEditor(source).writeAll(Map.of("database.provider", settings.savedTarget().providerOwner()));
                MigrationFiles.makePrivate(source);
                force(source);
                saveOtherSettings.run();
                write(target, boundedBytes(current));
                new ConfigFileEditor(target).writeAll(pending.updates());
                MigrationFiles.makePrivate(target);
                force(target);
                if (!pending.reuse().isEmpty()) {
                    var all = new LinkedHashMap<String, ProviderDatabaseMigration.StoreReport>();
                    for (var store : pending.plan().stores()) {
                        var retained = pending.reuse().get(store.owner());
                        all.put(store.owner(), retained != null ? retained : new ProviderDatabaseMigration.StoreReport(
                                "", "", "", "", ProviderDatabaseMigration.Phase.NOT_STARTED, "", 0, null));
                    }
                    writeJson(run.resolve("report.json"), new ProviderDatabaseMigration.Report(1, "IN_PROGRESS", all, null, List.of()));
                }
                Job job = new Job(id, "PENDING", "", ProcessHandle.current().pid(), current.toString(),
                        MigrationFiles.digest(current), MigrationFiles.digest(source), MigrationFiles.digest(target), pending.schema(),
                        pending.rebuild(), pending.plan().stores(), pending.replacements(), pending.same());
                writeJson(RuntimeFiles.databaseSwitchJournal(), job);
                prepared = null;
                if (!restart.getAsBoolean()) {
                    writeJson(RuntimeFiles.databaseSwitchJournal(), job.withStatus("FAILED", "RESTART_FAILED"));
                    throw new IOException("RESTART_FAILED");
                }
            });
          }
        }
    }

    public static boolean pending() {
        try {
            Job job = readJob();
            return job != null && !Set.of("SUCCEEDED", "FAILED").contains(job.status());
        } catch (IOException | RuntimeException invalid) { return true; }
    }

    /** 调用方已取得单实例锁；此方法返回后才允许创建主库或业务插件。 */
    public static void runPending(String[] arguments) throws Exception {
        synchronized (LOCK) {
            Job job = readJob();
            if (job == null || Set.of("SUCCEEDED", "FAILED").contains(job.status())) return;
            Path current = Path.of(job.config()).toAbsolutePath().normalize();
            if (!current.equals(RuntimeFiles.peekConfigYamlPath().toAbsolutePath().normalize()))
                throw new IllegalStateException("MIGRATION_CONFIG_PATH_CHANGED");
            String actual = MigrationFiles.digest(current);
            if (job.status().equals("SWITCHING") && actual.equals(job.targetDigest())) {
                writeJson(RuntimeFiles.databaseSwitchJournal(), job.withStatus("SUCCEEDED", ""));
                return;
            }
            if (!actual.equals(job.configDigest())) throw new IllegalStateException("MIGRATION_CONFIG_CHANGED");
            if (!job.status().equals("PENDING")) {
                writeJson(RuntimeFiles.databaseSwitchJournal(), job.withStatus("FAILED", "MIGRATION_INTERRUPTED"));
                return;
            }
            if (ProcessHandle.of(job.requester()).filter(ProcessHandle::isAlive).isPresent())
                throw new IllegalStateException("MIGRATION_SOURCE_STILL_RUNNING");
            job = job.withStatus("RUNNING", "");
            writeJson(RuntimeFiles.databaseSwitchJournal(), job);
            try {
                Path run = directory(job.id());
                Path sourceConfig = run.resolve("source.yaml");
                Path targetConfig = run.resolve("target.yaml");
                if (!MigrationFiles.digest(sourceConfig).equals(job.sourceDigest())
                        || !MigrationFiles.digest(targetConfig).equals(job.targetDigest()))
                    throw new IllegalStateException("MIGRATION_CONFIG_CHANGED");
                var environment = DatabaseTarget.configurationEnvironment(arguments);
                try (var session = DatabaseBootstrap.openToolSession(environment)) {
                    var catalog = DatabaseMigration.declarations(session, null);
                    if (!catalog.manifest().equals(job.schema())) throw new IllegalStateException("MIGRATION_PLUGINS_CHANGED");
                    var target = endpoint(session, targetConfig);
                    var source = job.same() ? target : endpoint(session, sourceConfig);
                    var stores = DatabaseMigration.stores(catalog, source, target, run,
                            run.resolve("source.backup"), Set.of(), true);
                    var configured = new ArrayList<ProviderDatabaseMigration.Store>();
                    for (var store : stores) configured.add(new ProviderDatabaseMigration.Store(store.owner(), store.source(), store.target(),
                            new MigrationRequest(store.request().schema(), store.request().backup(),
                                    job.rebuild().getOrDefault(store.owner(), Set.of()), store.request().storageMigrations()),
                            job.replacements().get(store.owner())));
                    requireSameStores(job.stores(), configured);
                    if (job.same()) {
                        target.target().checkConnection();
                    } else {
                        var emptyOwners = job.stores().stream().filter(store -> store.phase().equals("EMPTY_SOURCE"))
                                .map(MigrationStore::owner).collect(java.util.stream.Collectors.toSet());
                        for (var store : configured) if (emptyOwners.contains(store.owner())) {
                            if (!store.source().inspect(store.request().schema()).empty()
                                    || !store.target().provider().inspectMigration(store.target().target(), store.request().schema(), false).empty())
                                throw new IllegalStateException("MIGRATION_SOURCE_CHANGED");
                        }
                        configured.removeIf(store -> emptyOwners.contains(store.owner()));
                        var sourceConfiguration = PrivateDatabaseMigrationState.sourceConfiguration(sourceConfig, RuntimeFiles.dataDirectory());
                        var prior = new LinkedHashMap<>(readReport(job.id()));
                        emptyOwners.forEach(prior::remove);
                        if (!configured.isEmpty()) {
                            var result = ProviderDatabaseMigration.migrate(configured, prior,
                                    progress -> writeJson(run.resolve("report.json"), progress),
                                    (store, snapshot) -> {
                                        if (!store.owner().equals("main")) PrivateDatabaseMigrationState.recordVerified(
                                                store.owner().substring("private:".length()), store.source(), target, store.target(),
                                                sourceConfiguration, snapshot);
                                    }, CONTROL);
                            ProviderDatabaseMigration.verifyAll(configured, result.stores(), CONTROL);
                        }
                    }
                    var selected = DatabaseBootstrap.inspect(session,
                            DatabaseTarget.configurationEnvironment("--spring.config.location=" + targetConfig.toUri()));
                    PrivateDatabaseMigrationState.requireReady(selected, session.databaseProviders());
                }
                Job completed = job;
                new ConfigFileEditor(current).asConfigFile().withWriteLock(() -> {
                    if (!MigrationFiles.digest(current).equals(completed.configDigest())) throw new IOException("CONFIG_CHANGED");
                    writeJson(RuntimeFiles.databaseSwitchJournal(), completed.withStatus("SWITCHING", ""));
                    replace(current, boundedBytes(targetConfig));
                    writeJson(RuntimeFiles.databaseSwitchJournal(), completed.withStatus("SUCCEEDED", ""));
                });
            } catch (Exception failed) {
                if (MigrationFiles.digest(current).equals(job.targetDigest())) {
                    writeJson(RuntimeFiles.databaseSwitchJournal(), job.withStatus("SUCCEEDED", ""));
                } else {
                    writeJson(RuntimeFiles.databaseSwitchJournal(), job.withStatus("FAILED", ProviderDatabaseMigration.safeFailure(failed)));
                    if (!MigrationFiles.digest(current).equals(job.configDigest())) throw new IllegalStateException("MIGRATION_CONFIG_CHANGED");
                }
            }
        }
    }

    public static MigrationState state() throws IOException {
        Job job = readJob();
        if (job == null) return new MigrationState("", "", List.of());
        var reports = readReport(job.id());
        return new MigrationState(job.status(), job.failure(), job.stores().stream().map(store -> {
            var report = reports.get(store.owner());
            return new MigrationStore(store.owner(), store.source(), store.target(),
                    report == null ? store.phase() : report.phase().name(), store.rebuildObjects());
        }).toList());
    }

    private static MigrationEndpoint endpoint(PluginBootstrapSession session, Path file) throws Exception {
        return MigrationEndpoint.resolve(session.databaseProviders(),
                DatabaseTarget.configurationEnvironment("--spring.config.location=" + file.toUri()));
    }
    private static void requireSameStores(List<MigrationStore> expected, List<ProviderDatabaseMigration.Store> actual) {
        var identities = actual.stream().map(store -> List.of(store.owner(), store.source().description(), store.target().target().description())).toList();
        if (!expected.stream().map(store -> List.of(store.owner(), store.source(), store.target())).toList().equals(identities))
            throw new IllegalStateException("MIGRATION_STORES_CHANGED");
    }
    private static void requireRetained(ProviderDatabaseMigration.Store store, ProviderDatabaseMigration.StoreReport report) {
        if (report == null || report.snapshot() == null
                || !Set.of(ProviderDatabaseMigration.Phase.COMMITTED, ProviderDatabaseMigration.Phase.COMMITTING,
                        ProviderDatabaseMigration.Phase.UNKNOWN).contains(report.phase())
                || !store.request().schema().equals(report.snapshot().schema())
                || !store.source().owner().equals(report.sourceProvider()) || !store.target().owner().equals(report.targetProvider())
                || !store.source().description().equals(report.source()) || !store.target().target().description().equals(report.target()))
            throw new IllegalStateException("TARGET_NOT_EMPTY");
    }
    private static Job readJob() throws IOException {
        Path path = RuntimeFiles.databaseSwitchJournal();
        return Files.exists(path) ? JSON.readValue(boundedBytes(path), Job.class) : null;
    }
    private static Map<String, ProviderDatabaseMigration.StoreReport> readReport(String id) throws IOException {
        Path report = directory(id).resolve("report.json");
        return Files.exists(report) ? JSON.readValue(boundedBytes(report), ProviderDatabaseMigration.Report.class).stores() : Map.of();
    }
    private static Path directory(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("MIGRATION_ID_INVALID");
        return RuntimeFiles.databaseMigrationDirectory().resolve(id);
    }
    private static byte[] boundedBytes(Path path) throws IOException {
        MigrationFiles.requirePlain(path, false);
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("MIGRATION_FILE_TOO_LARGE");
            return bytes;
        }
    }
    private static void writeJson(Path path, Object value) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(value);
        if (bytes.length > MAX_BYTES) throw new IOException("MIGRATION_FILE_TOO_LARGE");
        replace(path, bytes);
    }
    private static void replace(Path path, byte[] bytes) throws IOException {
        MigrationFiles.requirePlain(path, false);
        Path temporary = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try { write(temporary, bytes); Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
    }
    private static void write(Path path, byte[] bytes) throws IOException {
        MigrationFiles.createPrivateFile(path);
        try (var output = FileChannel.open(path, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) output.write(buffer);
            output.force(true);
        }
    }
    private static void force(Path path) throws IOException {
        try (var file = FileChannel.open(path, StandardOpenOption.WRITE)) { file.force(true); }
    }
    private record Pending(MigrationPlan plan, DesktopUiHost.ConfigSnapshot baseline, Map<String, String> updates,
            DatabaseMigration.SchemaManifest schema, Map<String, Set<String>> rebuild,
            Map<String, ProviderDatabaseMigration.StoreReport> reuse, Map<String, MigrationReplacement> replacements, boolean same) {}
    public record Job(String id, String status, String failure, long requester, String config,
            String configDigest, String sourceDigest, String targetDigest, DatabaseMigration.SchemaManifest schema,
            Map<String, Set<String>> rebuild, List<MigrationStore> stores, Map<String, MigrationReplacement> replacements, boolean same) {
        public Job {
            directory(id);
            if (!Set.of("PENDING", "RUNNING", "SWITCHING", "SUCCEEDED", "FAILED").contains(status))
                throw new IllegalArgumentException("MIGRATION_STATE_INVALID");
            rebuild = Map.copyOf(rebuild); stores = List.copyOf(stores);
            replacements = replacements == null ? Map.of() : Map.copyOf(replacements);
        }
        Job withStatus(String status, String failure) {
            return new Job(id, status, failure, requester, config, configDigest, sourceDigest, targetDigest, schema, rebuild, stores, replacements, same);
        }
    }
}
