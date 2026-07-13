package top.sywyar.pixivdownload.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixColumns;
import top.sywyar.pixivdownload.core.schedule.db.migration.LegacyScheduledTaskMigrationCoordinator;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;

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

    @Bean
    public LegacyScheduledTaskMigrationCoordinator legacyScheduledTaskMigrationCoordinator(
            DatabaseInitializer databaseInitializer,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            ScheduleCapabilityRegistry scheduleCapabilityRegistry) {
        // 显式依赖 initializer：插件 registrar 取得协调器时，canonical 列与新事实表已就绪。
        return new LegacyScheduledTaskMigrationCoordinator(
                jdbcTemplate, objectMapper, transactionManager, scheduleCapabilityRegistry);
    }
}
