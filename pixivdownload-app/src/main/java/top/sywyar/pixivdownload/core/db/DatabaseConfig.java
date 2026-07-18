package top.sywyar.pixivdownload.core.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
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
    private final DatabasePoolProperties databasePoolProperties;
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
        // 池容量由宿主数据库基础设施自己的设置控制。外置插件共享数据源，但插件业务并发数不参与
        // 宿主池容量推导，避免核心基础设施反向依赖任一可选插件的配置。
        // 否则宿主任务一旦把所有连接占满，HTTP 请求拿不到连接 → 等到 connectionTimeout（默认 30s）→ 页面观感"卡死"。
        // 注意：SQLite 单写者由 PRAGMA busy_timeout 在 SQLite 层保证（一次只有一个 writer），与池大小
        // 无关；增大池只是让读者和等写锁的线程有更多排队空间，不会出现多 writer 同时写。
        hikari.setMaximumPoolSize(databasePoolProperties.getMaximumPoolSize());
        hikari.setMinimumIdle(1);
        // 兜底：万一某条连接没套到 PRAGMA，连接初始化时再设一次 busy_timeout（仅支持单条语句）
        hikari.setConnectionInitSql("PRAGMA busy_timeout=5000");
        return new HikariDataSource(hikari);
    }
}
