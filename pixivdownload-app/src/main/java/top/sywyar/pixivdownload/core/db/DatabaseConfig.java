package top.sywyar.pixivdownload.core.db;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.core.db.provider.DatabaseBootstrap;
import top.sywyar.pixivdownload.core.db.provider.ProviderDataSource;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class DatabaseConfig {

    private final DatabasePoolProperties databasePoolProperties;
    private final AppMessages messages;

    @Bean(destroyMethod = "close")
    public DataSource dataSource(DatabaseTarget target, PluginRegistry registry, PluginBootstrapSession session) throws IOException {
        if (!target.available()) return new UnavailableDataSource();
        var owner = registry.registeredPlugins().stream()
                .filter(plugin -> plugin.id().equals(target.providerOwner())).findFirst();
        if (owner.isEmpty() || registry.lifecycleFailure(owner.orElseThrow()).isPresent()) {
            target.invalidate();
            return new UnavailableDataSource();
        }
        try {
            registry.startFeature(owner.orElseThrow());
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            target.invalidate();
            registry.recordLifecycleFailure(owner.orElseThrow(), new IllegalStateException("DATABASE_PROVIDER_START_FAILED"));
            return new UnavailableDataSource();
        }
        log.info(messages.getForLog("core.db.log.engine", target.databaseId()));
        try {
            top.sywyar.pixivdownload.core.db.schema.PrivateDatabaseMigrationState.requireReady(target, session.databaseProviders());
            var pool = target.openPool(databasePoolProperties.getMaximumPoolSize());
            return new ProviderDataSource(pool, target, registry, owner.orElseThrow());
        } catch (IOException failure) {
            target.invalidate();
            log.warn(messages.getForLog("plugin.recovery.database-unavailable"));
            return new UnavailableDataSource();
        }
    }

    @Bean
    public DatabaseTarget databaseTarget(PluginBootstrapSession session, Environment environment) throws IOException {
        var binding = DatabaseBootstrap.bind(session, environment);
        return DatabaseTarget.fromBinding(binding);
    }

    @Bean
    public top.sywyar.pixivdownload.core.database.DatabaseAvailability databaseAvailability(DataSource dataSource) {
        return () -> !UnavailableDataSource.isUnavailable(dataSource);
    }

    @Bean
    public JdbcTransactionManager transactionManager(DataSource dataSource, DatabaseTarget target) {
        return new JdbcTransactionManager(dataSource) {
            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                if (UnavailableDataSource.isUnavailable(dataSource)) {
                    throw new top.sywyar.pixivdownload.core.database.DatabaseUnavailableException();
                }
                super.doBegin(transaction, definition);
            }

            @Override
            protected void prepareTransactionalConnection(Connection connection, TransactionDefinition definition)
                    throws SQLException {
                super.prepareTransactionalConnection(connection, definition);
                target.prepareTransaction(connection, definition.isReadOnly());
            }
        };
    }
}
