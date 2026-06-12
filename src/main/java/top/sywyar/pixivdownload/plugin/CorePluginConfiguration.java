package top.sywyar.pixivdownload.plugin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.core.db.PathPrefixColumns;

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
}
