package top.sywyar.pixivdownload.core.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseProvider;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderSelection;
import top.sywyar.pixivdownload.plugin.storage.AppPluginDatabase;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("数据库连接池资源生命周期")
class DatabasePoolLifecycleTest {
    @TempDir Path directory;

    @Test
    @DisplayName("预检连接关闭失败时不创建新池，并隐藏驱动诊断")
    void doesNotStartPoolWhenProbeCloseFails() throws Exception {
        var physical = DatabaseProviderTestFixtures.sqlite(directory.resolve("probe.db"));
        var opened = new java.util.concurrent.CopyOnWriteArrayList<Connection>();
        var endpoint = mock(DatabaseConnectionTarget.class);
        when(endpoint.localFile()).thenReturn(Optional.empty());
        when(endpoint.openConnection(any())).thenAnswer(invocation -> {
            var connection = physical.openConnection();
            opened.add(connection);
            if (opened.size() != 1) return connection;
            var probe = spy(connection);
            doAnswer(call -> {
                call.callRealMethod();
                throw new java.sql.SQLException("controlled-driver-detail");
            }).when(probe).close();
            return probe;
        });
        try {
            assertThatThrownBy(() -> target(endpoint).openPool(2))
                    .isInstanceOf(java.io.IOException.class).hasMessage("DATABASE_CONNECTION_FAILED").hasNoCause();
            assertThat(opened).hasSize(1);
            assertThat(opened.get(0).isClosed()).isTrue();
        } finally {
            for (var connection : opened) connection.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("连接池初始化致命失败仍释放已取得的物理连接并传播原错误")
    void closesConnectionWhenInitializationFails(boolean threadDeath) throws Exception {
        var physical = DatabaseProviderTestFixtures.sqlite(directory.resolve("failed.db"));
        try (var connection = physical.openConnection()) {
            var exposed = spy(connection);
            Error failure = threadDeath ? new ThreadDeath() : new OutOfMemoryError("controlled-init-failure");
            doThrow(failure).when(exposed).getAutoCommit();
            var source = mock(DataSource.class);
            when(source.getConnection()).thenReturn(exposed);
            var config = new com.zaxxer.hikari.HikariConfig();
            config.setDataSource(source);
            assertThatThrownBy(() -> top.sywyar.pixivdownload.core.db.provider.DatabaseConnectionPool.create(config))
                    .isSameAs(failure);
            assertThat(connection.isClosed()).isTrue();
            verify(exposed).close();
        }
    }

    @Test
    @DisplayName("归还和淘汰连接保留事务行为，关闭一个池不影响另一个池")
    void keepsPoolAndTransactionOwnership() throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("first.db"));
        var otherTarget = DatabaseProviderTestFixtures.sqlite(directory.resolve("other.db"));
        try (var pool = target.openPool(2); var other = otherTarget.openPool(2)) {
            try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE records (id INTEGER)");
                connection.setAutoCommit(false);
                target.prepareTransaction(connection, false);
                statement.executeUpdate("INSERT INTO records VALUES (1)");
                connection.rollback();
                statement.executeUpdate("INSERT INTO records VALUES (2)");
                connection.commit();
            }
            try (var connection = pool.getConnection(); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT id FROM records")) {
                assertThat(connection.getAutoCommit()).isTrue();
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(2);
                assertThat(result.next()).isFalse();
            }
            for (int i = 0; i < 10; i++) {
                try (var connection = pool.getConnection()) { pool.evictConnection(connection); }
            }
            pool.close();
            try (var connection = other.getConnection(); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false,none", "true,false,none", "false,true,none", "true,true,none",
            "false,true,sql", "false,true,fatal"})
    @DisplayName("主库与私库关闭后释放创建途中及尚未入池的真实连接")
    void closesConnectionsStillBeingCreated(boolean privateStorage, boolean duringSetup, String closeFailureKind) throws Exception {
        var physical = DatabaseProviderTestFixtures.sqlite(directory.resolve("database.db"));
        var endpoint = mock(DatabaseConnectionTarget.class);
        when(endpoint.localFile()).thenReturn(Optional.empty());
        when(endpoint.privateStorage(any())).thenReturn(endpoint);
        Throwable closeFailure = switch (closeFailureKind) {
            case "sql" -> new java.sql.SQLException("controlled-driver-detail");
            case "fatal" -> new OutOfMemoryError("controlled-close-failure");
            default -> null;
        };
        var hold = new AtomicBoolean();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var late = new AtomicReference<Connection>();
        var creator = new AtomicReference<Thread>();
        when(endpoint.openConnection(any())).thenAnswer(invocation -> {
            Connection connection = physical.openConnection();
            if (hold.get()) {
                late.set(connection);
                creator.set(Thread.currentThread());
                if (duringSetup) {
                    var wrapped = spy(connection);
                    doAnswer(call -> {
                        started.countDown();
                        assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
                        return call.callRealMethod();
                    }).when(wrapped).getAutoCommit();
                    if (closeFailure != null) doAnswer(call -> {
                        call.callRealMethod();
                        throw closeFailure;
                    }).when(wrapped).close();
                    return wrapped;
                }
                started.countDown();
                assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            }
            return connection;
        });
        var target = target(endpoint);
        DataSource source = privateStorage
                ? new AppPluginDatabase("fixture-owner", target, mock(DataSource.class), () -> null)
                : target.openPool(2);
        var pool = (AutoCloseable) source;
        var executor = Executors.newSingleThreadExecutor();
        try (pool; var first = source.getConnection()) {
            hold.set(true);
            var waiting = executor.submit(() -> {
                try (var connection = source.getConnection()) { return connection.isValid(1); }
            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            waiting.cancel(true);
            first.close();
            if (closeFailure == null) pool.close();
            else if (closeFailure instanceof Error) assertThatThrownBy(pool::close).isSameAs(closeFailure);
            else assertThatThrownBy(pool::close).isInstanceOf(IllegalStateException.class)
                    .hasMessage("DATABASE_CONNECTION_CLOSE_FAILED").hasNoCause();
            if (duringSetup) assertThat(late.get().isClosed()).isTrue();
            release.countDown();
            creator.get().join(10_000);
            assertThat(creator.get().isAlive()).isFalse();
            assertThat(late.get().isClosed()).isTrue();
            assertThatThrownBy(() -> late.get().createStatement()).isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(source::getConnection).isInstanceOf(java.sql.SQLException.class);
        } finally {
            release.countDown();
            if (creator.get() != null) creator.get().join(10_000);
            if (late.get() != null) late.get().close();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private DatabaseTarget target(DatabaseConnectionTarget endpoint) throws Exception {
        var provider = mock(DatabaseProvider.class);
        when(provider.resolve(any())).thenReturn(endpoint);
        return DatabaseTarget.inspect(new DatabaseProviderSelection.Result("controlled-provider", "controlled-db",
                Optional.of(provider), DatabaseProviderSelection.Failure.NONE),
                new DatabaseConnectionSettings("", "", "", directory.resolve("unused.db")));
    }
}
