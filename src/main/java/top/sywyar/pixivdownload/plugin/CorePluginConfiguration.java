package top.sywyar.pixivdownload.plugin;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import top.sywyar.pixivdownload.core.db.DatabaseInitializer;
import top.sywyar.pixivdownload.core.db.PathPrefixColumns;
import top.sywyar.pixivdownload.i18n.AppMessages;

@Configuration
public class CorePluginConfiguration {

    @Bean
    public CorePlugin corePlugin() {
        return new CorePlugin();
    }

    @Bean
    public PathPrefixColumns pathPrefixColumns(DatabaseSchemaRegistry databaseSchemaRegistry) {
        return databaseSchemaRegistry.pathPrefixColumns();
    }

    @Bean
    public DatabaseInitializer databaseInitializer(DatabaseSchemaRegistry databaseSchemaRegistry,
                                                   JdbcTemplate jdbcTemplate,
                                                   AppMessages messages,
                                                   ApplicationEventPublisher eventPublisher) {
        return new DatabaseInitializer(jdbcTemplate, databaseSchemaRegistry.contributions(),
                databaseSchemaRegistry.mergedSchema(), messages, eventPublisher);
    }
}
