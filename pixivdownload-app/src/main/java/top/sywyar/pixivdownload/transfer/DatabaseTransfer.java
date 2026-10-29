package top.sywyar.pixivdownload.transfer;

import top.sywyar.pixivdownload.config.InstallIdentity;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.config.credential.PluginCredentialStore;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.provider.DatabaseBootstrap;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixTransfer;
import top.sywyar.pixivdownload.core.db.schema.CoreMigrationSchema;
import top.sywyar.pixivdownload.migration.DatabaseMigration;
import top.sywyar.pixivdownload.migration.database.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.plugin.storage.PluginDatabaseFactory;
import top.sywyar.pixivdownload.transfer.archive.ArchiveDatabaseSource;
import top.sywyar.pixivdownload.transfer.archive.TransferArchive;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** 归档数据库始终通过当前可信提供者迁入；预览不初始化数据库、身份或凭据。 */
final class DatabaseTransfer {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = MigrationSchemaJson.mapper();
    private static final MigrationControl CONTROL = (table, rows) -> {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("transfer.cancelled");
    };
    private final DesktopUiHost host;
    private final PluginBootstrapSession session;
    private final String[] arguments;

    record StorePlan(String owner, String target, String replacementRevision, String credentialDigest, long rows) {}
    record Plan(DatabaseMigration.SchemaManifest catalog, String configDigest, String installation, List<StorePlan> stores,
                PathPrefixTransfer.Paths paths, Map<Long, String> mappedPaths) {
        Plan { stores = List.copyOf(stores); mappedPaths = Map.copyOf(mappedPaths); }
        Plan withPaths(Map<Long, String> selected) {
            return new Plan(catalog, configDigest, installation, stores, paths, PathPrefixTransfer.destinations(paths, selected));
        }
    }

    DatabaseTransfer(DesktopUiHost host, PluginBootstrapSession session, String[] arguments) {
        this.host = host; this.session = session; this.arguments = arguments.clone();
    }

    List<TransferArchive.Source> export(Path run) throws Exception {
        requireAvailable();
        var catalog = DatabaseMigration.declarations(session, null);
        var source = endpoint(session, arguments);
        var stores = DatabaseMigration.stores(catalog, source, source, run, run.resolve("main.backup"), Set.of(), false);
        var opened = new ArrayList<DatabaseExportSession>();
        Exception failure = null;
        try {
            // 全部源会话同时持有，私库与主库均完成停写后才读取快照。
            for (var store : stores) {
                var inspection = store.source().inspect(store.request().schema());
                opened.add(store.source().export(new MigrationRequest(store.request().schema(), store.request().backup(),
                        inspection.rebuildObjects(), store.request().storageMigrations()), CONTROL));
            }
            var result = new ArrayList<TransferArchive.Source>();
            byte[] catalogBytes = JSON.writeValueAsBytes(catalog.manifest());
            if (catalogBytes.length > 8 * 1024 * 1024) throw new IOException("transfer.database-limit");
            result.add(new TransferArchive.Source(new TransferArchive.Descriptor("host", TransferArchive.Category.DATABASE,
                    "catalog.json", 1), () -> new ByteArrayInputStream(catalogBytes)));
            var budget = new ArchiveDatabaseSource.Budget(TransferArchive.MAX_CONTENT_BYTES);
            var paths = new PathPrefixTransfer(DatabaseTarget.configurationEnvironment(arguments).getProperty("download.root-folder",
                    new top.sywyar.pixivdownload.core.appconfig.DownloadConfig().getRootFolder()));
            for (int i = 0; i < stores.size(); i++) {
                String owner = archiveOwner(stores.get(i).owner());
                if (owner.equals("host")) requireNoPendingFiles(opened.get(i).snapshot().verification());
                var snapshot = ArchiveDatabaseSource.write(run.resolve("store-" + i), opened.get(i), CONTROL,
                        (table, row) -> owner.equals("host") ? paths.capture(table,
                                top.sywyar.pixivdownload.core.schedule.db.ScheduleTransferRows.paused(table, row)) : row, budget);
                result.addAll(snapshot.archiveEntries(owner));
            }
            byte[] pathBytes = JSON.writeValueAsBytes(paths.snapshot());
            if (pathBytes.length > 8 * 1024 * 1024) throw new IOException("transfer.database-limit");
            result.add(new TransferArchive.Source(new TransferArchive.Descriptor("host", TransferArchive.Category.DATABASE,
                    "paths.json", 1), () -> new ByteArrayInputStream(pathBytes)));
            return List.copyOf(result);
        } catch (Exception problem) { failure = problem; throw problem; }
        finally {
            Exception closeFailure = null;
            for (int i = opened.size() - 1; i >= 0; i--) try { opened.get(i).close(); }
            catch (Exception problem) {
                if (closeFailure == null) closeFailure = problem; else closeFailure.addSuppressed(problem);
            }
            if (closeFailure != null) {
                if (failure != null) failure.addSuppressed(closeFailure); else throw closeFailure;
            }
        }
    }

