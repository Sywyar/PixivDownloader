package top.sywyar.pixivdownload.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.schema.CoreMigrationSchema;
import top.sywyar.pixivdownload.migration.database.*;
import top.sywyar.pixivdownload.plugin.api.database.DatabasePrivateStorageSettings;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.core.db.schema.PrivateDatabaseMigrationState;
import top.sywyar.pixivdownload.config.credential.PluginCredentialStore;
import top.sywyar.pixivdownload.plugin.storage.PluginDatabaseFactory;
import top.sywyar.pixivdownload.gui.SingleInstanceManager;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogProperties;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogTrustStores;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;
import top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogRevocationAdmissionPolicy;
import top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogTrustStateStore;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.sdk.SdkVersion;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 显式停机迁移入口；可信源与目标分别迁出和迁入，主库和私库使用同一合同。 */
public final class DatabaseMigration {
    private static final int MAX_SCHEMA_BYTES = 8 * 1024 * 1024;
    private static final ObjectMapper JSON = top.sywyar.pixivdownload.migration.database.MigrationSchemaJson.mapper();

    private DatabaseMigration() {}

    public static void main(String[] args) {
        Utf8ConsoleStreams.install();
        try {
            execute(args);
        } catch (Exception failure) {
            // 不打印 JDBC / Spring cause 链；异常中可能含完整失败行或认证信息。
            System.err.println(MessageBundles.get("core.db.migration-cli-failed"));
            System.exit(2);
        }
    }

    public static void execute(String[] args) throws Exception {
        if (args.length == 0 || List.of(args).contains("--help")) {
            System.out.println(MessageBundles.get("core.db.migration-usage"));
            return;
        }
        Map<String, String> options = parse(args);
        try (var instance = SingleInstanceManager.acquire()) {
            if (instance == null) throw new IllegalStateException("INSTANCE_BUSY");
            var environment = new StandardEnvironment();
            ConfigDataEnvironmentPostProcessor.applyTo(environment);
            try (var session = top.sywyar.pixivdownload.core.db.provider.DatabaseBootstrap.openToolSession(environment)) {
                if (!session.startupDiscovery().failures().isEmpty()) {
                    throw new IllegalStateException("PLUGIN_DISCOVERY_FAILED");
                }
                var catalog = declarations(session, options.containsKey("--retained-schema")
                        ? Path.of(options.get("--retained-schema")) : null);
                var manifest = catalog.manifest();
                if (options.containsKey("--export-schema")) {
                    writeNew(Path.of(options.get("--export-schema")), manifest);
                    return;
                }
                Path sourceConfig = Path.of(required(options, "--source-config")).toAbsolutePath();
                Path targetConfig = Path.of(required(options, "--target-config")).toAbsolutePath();
                Path sourceData = RuntimeFiles.dataDirectory();
                Path targetData = RuntimeFiles.dataDirectory();
                Path runDirectory = RuntimeFiles.databaseMigrationDirectory().resolve(UUID.randomUUID().toString());
                Path backup = options.containsKey("--backup") ? Path.of(options.get("--backup")) : runDirectory.resolve("source.backup");
                Path report = options.containsKey("--report") ? Path.of(options.get("--report")) : runDirectory.resolve("report.json");
                Set<String> rebuild = options.containsKey("--rebuild-objects")
                        ? Set.of(options.get("--rebuild-objects").split(",", -1)) : Set.of();
                writeNew(report, new MigrationReport(1, "IN_PROGRESS", manifest, Map.of(), null, List.of()));
                boolean orchestrationStarted = false;
                try {
                    if (!Files.isRegularFile(sourceConfig) || !Files.isRegularFile(targetConfig))
                        throw new IllegalStateException("DATABASE_CONFIG_MISSING");
                    var available = session.databaseProviders();
                    var source = MigrationEndpoint.resolve(available, DatabaseTarget.configurationEnvironment(
                            "--spring.config.location=" + sourceConfig.toUri()));
                    var target = MigrationEndpoint.resolve(available, DatabaseTarget.configurationEnvironment(
                            "--spring.config.location=" + targetConfig.toUri()));
                    var sourceConfiguration = PrivateDatabaseMigrationState.sourceConfiguration(sourceConfig, sourceData);
                    var stores = stores(catalog, source, target, runDirectory, backup, rebuild, true);
                    orchestrationStarted = true;
                    ProviderDatabaseMigration.migrate(stores, progress -> replaceReport(report,
                            new MigrationReport(progress.formatVersion(), progress.status(), manifest,
                                    progress.stores(), progress.failure(), progress.releaseFailures())),
                            (store, snapshot) -> {
                                if (!store.owner().equals("main")) PrivateDatabaseMigrationState.recordVerified(store.owner().substring("private:".length()),
                                        store.source(), target, store.target(), sourceConfiguration, snapshot);
                            }, (table, rows) -> {
                                if (Thread.currentThread().isInterrupted())
                                    throw new java.util.concurrent.CancellationException("MIGRATION_CANCELLED");
                            });
                } catch (Exception failure) {
                    if (!orchestrationStarted) {
                        try { replaceReport(report, new MigrationReport(1, "FAILED_TARGET_NOT_VERIFIED", manifest,
                                Map.of(), ProviderDatabaseMigration.safeFailure(failure), List.of())); }
                        catch (IOException reportFailure) { failure.addSuppressed(reportFailure); }
                    }
                    System.err.println(MessageBundles.get("core.db.migration-cli-failed-report", report.toAbsolutePath()));
                    throw failure;
                }
                System.out.println(MessageBundles.get("core.db.migration-completed", report.toAbsolutePath()));
            }
        }
    }


