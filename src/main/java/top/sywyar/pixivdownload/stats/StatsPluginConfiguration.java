package top.sywyar.pixivdownload.stats;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.core.stats.StatsQueryStore;

/**
 * stats 插件的 Bean 装配收敛点：业务 Bean（含 {@code @RestController}）均经
 * {@code @PluginManagedBean} 排除出根包扫描，由这里以 {@code @Bean} 显式提供。
 * <p>
 * 统计数据的读取经核心 owned 语义接口 {@link StatsQueryStore}（实现
 * {@code core.stats.db.StatsQueryStoreImpl}，root 扫描的核心 {@code @Repository}）——插件托管 Bean
 * 不再自建 {@code JdbcTemplate} / 注入池化 {@code DataSource} 直读核心表（边界守卫见
 * {@code PluginApiDependencyGuardTest}）。
 */
@Configuration
public class StatsPluginConfiguration {

    @Bean
    public StatsPlugin statsPlugin() {
        return new StatsPlugin();
    }

    @Bean
    public StatsService statsService(StatsQueryStore statsQueryStore) {
        return new StatsService(statsQueryStore);
    }

    @Bean
    public StatsController statsController(StatsService statsService) {
        return new StatsController(statsService);
    }
}
