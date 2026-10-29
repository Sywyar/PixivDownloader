package top.sywyar.pixivdownload.migration.database;

import top.sywyar.pixivdownload.plugin.api.database.migration.*;

/** 实时 provider 或已核验归档提供的迁出端，不向归档授予 JDBC 连接能力。 */
public interface MigrationSource {
    String owner();
    String description();
    DatabaseMigrationProvider.Inspection inspect(MigrationSchema schema) throws Exception;
    DatabaseExportSession export(MigrationRequest request, MigrationControl control) throws Exception;
    void verifyRevision(String revision) throws Exception;
}
