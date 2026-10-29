package top.sywyar.pixivdownload.core.db.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.config.InstallIdentity;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.config.credential.PluginCredentialStore;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.migration.database.MigrationEndpoint;
import top.sywyar.pixivdownload.plugin.api.database.DatabasePrivateStorageSettings;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderSelection;
import top.sywyar.pixivdownload.plugin.storage.PluginDatabaseFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** 宿主只保存 owner 和 provider 变化凭据；源布局与变化验证完全由可信源 provider 拥有。 */
public final class PrivateDatabaseMigrationState {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RECEIPT_BYTES = 16 * 1024;
    private PrivateDatabaseMigrationState() {}

    public record SourceConfiguration(String path, String sha256, String dataDirectory) {}
    public record Receipt(int formatVersion, String owner, String installation, SourceConfiguration configuration,
                          String sourceProvider, String sourceTarget, String sourceRevision,
                          String targetProvider, String target, String targetPrivate) {}

    public static SourceConfiguration sourceConfiguration(Path config, Path data) throws IOException {
        PlainFilePathGuard.requirePlainRegularFile(config);
        return new SourceConfiguration(config.toRealPath().toString(), MigrationFiles.digest(config),
                data.toAbsolutePath().normalize().toString());
    }

    public static void recordVerified(String owner, top.sywyar.pixivdownload.migration.database.MigrationSource source, MigrationEndpoint targetMain,
            MigrationEndpoint targetPrivate, SourceConfiguration config, MigrationSnapshot snapshot) throws Exception {
        source.verifyRevision(snapshot.sourceRevision());
        var receipt = new Receipt(1, owner, InstallIdentity.get(), config, source.owner(), source.description(),
                snapshot.sourceRevision(), targetMain.owner(), targetMain.target().description(), targetPrivate.target().description());
        Path path = RuntimeFiles.privateDatabaseMigrationReceipt(owner);
        PlainFilePathGuard.requirePlainParent(path, true);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) PlainFilePathGuard.requirePlainRegularFile(path);
        byte[] bytes = JSON.writeValueAsBytes(receipt);
        if (bytes.length > MAX_RECEIPT_BYTES) throw required();
        Path temporary = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            MigrationFiles.createPrivateFile(temporary);
            try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    public static void requireReady(DatabaseTarget target, List<DatabaseProviderSelection.Result> providers) throws IOException {
        try { requireReady(target.migrationEndpoint(), providers); }
        catch (Exception failure) { throw required(); }
    }

    public static void requireReady(MigrationEndpoint target, List<DatabaseProviderSelection.Result> providers) throws Exception {
        String installation = InstallIdentity.get();
        Set<String> mustVerify = new TreeSet<>();
        for (var candidate : providers) {
            if (!candidate.available()) continue;
            var local = candidate.provider().orElseThrow().retainedLocalPrivateDatabases(RuntimeFiles.dataDirectory(), installation);
            if (!candidate.owner().equals(target.owner())) mustVerify.addAll(local.keySet());
        }
        Path root = RuntimeFiles.databaseMigrationDirectory().resolve("private");
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            MigrationFiles.requirePlain(root, true);
            try (var directories = Files.newDirectoryStream(root)) {
                for (Path directory : directories) {
                    MigrationFiles.requirePlain(directory, true);
                    String owner = directory.getFileName().toString();
                    if (!owner.matches("[a-z][a-z0-9]*(-[a-z0-9]+)*")) throw required();
                    mustVerify.add(owner);
                }
            }
        }
        for (String owner : mustVerify) {
            Receipt receipt = read(owner);
            if (!installation.equals(receipt.installation()) || !owner.equals(receipt.owner())) throw required();
            var source = resolveSource(receipt, providers);
            // 显式返回原存储不会消费迁入收据，是否已有目标增量仍由操作者负责。
            if (target.owner().equals(source.owner()) && target.target().description().equals(source.target().description())) continue;
            if (!target.owner().equals(receipt.targetProvider()) || !target.target().description().equals(receipt.target())) throw required();
            var settings = PluginDatabaseFactory.privateSettings(owner, new PluginCredentialStore());
            var privateSource = source.privateStorage(new DatabasePrivateStorageSettings(owner,
                    Path.of(receipt.configuration().dataDirectory()).resolve(owner), settings.installationId(), settings.password()));
            if (!privateSource.target().description().equals(receipt.sourceTarget())) throw required();
            privateSource.provider().verifySource(privateSource.target(), receipt.sourceRevision());
            var privateTarget = target.privateStorage(settings);
            if (!privateTarget.target().description().equals(receipt.targetPrivate())) throw required();
        }
    }
    private static MigrationEndpoint resolveSource(Receipt receipt, List<DatabaseProviderSelection.Result> providers) throws Exception {
        Path config = Path.of(receipt.configuration().path());
        PlainFilePathGuard.requirePlainRegularFile(config);
        if (!MigrationFiles.digest(config).equals(receipt.configuration().sha256())) throw required();
        var source = MigrationEndpoint.resolve(providers, DatabaseTarget.configurationEnvironment(
                "--spring.config.location=" + config.toUri()));
        if (!source.owner().equals(receipt.sourceProvider())) throw required();
        return source;
    }
    private static Receipt read(String owner) throws IOException {
        Path path = RuntimeFiles.privateDatabaseMigrationReceipt(owner);
        PlainFilePathGuard.requirePlainRegularFile(path);
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_RECEIPT_BYTES + 1);
            if (bytes.length > MAX_RECEIPT_BYTES) throw required();
            Receipt saved = JSON.readValue(bytes, Receipt.class);
            if (saved == null || saved.formatVersion() != 1) throw required();
            return saved;
        } catch (IOException invalid) { throw required(); }
    }
    private static IOException required() { return new IOException("DATABASE_PRIVATE_MIGRATION_REQUIRED"); }
}
