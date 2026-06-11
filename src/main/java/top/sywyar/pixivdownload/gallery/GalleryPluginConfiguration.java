package top.sywyar.pixivdownload.gallery;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GalleryPluginConfiguration {

    @Bean
    public GalleryPlugin galleryPlugin() {
        return new GalleryPlugin();
    }
}
