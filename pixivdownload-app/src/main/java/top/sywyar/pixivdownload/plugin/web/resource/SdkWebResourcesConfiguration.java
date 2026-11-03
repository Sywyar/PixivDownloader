package top.sywyar.pixivdownload.plugin.web.resource;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** SDK 固定浏览器资源不依赖可写的操作策略存储，仍经过宿主路由鉴权。 */
@Configuration(proxyBeanMethods = false)
public class SdkWebResourcesConfiguration implements WebMvcConfigurer {
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/sdk/values/**")
                .addResourceLocations("classpath:/META-INF/pixivdownload/web/")
                .setCacheControl(CacheControl.noCache());
    }
}