    static Set<Integer> indices(TransferArchive.Preview archive) {
        Set<Integer> indices = new LinkedHashSet<>();
        for (int i = 0; i < archive.items().size(); i++) {
            var descriptor = archive.items().get(i).descriptor();
            if (descriptor.category() == TransferArchive.Category.DATABASE
                    && Set.of("metadata.json", "catalog.json", "paths.json").contains(descriptor.path())) indices.add(i);
        }
        return indices;
    }

    Plan preview(TransferArchive.Preview archive, TransferArchive.SelectedContents contents) throws Exception {
        if (archive.items().stream().noneMatch(item -> item.descriptor().category() == TransferArchive.Category.DATABASE)) return null;
        requireAvailable();
        if (!archive.encrypted()) throw new IOException("transfer.password-required");
        String configDigest = MigrationFiles.digest(RuntimeFiles.peekConfigYamlPath());
        String installation = InstallIdentity.readExisting().orElse("");
        var catalog = DatabaseMigration.declarations(session, null);
        var metadata = metadata(archive, contents, catalog.manifest());
        PathPrefixTransfer.validate(metadata.get("host").verification(), paths(archive, contents));
        var target = endpoint(session, arguments);
        List<StorePlan> stores = new ArrayList<>();
        for (var entry : metadata.entrySet()) {
            String owner = entry.getKey();
            var schema = schema(catalog, owner);
            if (!schema.equals(entry.getValue().schema())) throw new IOException("transfer.database-schema-mismatch");
            MigrationEndpoint destination = owner.equals("host") ? target : installation.isEmpty() ? null
                    : target.privateStorage(PluginDatabaseFactory.privateSettings(owner, new PluginCredentialStore(), false));
            String replacement = "";
            if (destination != null && !destination.provider().inspectMigration(destination.target(), schema, false).empty())
                replacement = destination.provider().inspectReplacement(destination.target(), schema);
            if (owner.equals("host") && !replacement.isEmpty()
                    && top.sywyar.pixivdownload.core.asset.FileOperationJournal.hasPendingFileOperations(destination.target(), schema))
                throw new IOException("transfer.database-pending-files");
            long rows = 0;
            for (var table : entry.getValue().verification().tables().values()) rows = Math.addExact(rows, table.rows());
            stores.add(new StorePlan(owner, destination == null ? "" : destination.description(), replacement,
                    owner.equals("host") ? "" : credentialDigest(owner), rows));
        }
        if (!configDigest.equals(MigrationFiles.digest(RuntimeFiles.peekConfigYamlPath()))
                || !installation.equals(InstallIdentity.readExisting().orElse(""))) throw new IOException("transfer.target-changed");
        return new Plan(catalog.manifest(), configDigest, installation, stores, paths(archive, contents), Map.of());
    }

    private static PathPrefixTransfer.Paths paths(TransferArchive.Preview archive, TransferArchive.SelectedContents contents) throws IOException {
        for (int i : indices(archive)) if (archive.items().get(i).descriptor().path().equals("paths.json"))
            return JSON.readValue(contents.get(i), PathPrefixTransfer.Paths.class);
        throw new IOException("transfer.database-path-invalid");
    }

    /** 用户已确认；只补齐目标安装自己的私库身份和密钥，随后绑定最终恢复计划。 */
    Plan prepareTargets(Plan expected, TransferArchive.Preview archive, TransferArchive.SelectedContents contents) throws Exception {
        if (!expected.equals(preview(archive, contents))) throw new IOException("transfer.target-changed");
        for (var store : expected.stores()) if (!store.owner().equals("host")) {
            if (InstallIdentity.readExisting().isEmpty()) InstallIdentity.get();
            if (PluginDatabaseFactory.privateSettings(store.owner(), new PluginCredentialStore(), false).password().isEmpty())
                PluginDatabaseFactory.privateSettings(store.owner(), new PluginCredentialStore());
        }
        return preview(archive, contents);
    }

