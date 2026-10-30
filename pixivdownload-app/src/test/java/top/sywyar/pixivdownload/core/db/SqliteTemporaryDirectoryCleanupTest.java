package top.sywyar.pixivdownload.core.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SQLite WAL 关闭后的临时目录清理")
class SqliteTemporaryDirectoryCleanupTest {
    @TempDir Path directory;

    @AfterEach
    void cleanupDirectory() throws Exception {
        TestDirectoryCleanup.delete(directory);
        assertThat(directory).doesNotExist();
    }

    @Test
    @DisplayName("关闭 WAL 连接后清理文件及目录，JUnit 最终清理仍启用")
    void deletesClosedWalDatabase() throws Exception {
        Path database = directory.resolve("fixture.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            try (var mode = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                assertThat(mode.next()).isTrue();
                assertThat(mode.getString(1)).isEqualToIgnoringCase("wal");
            }
            statement.execute("CREATE TABLE fixture(value TEXT)");
            statement.execute("INSERT INTO fixture VALUES ('retained')");
            assertThat(Files.size(directory.resolve("fixture.db-wal"))).isPositive();
            try (var rows = statement.executeQuery("SELECT value FROM fixture")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("retained");
            }
        }
    }
}
