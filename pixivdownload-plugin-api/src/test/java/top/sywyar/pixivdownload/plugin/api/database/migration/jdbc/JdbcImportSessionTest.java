package top.sywyar.pixivdownload.plugin.api.database.migration.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.*;

@DisplayName("JDBC 迁入会话的提交与释放边界")
class JdbcImportSessionTest {
    private static final MigrationSchema SCHEMA = new MigrationSchema(
            MigrationSnapshot.FORMAT_VERSION, List.of(), List.of(), List.of());
    private static final MigrationSnapshot SNAPSHOT = new MigrationSnapshot(
            MigrationSnapshot.FORMAT_VERSION, SCHEMA,
            new MigrationVerification(Map.of(), Map.of(), Map.of()),
            Path.of("fixture-backup"), "fixture-revision", List.of());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("开始提交后无论成功或抛错都不再回滚或重试，关闭仍释放连接")
    void attemptedCommitCannotBeAbortedOrRetried(boolean commitFails) throws Exception {
        var probe = new ConnectionProbe(commitFails);
        var session = session(probe, (table, rows) -> {});
        session.verify(SNAPSHOT);

        if (commitFails) {
            assertThatThrownBy(session::commit).isSameAs(probe.failure);
        } else {
            session.commit();
        }
        assertThatThrownBy(session::abort).hasMessage("MIGRATION_COMMIT_OUTCOME_UNKNOWN");
        assertThatThrownBy(session::commit).hasMessage("MIGRATION_SESSION_CLOSED");
        session.close();
        session.close();

        assertThat(probe.commits).isEqualTo(1);
        assertThat(probe.rollbacks).isZero();
        assertThat(probe.closes).isEqualTo(1);
    }

    @Test
    @DisplayName("提交前取消仍允许回滚且关闭只释放一次连接")
    void cancelledCheckpointLeavesTransactionAbortable() throws Exception {
        var probe = new ConnectionProbe(false);
        var cancellation = new CancellationException("fixture cancellation");
        var session = session(probe, (table, rows) -> { throw cancellation; });
        session.verify(SNAPSHOT);

        assertThatThrownBy(session::commit).isSameAs(cancellation);
        session.abort();
        session.abort();
        session.close();
        session.close();

        assertThat(probe.commits).isZero();
        assertThat(probe.rollbacks).isEqualTo(1);
        assertThat(probe.closes).isEqualTo(1);
    }

    @Test
    @DisplayName("未核验会话拒绝提交，关闭会回滚并释放连接")
    void unverifiedSessionRollsBackOnClose() throws Exception {
        var probe = new ConnectionProbe(false);
        var session = session(probe, (table, rows) -> {});

        assertThatThrownBy(session::commit).hasMessage("MIGRATION_NOT_VERIFIED");
        session.close();
        assertThat(probe.commits).isZero();
        assertThat(probe.rollbacks).isEqualTo(1);
        assertThat(probe.closes).isEqualTo(1);
    }

    private static JdbcImportSession session(ConnectionProbe probe, MigrationControl control) {
        return new JdbcImportSession(probe.connection, SCHEMA, control) {
            @Override protected MigrationRows rows(TableSpec table) { throw new AssertionError("unexpected table"); }
            @Override protected void validateSchema() {}
            @Override protected Map<String, Long> restoreSequences(Map<String, Long> expected) { return expected; }
        };
    }

    private static final class ConnectionProbe {
        final SQLException failure = new SQLException("fixture commit failure");
        final Connection connection;
        int commits, rollbacks, closes;

        ConnectionProbe(boolean commitFails) {
            connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "commit" -> { commits++; if (commitFails) throw failure; }
                            case "rollback" -> rollbacks++;
                            case "close" -> closes++;
                            default -> throw new AssertionError("unexpected connection call: " + method.getName());
                        }
                        return null;
                    });
        }
    }
}
