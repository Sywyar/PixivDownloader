package top.sywyar.pixivdownload.download.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class DatabaseConfig {

    private final DownloadConfig downloadConfig;
    private final AppMessages messages;

    @Bean
    public DataSource dataSource() throws IOException {
        Path databasePath = RuntimeFiles.resolveDatabasePath(downloadConfig.getRootFolder());
        Files.createDirectories(databasePath.getParent());
        String url = "jdbc:sqlite:" + databasePath;
        log.info(messages.getForLog("download.db.log.path", url));

        // 池中每条物理连接都会带上这些 PRAGMA：
        // - busy_timeout=5000：SQLite 单写者模型下并发写会排队等待，而不是立刻抛 SQLITE_BUSY
        // - journal_mode=WAL：与启动时设置一致，读不阻塞写
        // HikariCP 的 dataSourceProperties 会经 DriverDataSource 传给驱动 driver.connect(url, props)
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setBusyTimeout(5000);
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("pixiv-sqlite");
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setJdbcUrl(url);
        hikari.setDataSourceProperties(sqliteConfig.toProperties());
        // SQLite 是单写者模型，池不宜大；并发写靠 busy_timeout 排队，WAL 下读不阻塞
        hikari.setMaximumPoolSize(8);
        hikari.setMinimumIdle(1);
        // 兜底：万一某条连接没套到 PRAGMA，连接初始化时再设一次 busy_timeout（仅支持单条语句）
        hikari.setConnectionInitSql("PRAGMA busy_timeout=5000");
        return new HikariDataSource(hikari);
    }
}
