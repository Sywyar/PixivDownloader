package top.sywyar.pixivdownload.core.asset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/** 文件事务测试使用独立 SQLite 主库；故障断言读取真实耐久标记。 */
public final class FileOperationTestSupport {
    private static final ThreadLocal<List<SingleConnectionDataSource>> SOURCES = ThreadLocal.withInitial(ArrayList::new);
    private FileOperationTestSupport() {}

    public static FileOperationJournal journal() {
        var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        SOURCES.get().add(source);
        return journal(source);
    }

    public static void close() {
        SOURCES.get().forEach(SingleConnectionDataSource::destroy);
        SOURCES.remove();
    }

    public static FileOperationJournal journal(DataSource source) {
        return journal(source, new DataSourceTransactionManager(source));
    }

    public static FileOperationJournal journal(DataSource source, org.springframework.transaction.PlatformTransactionManager manager) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE IF NOT EXISTS path_prefixes (id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS file_operation_entries (entry_id TEXT NOT NULL PRIMARY KEY, operation_id TEXT NOT NULL, ordinal INTEGER NOT NULL, target_path TEXT NOT NULL, old_hash TEXT, new_hash TEXT, committed INTEGER NOT NULL DEFAULT 0, UNIQUE(operation_id, ordinal), UNIQUE(target_path))");
        var config = new top.sywyar.pixivdownload.core.appconfig.DownloadConfig();
        config.setRootFolder("current-download-root");
        var paths = new PathPrefixCodec(new top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMapper(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(source)),
                config, top.sywyar.pixivdownload.i18n.TestI18nBeans.appMessages());
        paths.init();
        return new FileOperationJournal(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(source), manager, paths, null);
    }
}
