package top.sywyar.pixivdownload.stats;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * stats 插件的 Bean 装配收敛点：业务 Bean（含 {@code @RestController}）均经
 * {@code @PluginManagedBean} 排除出根包扫描，由这里以 {@code @Bean} 显式提供。
 */
@Configuration
public class StatsPluginConfiguration {

    @Bean
    public StatsPlugin statsPlugin() {
        return new StatsPlugin();
    }

    @Bean
    public StatsRepository statsRepository(DataSource dataSource) {
        return new StatsRepository(dataSource);
    }

    @Bean
    public StatsService statsService(StatsRepository statsRepository) {
        return new StatsService(statsRepository);
    }

    @Bean
    public StatsController statsController(StatsService statsService) {
        return new StatsController(statsService);
    }
}
