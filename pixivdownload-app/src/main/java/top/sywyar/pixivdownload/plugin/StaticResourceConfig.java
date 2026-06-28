package top.sywyar.pixivdownload.plugin;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.accept.ContentNegotiationManager;

/**
 * 装配插件 / 核心静态资源的查询期 HandlerMapping。
 * <p>
 * 插件页面静态目录（如 {@code /pixiv-gallery/**}）与核心公共库（{@code /js/**} 等）
 * 因前缀更具体，优先于 Spring Boot 默认的 {@code classpath:/static/**} 整体放行被命中——
 * 当前两者 ClassLoader 同为应用加载器、解析到同一文件，行为不变；外置插件资源随其
 * ClassLoader 解析。映射在请求到达时读取 {@link StaticResourceRegistry} 快照，因此运行期注册、
 * 注销和重载都会立即反映到 HTTP 分发链。顶层 HTML / favicon 等未声明为 contribution 的资源
 * 仍由 Spring Boot 默认处理器兜底。
 */
@Configuration
public class StaticResourceConfig {

    @Bean
    DynamicStaticResourceHandlerMapping pluginStaticResourceHandlerMapping(
            StaticResourceRegistry staticResourceRegistry,
            ContentNegotiationManager contentNegotiationManager) {
        return new DynamicStaticResourceHandlerMapping(staticResourceRegistry, contentNegotiationManager);
    }
}
