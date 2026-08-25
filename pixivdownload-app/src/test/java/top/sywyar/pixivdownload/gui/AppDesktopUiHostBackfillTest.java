package top.sywyar.pixivdownload.gui;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("桌面宿主作品回填数据源")
class AppDesktopUiHostBackfillTest {

    @Test
    @DisplayName("候选查询借用注入的宿主连接池")
    void candidateCountBorrowsInjectedHostPool(@TempDir Path tempDir) throws Exception {
        Path databasePath = tempDir.resolve("host.db");
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl("jdbc:sqlite:" + databasePath);

        try (HikariDataSource pool = new HikariDataSource(hikari)) {
            try (Connection connection = pool.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE artworks (artwork_id INTEGER PRIMARY KEY)");
            }
            DataSource observedPool = mock(DataSource.class);
            when(observedPool.getConnection()).thenAnswer(ignored -> pool.getConnection());
            AppDesktopUiHost host = new AppDesktopUiHost(
                    0,
                    mock(DesktopUiHost.ConfigFile.class),
                    () -> observedPool
            );

            assertThat(host.countBackfillCandidates(new DesktopUiHost.BackfillOptions(
                    databasePath.toString(),
                    "127.0.0.1",
                    7890,
                    false,
                    0,
                    0,
                    true
            ))).isZero();
            verify(observedPool).getConnection();
        }
    }
}
