package top.sywyar.pixivdownload.novel;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NovelPluginConfiguration {

    @Bean
    public NovelPlugin novelPlugin() {
        return new NovelPlugin();
    }
}
