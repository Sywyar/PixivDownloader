package top.sywyar.pixivdownload.core.db;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class DatabaseConfig {

    private final DownloadConfig downloadConfig;
    private final DatabasePoolProperties databasePoolProperties;
    private final AppMessages messages;

    @Bean
    public DataSource dataSource() throws IOException {
        DatabaseTarget target = databaseTarget();
        log.info(messages.getForLog("core.db.log.engine", target.engine().databaseId()));
        return target.openPool(databasePoolProperties.getMaximumPoolSize());
    }

    @Bean
    public DatabaseTarget databaseTarget() throws IOException {
        return DatabaseTarget.resolve(databasePoolProperties, downloadConfig.getRootFolder());
    }

    @Bean
    public DatabaseIdProvider databaseIdProvider() {
        return dataSource -> {
            try (var connection = dataSource.getConnection()) {
                return DatabaseEngine.detect(connection).databaseId();
            }
        };
    }

    @Bean
    public JdbcTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource) {
            @Override
            protected void prepareTransactionalConnection(Connection connection, TransactionDefinition definition)
                    throws SQLException {
                super.prepareTransactionalConnection(connection, definition);
                if (definition.isReadOnly() || DatabaseEngine.detect(connection) != DatabaseEngine.SQLITE) return;
                var config = connection.unwrap(SQLiteConnection.class).getConnectionConfig();
                var previousMode = config.getTransactionMode();
                try {
                    // 此钩子位于业务 SQL 之前；重新开启尚为空的事务，提前取得写入资格。
                    // 只读事务仍用 DEFERRED，不能随写事务一起抢占 SQLite 的唯一写锁。
                    config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
                    connection.rollback();
                } finally {
                    config.setTransactionMode(previousMode);
                }
            }
        };
    }
}