    private static Map<String, ArchiveDatabaseSource.Metadata> metadata(TransferArchive.Preview archive,
            TransferArchive.SelectedContents contents, DatabaseMigration.SchemaManifest expected) throws IOException {
        var result = new TreeMap<String, ArchiveDatabaseSource.Metadata>();
        boolean catalog = false;
        for (int i : indices(archive)) {
            var descriptor = archive.items().get(i).descriptor();
            if (descriptor.schemaVersion() != 1) throw new IOException("transfer.database-schema-mismatch");
            if (descriptor.path().equals("paths.json")) {
                if (!descriptor.owner().equals("host")) throw new IOException("transfer.database-invalid");
                continue;
            }
            if (descriptor.path().equals("catalog.json")) {
                if (!descriptor.owner().equals("host") || catalog
                        || !expected.equals(JSON.readValue(contents.get(i), DatabaseMigration.SchemaManifest.class)))
                    throw new IOException("transfer.database-schema-mismatch");
                catalog = true;
            } else result.put(descriptor.owner(), JSON.readValue(contents.get(i), ArchiveDatabaseSource.Metadata.class));
        }
        if (!catalog || !result.containsKey("host") || result.containsValue(null)) throw new IOException("transfer.database-invalid");
        requireNoPendingFiles(result.get("host").verification());
        for (var owner : result.entrySet()) {
            Set<String> paths = new HashSet<>();
            paths.add("metadata.json");
            if (owner.getKey().equals("host")) { paths.add("catalog.json"); paths.add("paths.json"); }
            for (int i = 0; i < owner.getValue().schema().tables().size(); i++) paths.add(i + ".rows");
            for (var item : archive.items()) if (item.descriptor().category() == TransferArchive.Category.DATABASE
                    && item.descriptor().owner().equals(owner.getKey())) {
                if (item.descriptor().schemaVersion() != 1 || !paths.remove(item.descriptor().path()))
                    throw new IOException("transfer.database-invalid");
            }
            if (!paths.isEmpty()) throw new IOException("transfer.database-invalid");
        }
        for (var item : archive.items()) if (item.descriptor().category() == TransferArchive.Category.DATABASE
                && !result.containsKey(item.descriptor().owner())) throw new IOException("transfer.database-invalid");
        return result;
    }

    /** 调用方已停机；任务只含本机确认目标，归档不能提供连接参数。 */
    static void restore(Path run, Plan plan, TransferArchive.Preview archive, String[] arguments) throws Exception {
        if (!plan.configDigest().equals(MigrationFiles.digest(RuntimeFiles.peekConfigYamlPath()))
                || !plan.installation().equals(InstallIdentity.readExisting().orElse(""))) throw new IOException("transfer.target-changed");
        for (var store : plan.stores()) if (!store.owner().equals("host") && !store.credentialDigest().equals(credentialDigest(store.owner())))
            throw new IOException("transfer.target-changed");
        try (var session = DatabaseBootstrap.openToolSession(DatabaseTarget.configurationEnvironment(arguments))) {
            var catalog = DatabaseMigration.declarations(session, null);
            if (!plan.catalog().equals(catalog.manifest())) throw new IOException("transfer.database-schema-mismatch");
            var target = endpoint(session, arguments);
            List<ProviderDatabaseMigration.Store> stores = new ArrayList<>();
            var budget = new ArchiveDatabaseSource.Budget(TransferArchive.MAX_CONTENT_BYTES);
            for (var planned : plan.stores()) {
                Map<String, Path> files = new LinkedHashMap<>();
                for (int i = 0; i < archive.items().size(); i++) {
                    var item = archive.items().get(i);
                    var descriptor = item.descriptor();
                    if (descriptor.category() == TransferArchive.Category.DATABASE && descriptor.owner().equals(planned.owner())
                            && !Set.of("catalog.json", "paths.json").contains(descriptor.path())) {
                        Path file = run.resolve("archive").resolve(Integer.toString(i));
                        if (!item.sha256().equals(MigrationFiles.digest(file))) throw new IOException("transfer.archive-changed");
                        files.put(descriptor.path(), file);
                    }
                }
                var source = new ArchiveDatabaseSource(files);
                if (planned.owner().equals("host")) requireNoPendingFiles(source.metadata().verification());
                if (planned.owner().equals("host")) PathPrefixTransfer.validate(source.metadata().verification(), plan.paths());
                var schema = schema(catalog, planned.owner());
                source.inspect(schema);
                if (planned.owner().equals("host")) try (var snapshot = source.export(new MigrationRequest(schema,
                        run.resolve("unused"), Set.copyOf(source.metadata().rebuildObjects())), CONTROL)) {
                    var pathMapper = PathPrefixTransfer.mapper(schema, plan.paths(), plan.mappedPaths());
                    source = ArchiveDatabaseSource.write(run.resolve("restored-main"), snapshot, CONTROL,
                            (table, row) -> pathMapper.apply(table,
                                    top.sywyar.pixivdownload.core.schedule.db.ScheduleTransferRows.paused(table, row)), budget);
                } else source.verifyRows(CONTROL);
                var destination = planned.owner().equals("host") ? target
                        : target.privateStorage(PluginDatabaseFactory.privateSettings(planned.owner(), new PluginCredentialStore(), false));
                if (planned.owner().equals("host") && !planned.replacementRevision().isEmpty()
                        && top.sywyar.pixivdownload.core.asset.FileOperationJournal.hasPendingFileOperations(destination.target(), schema))
                    throw new IOException("transfer.database-pending-files");
                if (!planned.target().isEmpty() && !planned.target().equals(destination.description())) throw new IOException("transfer.target-changed");
                Path backup = run.resolve("database-backups").resolve(planned.owner() + ".backup");
                var replacement = planned.replacementRevision().isEmpty() ? null : new MigrationReplacement(planned.replacementRevision(), backup);
                stores.add(new ProviderDatabaseMigration.Store(storeOwner(planned.owner()), source, destination,
                        new MigrationRequest(schema, backup, Set.copyOf(source.metadata().rebuildObjects())), replacement));
            }
            var report = ProviderDatabaseMigration.migrate(stores, value -> writeReport(run.resolve("database-report.json"), value),
                    (store, snapshot) -> {}, CONTROL);
            ProviderDatabaseMigration.verifyAll(stores, report.stores(), CONTROL);
        }
    }

