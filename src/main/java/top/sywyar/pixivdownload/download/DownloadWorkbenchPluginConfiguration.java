package top.sywyar.pixivdownload.download;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DownloadWorkbenchPluginConfiguration {

    @Bean
    public DownloadWorkbenchPlugin downloadWorkbenchPlugin() {
        return new DownloadWorkbenchPlugin();
    }
}
