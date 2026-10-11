package top.sywyar.pixivdownload.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.schema.SqliteToPostgresMigration;
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

/** 显式停机 SQLite → PostgreSQL 入口，不保存配置、不启动业务、不改动作品或私有插件库。 */
public final class DatabaseMigration {
    private static final int MAX_SCHEMA_BYTES = 8 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

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
            RuntimeFiles.resolveConfigYamlPath();
            var environment = new StandardEnvironment();
            ConfigDataEnvironmentPostProcessor.applyTo(environment);
            var properties = Binder.get(environment).bind("plugin-catalog", PluginCatalogProperties.class)
                    .orElseGet(PluginCatalogProperties::new);
            var repositories = new PluginRepositoryRegistry(properties);
            try (var session = PluginBootstrapSession.createProcess(RuntimeFiles.pluginsDirectory(),
                    PluginEnabledSnapshot.empty(), PluginCatalogTrustStores.verifierResolver(repositories))) {
                session.updateAdmissionPolicy(new PluginCatalogRevocationAdmissionPolicy(repositories, new PluginCatalogTrustStateStore()));
                session.start();
                if (!session.startupDiscovery().failures().isEmpty()) {
                    throw new IllegalStateException("PLUGIN_DISCOVERY_FAILED");
                }
                var plugins = new PluginRegistry(BuiltInPlugins.createAll(), new PluginToggleProperties(), session.startupDiscovery());
                var registry = new DatabaseSchemaRegistry(new PluginRegistry(List.of()));
                List<OwnedSchema> declarations = new ArrayList<>();
                List<PluginIdentity> identities = new ArrayList<>();
                for (var plugin : plugins.allRegisteredPlugins()) {
                    List<SchemaContribution> contributions = new ArrayList<>();
                    plugins.runBootContribution(plugin, () -> contributions.addAll(plugin.plugin().schema()));
                    if (plugins.lifecycleFailure(plugin).isPresent()) throw new IllegalStateException("PLUGIN_SCHEMA_FAILED");
                    contributions.forEach(contribution -> registry.register(plugin.id(), contribution));
                    declarations.add(new OwnedSchema(plugin.id(), contributions));
                    String version = session.startupInventory().installations().stream()
                            .filter(installed -> installed.id().equals(plugin.id()))
                            .map(installed -> installed.descriptor().version()).findFirst().orElse(SdkVersion.VERSION);
                    identities.add(new PluginIdentity(plugin.id(), plugin.packageId(), plugin.source().name(), version));
                }
                if (options.containsKey("--retained-schema")) {
                    SchemaManifest retained = readManifest(Path.of(options.get("--retained-schema")));
                    Set<String> installed = declarations.stream().map(OwnedSchema::owner).collect(java.util.stream.Collectors.toSet());
                    for (var owner : retained.owners()) {
                        if (installed.contains(owner.owner())) continue;
                        for (var contribution : owner.contributions()) registry.register(owner.owner(), contribution);
                        declarations.add(owner);
                    }
                    retained.plugins().stream().filter(plugin -> !installed.contains(plugin.id())).forEach(identities::add);
                }
                SchemaManifest manifest = new SchemaManifest(SdkVersion.VERSION, List.copyOf(declarations), List.copyOf(identities));
                if (options.containsKey("--export-schema")) {
                    writeNew(Path.of(options.get("--export-schema")), manifest);
                    return;
                }
                Path source = Path.of(required(options, "--source"));
                Path targetConfig = Path.of(required(options, "--target-config")).toAbsolutePath();
                if (!Files.isRegularFile(targetConfig)) throw new IOException("TARGET_CONFIG_MISSING");
                DatabaseTarget target = DatabaseTarget.fromConfiguration("--spring.config.location=" + targetConfig.toUri());
                Path runDirectory = RuntimeFiles.databaseMigrationDirectory().resolve(UUID.randomUUID().toString());
                Path backup = options.containsKey("--backup") ? Path.of(options.get("--backup")) : runDirectory.resolve("source.db");
                Path report = options.containsKey("--report") ? Path.of(options.get("--report")) : runDirectory.resolve("report.json");
                if (Files.exists(report)) throw new IOException("REPORT_EXISTS");
                Set<String> rebuild = options.containsKey("--rebuild-objects")
                        ? Set.of(options.get("--rebuild-objects").split(",", -1)) : Set.of();
                writeNew(report, new MigrationReport("IN_PROGRESS", manifest, null, null));
                SqliteToPostgresMigration.Result result;
                try {
                    result = SqliteToPostgresMigration.migrate(source, backup, target,
                            registry.contributions(), registry.mergedSchema(), rebuild);
                } catch (Exception failure) {
                    try {
                        replaceReport(report, new MigrationReport("FAILED_TARGET_NOT_VERIFIED", manifest, null,
                                safeFailure(failure)));
                    } catch (IOException reportFailure) { failure.addSuppressed(reportFailure); }
                    System.err.println(MessageBundles.get("core.db.migration-cli-failed-report", report.toAbsolutePath()));
                    throw failure;
                }
                // 提交后报告落盘失败时保留 IN_PROGRESS，不把已提交目标误报为已回滚或自动重试。
                replaceReport(report, new MigrationReport("DATA_VERIFIED", manifest, result, null));
                System.out.println(MessageBundles.get("core.db.migration-completed", report.toAbsolutePath()));
            }
        }
    }

    private static String safeFailure(Exception failure) {
        String message = failure.getMessage();
        return failure instanceof IllegalStateException && message != null && !message.contains("\n")
                ? message : failure.getClass().getSimpleName();
    }

    private static Map<String, String> parse(String[] args) {
        Set<String> known = Set.of("--source", "--target-config", "--backup", "--report", "--retained-schema",
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
        Files.createDirectories(path.toAbsolutePath().getParent());
        byte[] json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        try (var output = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
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

    public record OwnedSchema(String owner, List<SchemaContribution> contributions) {
        public OwnedSchema { contributions = List.copyOf(contributions); }
    }
    public record PluginIdentity(String id, String packageId, String source, String version) {}
    public record SchemaManifest(String sdkVersion, List<OwnedSchema> owners, List<PluginIdentity> plugins) {
        public SchemaManifest { owners = List.copyOf(owners); plugins = List.copyOf(plugins); }
    }
    public record MigrationReport(String status, SchemaManifest schema,
                                  SqliteToPostgresMigration.Result result, String failure) {}
}
