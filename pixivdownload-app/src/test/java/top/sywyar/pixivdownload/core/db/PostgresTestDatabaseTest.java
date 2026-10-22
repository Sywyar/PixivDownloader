package top.sywyar.pixivdownload.core.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("PostgreSQL 测试夹具初始化资源释放")
class PostgresTestDatabaseTest {
    private enum FailurePoint { STATEMENT, CREATE_SCHEMA, CLOSE_STATEMENT }

    @ParameterizedTest
    @EnumSource(FailurePoint.class)
    @DisplayName("建 schema 失败时释放管理连接，已建成的 schema 仍清理")
    void closesAdminOnSchemaFailure(FailurePoint point) throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var cleanup = mock(Statement.class);
        var failure = new SQLException("fixture schema failure");
        when(connection.createStatement()).thenReturn(statement, cleanup);
        switch (point) {
            case STATEMENT -> when(connection.createStatement()).thenThrow(failure);
            case CREATE_SCHEMA -> when(statement.execute(anyString())).thenThrow(failure);
            case CLOSE_STATEMENT -> doThrow(failure).when(statement).close();
        }
        try (var driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(nullable(String.class), nullable(String.class), nullable(String.class)))
                    .thenReturn(connection);
            assertThatThrownBy(PostgresTestDatabase::new).isSameAs(failure);
        }
        verify(connection).close();
        if (point == FailurePoint.CLOSE_STATEMENT) verify(cleanup).execute(startsWith("DROP SCHEMA test_"));
        else verifyNoInteractions(cleanup);
    }

    @Test
    @DisplayName("初始化致命错误继续传播并释放已取得的管理连接")
    void closesAdminOnFatalFailure() throws Exception {
        var connection = mock(Connection.class);
        var failure = new LinkageError("fixture initialization failure");
        when(connection.createStatement()).thenThrow(failure);
        try (var driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(nullable(String.class), nullable(String.class), nullable(String.class)))
                    .thenReturn(connection);
            assertThatThrownBy(PostgresTestDatabase::new).isSameAs(failure);
        }
        verify(connection).close();
    }

    @Test
    @DisplayName("普通关闭失败保留为初始化错误的附加诊断")
    void retainsCleanupFailure() throws Exception {
        var connection = mock(Connection.class);
        var failure = new SQLException("fixture initialization failure");
        var cleanup = new SQLException("fixture cleanup failure");
        when(connection.createStatement()).thenThrow(failure);
        doThrow(cleanup).when(connection).close();
        try (var driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(nullable(String.class), nullable(String.class), nullable(String.class)))
                    .thenReturn(connection);
            assertThatThrownBy(PostgresTestDatabase::new).isSameAs(failure).hasSuppressedException(cleanup);
        }
    }

    @Test
    @DisplayName("关闭时的 JVM 致命错误优先传播并保留初始化错误")
    void propagatesFatalCleanup() throws Exception {
        var connection = mock(Connection.class);
        var failure = new SQLException("fixture initialization failure");
        var cleanup = new VirtualMachineError("fixture cleanup failure") {};
        when(connection.createStatement()).thenThrow(failure);
        doThrow(cleanup).when(connection).close();
        try (var driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(nullable(String.class), nullable(String.class), nullable(String.class)))
                    .thenReturn(connection);
            assertThatThrownBy(PostgresTestDatabase::new).isSameAs(cleanup).hasSuppressedException(failure);
        }
    }
}
