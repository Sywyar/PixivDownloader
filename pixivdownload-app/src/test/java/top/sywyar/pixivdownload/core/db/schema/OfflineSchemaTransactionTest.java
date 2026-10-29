package top.sywyar.pixivdownload.core.db.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.PostgresTestDatabase;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("离线结构初始化保留调用方事务所有权")
class OfflineSchemaTransactionTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("离线建表致命失败撤回 DDL 并保留外层写入")
    void fatalSchemaFailureRollsBack(boolean nested) throws Exception {
        var target = org.mockito.Mockito.spy(DatabaseProviderTestFixtures.sqlite(directory.resolve("fatal.db")));
        var failure = new OutOfMemoryError("controlled schema failure");
        try (var connection = target.openConnection()) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE transaction_probe (id INTEGER PRIMARY KEY)");
                if (nested) connection.setAutoCommit(false);
                statement.execute("INSERT INTO transaction_probe VALUES (1)");
            }
            org.mockito.Mockito.doAnswer(invocation -> {
                invocation.callRealMethod();
                throw failure;
            }).when(target).applySchema(org.mockito.ArgumentMatchers.eq(connection),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(false));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> DatabaseInitializer.prepareOffline(connection, target))
                    .isSameAs(failure);
            assertThat(target.readSchema(connection)).containsOnlyKeys("transaction_probe");
            assertThat(connection.getAutoCommit()).isEqualTo(!nested);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id FROM transaction_probe")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
            if (nested) connection.rollback();
        }
    }

    @Test @DisplayName("SQLite 初始化不会提交外层数据或 DDL")
    void sqlite() throws Exception {
        exercise(DatabaseProviderTestFixtures.sqlite(directory.resolve("schema.db")));
    }

    @Test @DisplayName("PostgreSQL 初始化不会提交外层数据或 DDL")
    @EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
    void postgres() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            exercise(DatabaseProviderTestFixtures.resolve(database.properties(), "unused"));
        }
    }

    private void exercise(DatabaseTarget target) throws Exception {
        try (var connection = target.openConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE transaction_probe (id INTEGER PRIMARY KEY)");
            connection.setAutoCommit(false);
            target.prepareTransaction(connection, false);
            statement.executeUpdate("INSERT INTO transaction_probe VALUES (1)");
            DatabaseInitializer.prepareOffline(connection, target);
            assertThat(connection.getAutoCommit()).isFalse();
            assertThat(target.readSchema(connection)).containsKey("artworks");
            connection.rollback();
            assertThat(target.readSchema(connection)).containsOnlyKeys("transaction_probe");
            try (var rows = statement.executeQuery("SELECT count(*) FROM transaction_probe")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isZero();
            }
            connection.rollback();
        }
    }
}
