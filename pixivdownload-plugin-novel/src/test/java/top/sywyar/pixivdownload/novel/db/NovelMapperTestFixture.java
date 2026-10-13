package top.sywyar.pixivdownload.novel.db;

import top.sywyar.pixivdownload.databasesqlite.SqliteDatabasePlugin;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import top.sywyar.pixivdownload.plugin.api.storage.PluginMainDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

/** 小型 SQLite 夹具；完整宿主结构及事务另由应用集成测试覆盖。 */
public final class NovelMapperTestFixture {
    private NovelMapperTestFixture() {}
    public static NovelMapper mapper(DataSource source) {
        final top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget target;
        final MigrationSchema schema;
        try (var connection = source.getConnection()) {
            target = new SqliteDatabasePlugin().resolve(
                    new DatabaseConnectionSettings("", "", "", Path.of("unused-fixture.db")));
            schema = new MigrationSchema(1, target.readSchema(connection).values().stream().map(t ->
                    new TableSpec(t.name(), t.columns().stream().map(c ->
                            new ColumnSpec(c.name(), c.type(), c.notNull(), c.defaultValue(), c.primaryKeyPosition())).toList(),
                            List.of())).toList(), List.of(), List.of());
        } catch (SQLException e) { throw new IllegalStateException(e); }
        return new RelationalNovelMapper(new PluginMainDatabase() {
            public boolean available() { return true; }
            public <T> T read(DatabaseSession.Work<T> work) { return execute(work); }
            public <T> T transaction(DatabaseSession.Work<T> work) { return execute(work); }
            private <T> T execute(DatabaseSession.Work<T> work) {
                try (var connection = source.getConnection()) {
                    connection.setAutoCommit(false);
                    try (var session = target.storageSession(connection, schema)) {
                        T result = work.run(session);
                        if (session.rollbackOnly()) throw new SQLException("STORAGE_TRANSACTION_FAILED");
                        connection.commit();
                        return result;
                    } catch (SQLException | RuntimeException | Error failure) {
                        connection.rollback();
                        throw failure;
                    } finally { connection.setAutoCommit(true); }
                } catch (SQLException e) { throw new IllegalStateException(e); }
            }
        });
    }
}
