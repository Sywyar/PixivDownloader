package top.sywyar.pixivdownload.migration.database;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.DatabasePoolProperties;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.plugin.api.database.*;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderSelection;
import java.util.List;
import java.util.Objects;

/** 停机工具的精确可信 provider 目标；不经过运行期支持方言枚举，也不保存首次选择。 */
public record MigrationEndpoint(String owner, DatabaseProvider provider, DatabaseConnectionTarget target) implements MigrationSource {
    public MigrationEndpoint {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("DATABASE_PROVIDER_NOT_SELECTED");
        Objects.requireNonNull(provider); Objects.requireNonNull(target);
    }
    public static MigrationEndpoint resolve(List<DatabaseProviderSelection.Result> available, Environment environment)
            throws Exception {
        var settings = Binder.get(environment).bind("database", DatabasePoolProperties.class).orElseGet(DatabasePoolProperties::new);
        String owner = settings.getProvider();
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("DATABASE_PROVIDER_NOT_SELECTED");
        var candidates = available.stream().filter(p -> p.available() && p.owner().equals(owner)).toList();
        if (candidates.size() != 1) throw new IllegalStateException("DATABASE_PROVIDER_UNAVAILABLE");
        var target = DatabaseTarget.inspect(candidates.get(0), top.sywyar.pixivdownload.core.db.provider.DatabaseBootstrap.settings(settings.getJdbcUrl(),
                settings.getUsername(), settings.getPassword(), environment.getProperty(
                "download.root-folder", RuntimeFiles.DEFAULT_DOWNLOAD_ROOT)));
        return target.migrationEndpoint();
    }
    public MigrationEndpoint privateStorage(DatabasePrivateStorageSettings settings) throws Exception {
        return new MigrationEndpoint(owner, provider, target.privateStorage(settings));
    }
    @Override public String description() { return target.description(); }
    @Override public top.sywyar.pixivdownload.plugin.api.database.migration.DatabaseMigrationProvider.Inspection inspect(
            top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema schema) throws Exception {
        return provider.inspectMigration(target, schema, true);
    }
    @Override public top.sywyar.pixivdownload.plugin.api.database.migration.DatabaseExportSession export(
            top.sywyar.pixivdownload.plugin.api.database.migration.MigrationRequest request,
            top.sywyar.pixivdownload.plugin.api.database.migration.MigrationControl control) throws Exception {
        return provider.exportDatabase(target, request, control);
    }
    @Override public void verifyRevision(String revision) throws Exception { provider.verifySource(target, revision); }
    @Override public String toString() { return "MigrationEndpoint[owner=" + owner + "]"; }
}
