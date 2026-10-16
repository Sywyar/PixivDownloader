package top.sywyar.pixivdownload.plugin.web.resource;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.CacheControl;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import top.sywyar.pixivdownload.plugin.registry.web.StaticResourceRegistry;

/**
 * 插件映射按请求读取当前注册快照，核心目录提供兜底；两者共用内容版本与缓存校验。
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    private final StaticResourceVersioning versioning;

    public StaticResourceConfig(StaticResourceRegistry staticResourceRegistry) {
        this.versioning = new StaticResourceVersioning(staticResourceRegistry);
    }

    @Bean
    StaticResourceVersioning staticResourceVersioning() {
        return versioning;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(StaticResourceVersioning.CORE_LOCATION)
                .setCacheControl(CacheControl.noCache())
                .setUseLastModified(false)
                .setEtagGenerator(StaticResourceVersioning::contentHash)
                .resourceChain(false)
                .addTransformer(versioning);
    }

    @Bean
    DynamicStaticResourceHandlerMapping pluginStaticResourceHandlerMapping(
            StaticResourceRegistry staticResourceRegistry,
            ContentNegotiationManager contentNegotiationManager) {
        return new DynamicStaticResourceHandlerMapping(staticResourceRegistry, contentNegotiationManager, versioning);
    }
}
