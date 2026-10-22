package top.sywyar.pixivdownload.plugin.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.UnavailableDataSource;
import top.sywyar.pixivdownload.databasesqlite.SqliteDatabasePlugin;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings;
import top.sywyar.pixivdownload.plugin.api.database.DatabasePrivateStorageSettings;
import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;
import top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderBinding;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderSelection;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("所选 provider 的 owner 私有连接池")
class AppPluginDatabaseTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("私库会话关闭失败必须回滚写入并允许后续事务重试")
    void sessionCloseFailureRollsBack(boolean fatal) throws Exception {
        var selected = spy(target());
        var privateTarget = spy(selected.privateStorage(new DatabasePrivateStorageSettings(
                "close-fixture", directory.resolve("close-fixture"), "fixture-installation", "fixture-private-secret")));
        doReturn(privateTarget).when(selected).privateStorage(any());
        var failClose = new AtomicBoolean();
        Throwable failure = fatal ? new OutOfMemoryError("fixture") : new IllegalStateException("fixture");
        doAnswer(call -> {
            var session = spy((DatabaseSession) call.callRealMethod());
            doAnswer(close -> {
                close.callRealMethod();
                if (failClose.get()) throw failure;
                return null;
            }).when(session).close();
            return session;
        }).when(privateTarget).storageSession(any(), any());
        try (var database = database("close-fixture", selected, mock(DataSource.class))) {
            database.initialize(List.of(new SchemaContribution(List.of(new TableSpec("facts", List.of(
                    new ColumnSpec("id", "INTEGER", true, null, 1)), List.of())), List.of(), List.of())));
            failClose.set(true);
            assertThatThrownBy(() -> database.transaction(session ->
                    session.insert("facts", Map.of("id", 1L), DatabaseSession.Conflict.error())))
                    .isSameAs(failure);
            failClose.set(false);
            try (var connection = database.getConnection(); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM facts")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
            int inserted = database.transaction(session ->
                    session.insert("facts", Map.of("id", 1L), DatabaseSession.Conflict.error()));
            assertThat(inserted).isEqualTo(1);
        }
    }

    @Test
    @Timeout(20)
    @DisplayName("私库由 SQLite 插件打开，多连接不死锁且不同 owner 保留各自数据")
    void opensOnlyOwnerDatabaseThroughSelectedProvider() throws Exception {
        var target = target();
        for (String owner : java.util.List.of("first-owner", "second-owner")) {
            try (var database = database(owner, target, mock(DataSource.class));
                 var connection = database.getConnection(); var second = database.getConnection();
                 var statement = connection.createStatement()) {
                var expected = target.privateStorage(new DatabasePrivateStorageSettings(
                        owner, directory.resolve(owner), "fixture-installation", "fixture-private-secret"));
                expected.requireConnectionTarget(connection);
                expected.requireConnectionTarget(second);
                assertThat(directory.resolve(owner).resolve("plugin.db")).isRegularFile();
                assertThat(second.getMetaData().getURL()).isEqualTo(connection.getMetaData().getURL());
                statement.execute("CREATE TABLE facts(value TEXT)");
                try (var insert = connection.prepareStatement("INSERT INTO facts VALUES (?)")) {
                    insert.setString(1, owner);
                    insert.executeUpdate();
                }
            }
            try (var database = database(owner, target, mock(DataSource.class));
                 var connection = database.getConnection(); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT value FROM facts")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(owner);
            }
        }
        assertThat(directory.resolve("main.db")).doesNotExist();
    }

    @Test
    @DisplayName("主库不可用时 SDK 私库不得访问凭据或创建文件，关闭后同样拒绝")
    void unavailableDatabaseHasNoPrivateSideEffects() throws Exception {
        try (var database = new AppPluginDatabase("fixture-owner", target(), new UnavailableDataSource(), () -> {
            throw new AssertionError("must not load or create private credentials");
        })) {
            assertThat(database.available()).isFalse();
            assertThatThrownBy(database::getConnection).isInstanceOf(SQLException.class)
                    .hasMessage("DATABASE_UNAVAILABLE");
        }
        assertThat(directory.resolve("fixture-owner")).doesNotExist();
    }

    @Test
    @DisplayName("宿主绑定 owner 时拒绝目录逃逸")
    void rejectsOwnerPathTraversal() {
        var factory = new PluginDatabaseFactory(null, null, null);
        assertThatThrownBy(() -> factory.create("../outside")).isInstanceOf(IllegalArgumentException.class);
        assertThat(directory.getParent().resolve("outside/plugin.db")).doesNotExist();
    }

    @Test
    @Timeout(20)
    @DisplayName("借用等待被中断后不永久撤销私库，连接归还后仍能读写")
    void interruptedBorrowDoesNotPoisonPrivatePool() throws Exception {
        try (var database = database("pool-fixture", target(), mock(DataSource.class));
             var one = database.getConnection(); var two = database.getConnection()) {
            var entered = new java.util.concurrent.CountDownLatch(1);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread borrower = new Thread(() -> {
                entered.countDown();
                Thread.currentThread().interrupt();
                try (var ignored = database.getConnection()) {
                    failure.set(new AssertionError("occupied pool must reject interrupted borrowing"));
                } catch (SQLException expected) {
                    failure.set(expected);
                }
            });
            borrower.start();
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            borrower.join(2000);
            assertThat(borrower.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(SQLException.class);
            assertThat(database.available()).isTrue();
            two.close();
            try (var recovered = database.getConnection(); var statement = recovered.createStatement()) {
                assertThat(statement.executeQuery("SELECT 1").next()).isTrue();
            }
        }
    }

    private AppPluginDatabase database(String owner, DatabaseTarget target, DataSource main) {
        return new AppPluginDatabase(owner, target, main, () -> new DatabasePrivateStorageSettings(
                owner, directory.resolve(owner), "fixture-installation", "fixture-private-secret"));
    }

    @Test
    @DisplayName("已借连接失效后不能执行或提交，元数据与结果集也不能泄出未门控连接")
    void leasedObjectsStopAfterProviderFailure() throws Exception {
        var selected = target();
        try (var database = database("lease-fixture", selected, mock(DataSource.class));
             var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE facts(value INTEGER)");
            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO facts VALUES (1)");
            try (var rows = statement.executeQuery("SELECT value FROM facts")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getStatement().getConnection()).isSameAs(connection);
                assertThat(connection.getMetaData().getConnection()).isSameAs(connection);
                assertThatThrownBy(() -> connection.unwrap(org.sqlite.SQLiteConnection.class))
                        .isInstanceOf(SQLException.class).hasMessage("DATABASE_UNWRAP_DENIED");
                selected.invalidate();
                assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO facts VALUES (2)"))
                        .isInstanceOf(SQLException.class).hasMessage("DATABASE_UNAVAILABLE");
                assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class).hasMessage("DATABASE_UNAVAILABLE");
                assertThatThrownBy(rows::next).isInstanceOf(SQLException.class).hasMessage("DATABASE_UNAVAILABLE");
                assertThat(connection.isClosed()).isTrue();
            }
        }
        try (var database = database("lease-fixture", target(), mock(DataSource.class));
             var connection = database.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM facts")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isZero();
        }
    }

    private DatabaseTarget target() {
        var provider = new SqliteDatabasePlugin();
        var selected = new DatabaseProviderSelection.Result(provider.id(), provider.databaseId(), Optional.of(provider),
                DatabaseProviderSelection.Failure.NONE);
        return DatabaseTarget.fromBinding(DatabaseProviderBinding.resolve(selected,
                () -> new DatabaseConnectionSettings("", "", "", directory.resolve("main.db")),
                java.util.function.UnaryOperator.identity()));
    }
}
