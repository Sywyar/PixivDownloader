package top.sywyar.pixivdownload.core.db;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("SQLite 数据源配置")
class DatabaseConfigTest {

    @TempDir
    Path tempDir;

    private String previousDataDir;

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
        previousDataDir = System.getProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, tempDir.resolve("data").toString());
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
}
