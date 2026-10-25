package top.sywyar.pixivdownload.core.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.db.provider.DatabaseConnectionLease;

import java.io.OutputStream;
import java.io.Writer;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("JDBC 租约派生对象的数据库失效语义")
class DatabaseConnectionLeaseTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("租约接管时状态检查抛错仍释放已取得的真实连接")
    void failedAvailabilityCheckClosesConnection(boolean fatal,
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("acquire.db"));
        var error = new OutOfMemoryError("controlled availability failure");
        var failure = new IllegalStateException("controlled availability failure");
        try (var connection = target.openConnection()) {
            assertThatThrownBy(() -> DatabaseConnectionLease.wrap(connection, () -> {
                if (fatal) throw error;
                throw failure;
            })).isSameAs(fatal ? error : failure);
            assertThat(connection.isClosed()).isTrue();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("租约接管拒绝后保留不可用状态，关闭致命错误仍传播")
    void rejectedLeasePreservesAvailabilityFailure(boolean fatal) throws Exception {
        var connection = mock(Connection.class);
        Throwable cleanup = fatal ? new ThreadDeath() : new SQLException("controlled close failure");
        doThrow(cleanup).when(connection).close();
        Throwable actual = org.assertj.core.api.Assertions.catchThrowable(
                () -> DatabaseConnectionLease.wrap(connection, () -> false));
        if (fatal) {
            assertThat(actual).isSameAs(cleanup);
            assertThat(actual.getSuppressed()).singleElement().satisfies(failure ->
                    assertThat(failure).isInstanceOf(java.sql.SQLNonTransientConnectionException.class)
                            .hasMessage("DATABASE_UNAVAILABLE"));
        } else {
            assertThat(actual).isInstanceOf(java.sql.SQLNonTransientConnectionException.class)
                    .hasMessage("DATABASE_UNAVAILABLE").hasSuppressedException(cleanup);
        }
        verify(connection).close();
    }

    @Test
    @DisplayName("Spring 连接代理可准备真实 SQLite 事务，普通 unwrap 和失效准备仍拒绝")
    void springProxyPreservesProviderPreparationAndLease(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
            throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("lease.db"));
        var available = new AtomicBoolean(true);
        try (var connection = DatabaseConnectionLease.wrap(target.openConnection(), available::get)) {
            connection.setAutoCommit(false);
            var source = new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) wrapped -> {
                target.prepareTransaction(wrapped, false);
                return null;
            });
            assertThat(jdbc.queryForObject("SELECT storage_epoch_month(1704067200000, 'UTC')", String.class))
                    .isEqualTo("2024-01");
            assertThatThrownBy(() -> connection.unwrap(org.sqlite.SQLiteConnection.class))
                    .isInstanceOf(SQLException.class).hasMessage("DATABASE_UNWRAP_DENIED");
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) wrapped -> {
                available.set(false);
                assertThatThrownBy(() -> target.prepareTransaction(wrapped, false))
                        .isInstanceOf(SQLException.class).hasMessage("DATABASE_UNAVAILABLE");
                return null;
            });
        }
    }

    @Test
    @DisplayName("LOB 对象及写流失效后均拒绝写入，关闭不刷新驱动缓冲")
    void lobWritersCannotBypassAvailability() throws Exception {
        var available = new AtomicBoolean(true);
        var raw = mock(Connection.class);
        var blob = mock(Blob.class);
        var bytes = mock(OutputStream.class);
        var clob = mock(Clob.class);
        var chars = mock(Writer.class);
        when(raw.createBlob()).thenReturn(blob);
        when(raw.createClob()).thenReturn(clob);
        when(blob.setBinaryStream(1)).thenReturn(bytes);
        when(clob.setCharacterStream(1)).thenReturn(chars);
        try (var connection = DatabaseConnectionLease.wrap(raw, available::get)) {
            var leasedBlob = connection.createBlob();
            var leasedClob = connection.createClob();
            var output = leasedBlob.setBinaryStream(1);
            var writer = leasedClob.setCharacterStream(1);
            output.write(1);
            writer.write("a", 0, 1);
            available.set(false);
            assertThatThrownBy(() -> leasedBlob.setBytes(1, new byte[]{2}))
                    .isInstanceOf(SQLException.class).hasMessage("DATABASE_UNAVAILABLE");
            assertThatThrownBy(() -> leasedClob.setString(1, "b"))
                    .isInstanceOf(SQLException.class).hasMessage("DATABASE_UNAVAILABLE");
            assertThatThrownBy(() -> output.write(2)).hasMessage("DATABASE_UNAVAILABLE");
            assertThatThrownBy(output::flush).hasMessage("DATABASE_UNAVAILABLE");
            assertThatThrownBy(() -> writer.write("b", 0, 1)).hasMessage("DATABASE_UNAVAILABLE");
            assertThatThrownBy(writer::flush).hasMessage("DATABASE_UNAVAILABLE");
            output.close();
            writer.close();
            verify(bytes).write(1);
            verify(chars).write("a", 0, 1);
            verifyNoMoreInteractions(bytes, chars);
            verify(raw).abort(any());
            verify(blob, never()).free();
            verify(clob, never()).free();
            verify(blob, never()).setBytes(anyLong(), any());
            verify(clob, never()).setString(anyLong(), anyString());
        }
        verify(raw).close();
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
    @DisplayName("真实 PostgreSQL 数组可以通过受控租约正常绑定与读取")
    void postgresArraysRoundTrip() throws Exception {
        try (var fixture = new PostgresTestDatabase();
             var connection = DatabaseConnectionLease.wrap(fixture.pool().getConnection(), () -> true);
             var statement = connection.prepareStatement("SELECT ?::int[]")) {
            var array = connection.createArrayOf("int4", new Object[]{1, 2});
            statement.setArray(1, array);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat((Object[]) rows.getArray(1).getArray()).containsExactly(1, 2);
            } finally {
                array.free();
            }
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
    @DisplayName("真实 PostgreSQL LOB 缓冲写入在失效后关闭时不落库")
    void postgresLobCloseDoesNotFlushAfterFailure() throws Exception {
        var available = new AtomicBoolean(true);
        try (var fixture = new PostgresTestDatabase(); var admin = fixture.pool().getConnection()) {
            long oid;
            try (var statement = admin.createStatement();
                 var rows = statement.executeQuery("SELECT lo_from_bytea(0, decode('01', 'hex'))")) {
                rows.next();
                oid = rows.getLong(1);
            }
            try {
                try (var connection = DatabaseConnectionLease.wrap(fixture.pool().getConnection(), available::get)) {
                    connection.setAutoCommit(false);
                    try (var statement = connection.prepareStatement("SELECT ?::oid")) {
                        statement.setLong(1, oid);
                        try (var rows = statement.executeQuery()) {
                            rows.next();
                            var blob = rows.getBlob(1);
                            var output = blob.setBinaryStream(1);
                            output.write(2);
                            available.set(false);
                            output.close();
                            blob.free();
                            assertThat(connection.isClosed()).isTrue();
                        }
                    }
                }
                try (var statement = admin.prepareStatement("SELECT lo_get(?)")) {
                    statement.setLong(1, oid);
                    try (var rows = statement.executeQuery()) {
                        rows.next();
                        assertThat(rows.getBytes(1)).containsExactly((byte) 1);
                    }
                }
            } finally {
                try (var statement = admin.prepareStatement("SELECT lo_unlink(?)")) {
                    statement.setLong(1, oid);
                    statement.execute();
                }
            }
        }
    }

}
