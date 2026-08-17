package top.sywyar.pixivdownload.plugin.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.sqlite.SQLiteConfig;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.storage.PluginDataSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Host-owned pool for one plugin's private SQLite database. */
public final class AppPluginDataSource extends HikariDataSource implements PluginDataSource {

    public AppPluginDataSource(String ownerPluginId) {
        super(configuration(ownerPluginId));
    }

    private static HikariConfig configuration(String ownerPluginId) {
        Path database = RuntimeFiles.resolvePluginDataDirectory(ownerPluginId)
                .resolve("plugin.db")
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(database.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create plugin data directory", e);
        }

        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.setBusyTimeout(5000);
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("plugin-sqlite-" + ownerPluginId);
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setJdbcUrl("jdbc:sqlite:" + database);
        hikari.setDataSourceProperties(sqlite.toProperties());
        hikari.setMaximumPoolSize(2);
        hikari.setMinimumIdle(0);
        hikari.setInitializationFailTimeout(-1);
        hikari.setConnectionInitSql("PRAGMA busy_timeout=5000");
        return hikari;
    }
}