    public record Declarations(SchemaManifest manifest, DatabaseSchemaRegistry registry,
            Map<String, List<top.sywyar.pixivdownload.plugin.api.storage.relational.StorageMigration>> privateMigrations) {}

    public static Declarations declarations(PluginBootstrapSession session, Path retainedPath) throws IOException {
        var inventory = session.manager().inspectPlugins();
        var discovery = session.manager().toDiscoveryResult(inventory);
        if (!discovery.failures().isEmpty()) throw new IllegalStateException("PLUGIN_DISCOVERY_FAILED");
        var plugins = new PluginRegistry(BuiltInPlugins.createAll(), new PluginToggleProperties(), discovery);
        var registry = new DatabaseSchemaRegistry(new PluginRegistry(List.of()));
        List<OwnedSchema> declarations = new ArrayList<>();
        List<PluginIdentity> identities = new ArrayList<>();
        Map<String, List<top.sywyar.pixivdownload.plugin.api.storage.relational.StorageMigration>> privateMigrations = new java.util.HashMap<>();
        for (var plugin : plugins.allRegisteredPlugins()) {
            List<SchemaContribution> contributions = new ArrayList<>();
            List<SchemaContribution> privateContributions = new ArrayList<>();
            plugins.runBootContribution(plugin, () -> {
                contributions.addAll(plugin.plugin().schema());
                privateContributions.addAll(plugin.plugin().privateSchema());
                var upgrades = List.copyOf(plugin.plugin().privateStorageMigrations());
                top.sywyar.pixivdownload.plugin.api.storage.relational.PrivateStorageSchema.merge(privateContributions, upgrades);
                privateMigrations.put(plugin.id(), upgrades);
                if (!upgrades.isEmpty()) privateContributions.add(new SchemaContribution(
                        List.of(top.sywyar.pixivdownload.plugin.api.storage.relational.StorageMigration.historyTable()),
                        List.of(), List.of()));
            });
            if (plugins.lifecycleFailure(plugin).isPresent()) throw new IllegalStateException("PLUGIN_SCHEMA_FAILED");
            contributions.forEach(contribution -> registry.register(plugin.id(), contribution));
            declarations.add(new OwnedSchema(plugin.id(), contributions, privateContributions));
            String version = inventory.installations().stream()
                    .filter(installed -> installed.id().equals(plugin.id()))
                    .map(installed -> installed.descriptor().version()).findFirst().orElse(SdkVersion.VERSION);
            identities.add(new PluginIdentity(plugin.id(), plugin.packageId(), plugin.source().name(), version));
        }
        if (retainedPath != null) {
            SchemaManifest retained = readManifest(retainedPath);
            Set<String> installed = declarations.stream().map(OwnedSchema::owner).collect(java.util.stream.Collectors.toSet());
            for (var owner : retained.owners()) {
                if (installed.contains(owner.owner())) continue;
                if (owner.privateContributions().stream().flatMap(c -> c.tables().stream())
                        .anyMatch(t -> t.name().equals("_storage_migrations")))
                    throw new IllegalStateException("PRIVATE_MIGRATION_PLUGIN_REQUIRED");
                for (var contribution : owner.contributions()) registry.register(owner.owner(), contribution);
                declarations.add(owner);
            }
            retained.plugins().stream().filter(plugin -> !installed.contains(plugin.id())).forEach(identities::add);
        }
        return new Declarations(new SchemaManifest(SdkVersion.VERSION, declarations, identities), registry, Map.copyOf(privateMigrations));
    }

