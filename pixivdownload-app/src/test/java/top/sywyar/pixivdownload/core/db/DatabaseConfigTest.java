package top.sywyar.pixivdownload.core.db;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.migration.JsonToSqliteMigration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("SQLite 数据源配置")
class DatabaseConfigTest {

    @TempDir
    Path tempDir;

    private String previousDataDir;

    @BeforeEach
    void isolateDataDirectory() {
        previousDataDir = System.getProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, tempDir.resolve("data").toString());
    }

    @AfterEach
    void restoreDataDirectory() {
        if (previousDataDir == null) {
            System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        } else {
            System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, previousDataDir);
        }
    }

    @Test
    @DisplayName("连接池容量只读取宿主数据库设置而不随下载业务并发推导")
    void poolCapacityComesFromDatabaseProperties() throws Exception {
        DownloadConfig downloadConfig = new DownloadConfig();
        downloadConfig.setRootFolder(tempDir.resolve("downloads").toString());
        downloadConfig.setMaxConcurrent(120);
        DatabasePoolProperties poolProperties = new DatabasePoolProperties();
        poolProperties.setMaximumPoolSize(34);

        try (HikariDataSource dataSource = (HikariDataSource) new DatabaseConfig(
                downloadConfig, poolProperties, mock(AppMessages.class)).dataSource()) {
            assertThat(dataSource.getMaximumPoolSize()).isEqualTo(34);
        }
    }

    @Test
    @DisplayName("默认池的每条连接均使用 WAL 和等待超时，重开后仍读写原主库")
    void defaultConnectionsKeepSettingsAndExistingDatabase() throws Exception {
        Path database = tempDir.resolve("data/pixiv_download.db");
        Path downloads = tempDir.resolve("downloads");
        Files.createDirectories(database.getParent());
        Files.createDirectories(downloads);
        seed(database, "managed");
        seed(downloads.resolve("pixiv_download.db"), "legacy");
        DownloadConfig download = new DownloadConfig();
        download.setRootFolder(downloads.toString());
        DatabaseConfig config = new DatabaseConfig(download, new DatabasePoolProperties(), mock(AppMessages.class));

        try (HikariDataSource pool = (HikariDataSource) config.dataSource();
             Connection first = pool.getConnection();
             Connection second = pool.getConnection();
             Connection third = pool.getConnection()) {
            assertThat(pool.getMaximumPoolSize()).isEqualTo(DatabasePoolProperties.DEFAULT_MAXIMUM_POOL_SIZE);
            assertThat(pool.getMinimumIdle()).isEqualTo(1);
            for (Connection connection : java.util.List.of(first, second, third)) {
                assertThat(query(connection, "PRAGMA journal_mode")).isEqualTo("wal");
                assertThat(query(connection, "PRAGMA busy_timeout")).isEqualTo("5000");
                assertThat(query(connection, "SELECT value FROM original_data")).isEqualTo("managed");
            }
            try (var statement = first.createStatement()) {
                statement.executeUpdate("UPDATE original_data SET value = 'updated'");
            }
        }
        try (HikariDataSource pool = (HikariDataSource) config.dataSource();
             Connection connection = pool.getConnection()) {
            assertThat(query(connection, "SELECT value FROM original_data")).isEqualTo("updated");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + downloads.resolve("pixiv_download.db"))) {
            assertThat(query(connection, "SELECT value FROM original_data")).isEqualTo("legacy");
        }
        assertThat(Path.of(JsonToSqliteMigration.Options.fromRootFolder(downloads.toString()).dbPath()))
                .isEqualTo(database);
    }

    @Test
    @DisplayName("旧下载根数据库迁入 data 后保持原记录且后续启动复用迁入库")
    void migratesLegacyDatabaseWithoutStartingEmpty() throws Exception {
        Path downloads = Files.createDirectories(tempDir.resolve("downloads"));
        Path legacy = downloads.resolve("pixiv_download.db");
        seed(legacy, "retained");
        DownloadConfig download = new DownloadConfig();
        download.setRootFolder(downloads.toString());
        DatabaseConfig config = new DatabaseConfig(download, new DatabasePoolProperties(), mock(AppMessages.class));
        for (int restart = 0; restart < 2; restart++) {
            try (HikariDataSource pool = (HikariDataSource) config.dataSource();
                 Connection connection = pool.getConnection()) {
                assertThat(query(connection, "SELECT value FROM original_data")).isEqualTo("retained");
            }
        }
        assertThat(tempDir.resolve("data/pixiv_download.db")).isRegularFile();
        assertThat(legacy).doesNotExist();
    }

    private static void seed(Path database, String value) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE original_data (value TEXT)");
            try (var insert = connection.prepareStatement("INSERT INTO original_data VALUES (?)")) {
                insert.setString(1, value);
                insert.executeUpdate();
            }
        }
    }

    private static String query(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
    @Test
    @DisplayName("读后写事务等待写者且不阻塞普通查询，两次写入均成功提交")
    void readThenWriteTransactionsWaitWithoutBlockingQueries() throws Exception {
        var workers = Executors.newSingleThreadExecutor();
        var config = new DatabaseConfig(new DownloadConfig(), new DatabasePoolProperties(), mock(AppMessages.class));
        try (var source = (HikariDataSource) config.dataSource()) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE records (id INTEGER PRIMARY KEY, value TEXT)");
            var manager = config.transactionManager(source);
            var first = new TransactionTemplate(manager);
            Future<?> second = first.execute(status -> {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM records", Integer.class)).isZero();
                var attempting = new CountDownLatch(1);
                var writer = workers.submit(() -> {
                    attempting.countDown();
                    new TransactionTemplate(manager).executeWithoutResult(inner -> {
                        jdbc.queryForObject("SELECT COUNT(*) FROM records", Integer.class);
                        jdbc.update("INSERT INTO records VALUES (2, 'second')");
                    });
                });
                try {
                    assertThat(attempting.await(5, TimeUnit.SECONDS)).isTrue();
                    writer.get(250, TimeUnit.MILLISECONDS);
                } catch (TimeoutException waitingForWriter) {
                    // 写者等待不能阻塞普通查询或显式只读事务。
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                try (var reader = source.getConnection(); var read = reader.createStatement();
                     var rows = read.executeQuery("SELECT COUNT(*) FROM records")) {
                    assertThat(rows.next()).isTrue();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                jdbc.update("INSERT INTO records VALUES (1, 'first')");
                var reader = new TransactionTemplate(manager);
                reader.setReadOnly(true);
                reader.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                Integer visible = reader.execute(ignored -> jdbc.queryForObject("SELECT COUNT(*) FROM records", Integer.class));
                assertThat(visible).isZero();
                return writer;
            });
            second.get(5, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM records", Integer.class)).isEqualTo(2);
            first.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM records");
                status.setRollbackOnly();
            });
            jdbc.update("INSERT INTO records VALUES (3, 'after rollback')");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM records", Integer.class)).isEqualTo(3);
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
