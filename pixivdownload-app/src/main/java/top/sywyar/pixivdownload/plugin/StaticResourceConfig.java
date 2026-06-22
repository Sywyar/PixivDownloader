package top.sywyar.pixivdownload.plugin;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 按 {@link StaticResourceRegistry} 的声明注册插件 / 核心静态资源的 ResourceHandler，
 * 解析路径经声明方插件的 ClassLoader（{@link DefaultResourceLoader}）。
 * <p>
 * 插件页面静态目录（如 {@code /pixiv-gallery/**}）与核心公共库（{@code /js/**} 等）
 * 因前缀更具体，优先于 Spring Boot 默认的 {@code classpath:/static/**} 整体放行被命中——
 * 当前两者 ClassLoader 同为应用加载器、解析到同一文件，行为零变化；物理拆分为插件 jar 后，
 * 插件资源随其 ClassLoader 解析，前端无需改动。顶层 HTML / favicon 等未声明为 contribution
 * 的资源仍由 Spring Boot 默认处理器兜底。
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    private final StaticResourceRegistry staticResourceRegistry;

    public StaticResourceConfig(StaticResourceRegistry staticResourceRegistry) {
        this.staticResourceRegistry = staticResourceRegistry;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        for (StaticResourceRegistry.RegisteredStaticResource registered : staticResourceRegistry.resources()) {
            String pattern = registered.contribution().publicPathPrefix() + "**";
            // 经声明方 ClassLoader 解析 classpath 目录资源（registry 已校验前缀 / 位置均以 "/" 结尾）。
            Resource location = new DefaultResourceLoader(registered.classLoader())
                    .getResource(registered.contribution().classpathLocation());
            registry.addResourceHandler(pattern).addResourceLocations(location);
        }
    }
}