    public static List<ProviderDatabaseMigration.Store> stores(Declarations catalog, MigrationEndpoint source,
            MigrationEndpoint target, Path runDirectory, Path backup, Set<String> rebuild,
            boolean createCredentials) throws Exception {
        Path sourceData = RuntimeFiles.dataDirectory();
        Path targetData = sourceData;
        String installation = top.sywyar.pixivdownload.config.InstallIdentity.get();
        Set<String> owners = source.provider().privateDatabaseOwners(source.target(), sourceData, installation);
        List<ProviderDatabaseMigration.Store> stores = new ArrayList<>();
        stores.add(new ProviderDatabaseMigration.Store("main", source, target,
                new MigrationRequest(CoreMigrationSchema.create(catalog.registry(), true), backup, rebuild)));
        var credentials = owners.isEmpty() ? null : new PluginCredentialStore();
        for (String owner : owners.stream().sorted().toList()) {
            OwnedSchema declaration = catalog.manifest().owners().stream().filter(value -> value.owner().equals(owner))
                    .findFirst().orElseThrow(() -> new IllegalStateException("PRIVATE_SCHEMA_MISSING"));
            if (declaration.privateContributions().isEmpty()) throw new IllegalStateException("PRIVATE_SCHEMA_MISSING");
            var privateRegistry = new DatabaseSchemaRegistry(new PluginRegistry(List.of()));
            for (var contribution : declaration.privateContributions()) privateRegistry.register(owner, contribution);
            var settings = PluginDatabaseFactory.privateSettings(owner, credentials, createCredentials);
            var privateSource = source.privateStorage(new DatabasePrivateStorageSettings(owner,
                    sourceData.resolve(owner), settings.installationId(), settings.password()));
            var privateTarget = target.privateStorage(new DatabasePrivateStorageSettings(owner,
                    targetData.resolve(owner), settings.installationId(), settings.password()));
            stores.add(new ProviderDatabaseMigration.Store("private:" + owner, privateSource, privateTarget,
                    new MigrationRequest(CoreMigrationSchema.create(privateRegistry, false),
                            runDirectory.resolve("private").resolve(owner + ".backup"), Set.of(),
                            catalog.privateMigrations().getOrDefault(owner, List.of()))));
        }
        return List.copyOf(stores);
    }
    private static Map<String, String> parse(String[] args) {
        Set<String> known = Set.of("--source-config", "--target-config", "--backup", "--report", "--retained-schema",
                "--export-schema", "--rebuild-objects");
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!known.contains(args[i]) || i + 1 >= args.length || result.putIfAbsent(args[i], args[i + 1]) != null) {
                throw new IllegalArgumentException("INVALID_ARGUMENTS");
            }
        }
        return result;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("MISSING_ARGUMENT " + name);
        return value;
    }

    private static SchemaManifest readManifest(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_SCHEMA_BYTES + 1);
            if (bytes.length > MAX_SCHEMA_BYTES) throw new IOException("SCHEMA_MANIFEST_TOO_LARGE");
            SchemaManifest manifest = JSON.readValue(bytes, SchemaManifest.class);
            if (!SdkVersion.VERSION.equals(manifest.sdkVersion())) throw new IOException("SCHEMA_SDK_MISMATCH");
            if (manifest.owners().stream().map(OwnedSchema::owner).distinct().count() != manifest.owners().size()
                    || manifest.plugins().stream().map(PluginIdentity::id).distinct().count() != manifest.plugins().size()) {
                throw new IOException("DUPLICATE_SCHEMA_OWNER");
            }
            return manifest;
        }
    }

    private static void writeNew(Path path, Object value) throws IOException {
        MigrationFiles.createPrivateFile(path);
        byte[] json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        try (var output = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(json);
            while (bytes.hasRemaining()) output.write(bytes);
            output.force(true);
        }
    }

    private static void replaceReport(Path path, Object value) throws IOException {
        Path temporary = path.toAbsolutePath().resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            writeNew(temporary, value);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    public record OwnedSchema(String owner, List<SchemaContribution> contributions,
                              List<SchemaContribution> privateContributions) {
        public OwnedSchema {
            contributions = List.copyOf(contributions);
            privateContributions = List.copyOf(privateContributions);
        }
    }
    public record PluginIdentity(String id, String packageId, String source, String version) {}
    public record SchemaManifest(String sdkVersion, List<OwnedSchema> owners, List<PluginIdentity> plugins) {
        public SchemaManifest { owners = List.copyOf(owners); plugins = List.copyOf(plugins); }
    }
    public record MigrationReport(int formatVersion, String status, SchemaManifest schema,
                                  Map<String, ProviderDatabaseMigration.StoreReport> stores, String failure,
                                  List<String> releaseFailures) {
        public MigrationReport { stores = Map.copyOf(stores); releaseFailures = List.copyOf(releaseFailures); }
    }
}
