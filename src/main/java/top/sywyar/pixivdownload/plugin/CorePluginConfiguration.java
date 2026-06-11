package top.sywyar.pixivdownload.plugin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CorePluginConfiguration {

    @Bean
    public CorePlugin corePlugin() {
        return new CorePlugin();
    }
}
