package top.sywyar.pixivdownload.stats;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StatsPluginConfiguration {

    @Bean
    public StatsPlugin statsPlugin() {
        return new StatsPlugin();
    }
}
