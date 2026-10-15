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
    @DisplayName("回填默认参数读取当前保存的代理并保留关闭状态")
    void backfillDefaultsFollowSavedProxy(@TempDir Path tempDir) throws Exception {
        var file = new top.sywyar.pixivdownload.gui.config.TestDesktopConfigFile(tempDir.resolve("config.yaml"));
        file.writeAll(java.util.Map.of("proxy.enabled", "true", "proxy.host", "192.0.2.55", "proxy.port", "17891"));
        var host = new AppDesktopUiHost(0, file);
        var first = host.defaultBackfillOptions();
        assertThat(first.useProxy()).isTrue();
        assertThat(first.proxyHost()).isEqualTo("192.0.2.55");
        assertThat(first.proxyPort()).isEqualTo(17891);

        file.writeAll(java.util.Map.of("proxy.enabled", "false", "proxy.port", "17892"));
        var changed = host.defaultBackfillOptions();
        assertThat(changed.useProxy()).isFalse();
        assertThat(changed.proxyPort()).isEqualTo(17892);
        assertThat(file.read("proxy.enabled")).isEqualTo("false");
        var startup = GuiLauncher.buildStartupBackfillOptions(host, tempDir.toString());
        assertThat(startup.useProxy()).isFalse();
        assertThat(startup.proxyHost()).isEqualTo("192.0.2.55");
        assertThat(startup.proxyPort()).isEqualTo(17892);
    }

    @Test
    @DisplayName("代理配置读取失败时不回退到另一条网络连接")
    void unreadableProxyConfigurationRejectsBackfillDefaults() throws Exception {
        var file = mock(DesktopUiHost.ConfigFile.class);
        when(file.readAll(org.mockito.ArgumentMatchers.anyCollection())).thenThrow(new java.io.IOException("fixture"));
        var host = new AppDesktopUiHost(0, file);
        org.assertj.core.api.Assertions.assertThatThrownBy(host::defaultBackfillOptions)
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

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