    private static void requireNoPendingFiles(MigrationVerification snapshot) throws IOException {
        if (top.sywyar.pixivdownload.core.asset.FileOperationJournal.hasPendingFileOperations(snapshot))
            throw new IOException("transfer.database-pending-files");
    }

    private void requireAvailable() throws IOException {
        if (session == null) throw new IOException("transfer.database-unavailable");
        var state = host.databaseSettings().orElseThrow(() -> new IOException("transfer.database-unavailable")).state();
        if (state.restartRequired() || !state.overriddenKeys().isEmpty()) throw new IOException("transfer.database-configuration-changed");
    }
    private static MigrationEndpoint endpoint(PluginBootstrapSession session, String[] arguments) throws Exception {
        return MigrationEndpoint.resolve(session.databaseProviders(), DatabaseTarget.configurationEnvironment(arguments));
    }
    private static MigrationSchema schema(DatabaseMigration.Declarations catalog, String owner) throws IOException {
        if (owner.equals("host")) return CoreMigrationSchema.create(catalog.registry(), true);
        var declaration = catalog.manifest().owners().stream().filter(value -> value.owner().equals(owner)).findFirst()
                .orElseThrow(() -> new IOException("transfer.database-owner-unavailable"));
        if (declaration.privateContributions().isEmpty()) throw new IOException("transfer.database-owner-unavailable");
        var registry = new DatabaseSchemaRegistry(new PluginRegistry(List.of()));
        for (var contribution : declaration.privateContributions()) registry.register(owner, contribution);
        return CoreMigrationSchema.create(registry, false);
    }
    private static String archiveOwner(String owner) { return owner.equals("main") ? "host" : owner.substring("private:".length()); }
    private static String storeOwner(String owner) { return owner.equals("host") ? "main" : "private:" + owner; }
    private static String credentialDigest(String owner) throws IOException {
        Path path = RuntimeFiles.peekPluginCredentialPath(owner);
        MigrationFiles.requirePlain(path, false);
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? MigrationFiles.digest(path) : "ABSENT";
    }
    static boolean requiresRecovery(Path run) throws IOException {
        Path file = run.resolve("database-report.json");
        MigrationFiles.requirePlain(file, false);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(8 * 1024 * 1024 + 1);
            if (bytes.length > 8 * 1024 * 1024) throw new IOException("transfer.database-limit");
            var report = JSON.readValue(bytes, ProviderDatabaseMigration.Report.class);
            if (report == null || report.formatVersion() != 1) throw new IOException("transfer.database-invalid");
            return report.stores().values().stream().anyMatch(store -> Set.of(ProviderDatabaseMigration.Phase.COMMITTING,
                    ProviderDatabaseMigration.Phase.COMMITTED, ProviderDatabaseMigration.Phase.UNKNOWN).contains(store.phase()));
        }
    }
    private static void writeReport(Path path, Object value) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(value);
        if (bytes.length > 8 * 1024 * 1024) throw new IOException("transfer.database-limit");
        Path temporary = path.resolveSibling(".database-report-" + UUID.randomUUID() + ".part");
        try {
            MigrationFiles.createPrivateFile(temporary);
            Files.write(temporary, bytes, LinkOption.NOFOLLOW_LINKS);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
