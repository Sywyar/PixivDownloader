package top.sywyar.pixivdownload.duplicate;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DuplicatePluginConfiguration {

    @Bean
    public DuplicatePlugin duplicatePlugin() {
        return new DuplicatePlugin();
    }
}
