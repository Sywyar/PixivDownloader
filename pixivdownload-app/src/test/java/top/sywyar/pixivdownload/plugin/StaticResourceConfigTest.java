package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("StaticResourceConfig 静态资源处理器注册与服务行为")
class StaticResourceConfigTest {

    /**
     * 经 {@code @EnableWebMvc} 装配真实的 Spring MVC 静态资源处理链：{@link StaticResourceConfig}
     * 作为 {@code WebMvcConfigurer} 被收集，按 {@link StaticResourceRegistry} 声明注册的
     * {@code ResourceHttpRequestHandler} 进入 {@code resourceHandlerMapping}，再由 MockMvc 的
     * {@code DispatcherServlet} 真实路由——下方服务行为用例据此断言资源真的被服务，而非仅断言
     * handler pattern 已注册。
     */
    @Configuration
    @EnableWebMvc
    static class ServingConfig {

        @Bean
        StaticResourceRegistry staticResourceRegistry() {
            return new StaticResourceRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        }

        @Bean
        StaticResourceConfig staticResourceConfig(StaticResourceRegistry registry) {
            return new StaticResourceConfig(registry);
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(ServingConfig.class);
        context.refresh();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    // --- 注册断言（pattern 级，保留作辅助证据，但不作为唯一证据）---

    /** 暴露受保护的 {@code getHandlerMapping()} 以便断言注册的 URL 模式。 */
    private static final class TestableRegistry extends ResourceHandlerRegistry {
        TestableRegistry() {
            super(new GenericWebApplicationContext(), new MockServletContext());
        }

        @Override
        public AbstractHandlerMapping getHandlerMapping() {
            return super.getHandlerMapping();
        }
    }

    @Test
    @DisplayName("按 StaticResourceRegistry 声明为每条 contribution 注册 <前缀>** 处理器")
    void registersHandlerPerContribution() {
        StaticResourceRegistry registry =
                new StaticResourceRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        StaticResourceConfig config = new StaticResourceConfig(registry);

        TestableRegistry handlerRegistry = new TestableRegistry();
        config.addResourceHandlers(handlerRegistry);

        SimpleUrlHandlerMapping mapping = (SimpleUrlHandlerMapping) handlerRegistry.getHandlerMapping();
        assertThat(mapping).isNotNull();
        assertThat(mapping.getUrlMap().keySet())
                .contains("/js/**", "/css/**", "/vendor/**",
                        "/pixiv-gallery/**", "/pixiv-artwork/**", "/pixiv-showcase/**", "/pixiv-series/**",
                        "/pixiv-novel-gallery/**", "/pixiv-novel/**",
                        "/pixiv-duplicates/**");
    }

    // --- 服务行为断言（真实经 DispatcherServlet → ResourceHttpRequestHandler）---

    @Test
    @DisplayName("核心公共库 /js/*.js 经 contribution handler 返回 200，MIME 为 JS 类型")
    void servesCorePublicJavascript() throws Exception {
        mockMvc.perform(get("/js/pixiv-i18n.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("javascript")));
    }

    @Test
    @DisplayName("核心公共库 /css/*.css 经 contribution handler 返回 200，MIME 为 text/css")
    void servesCorePublicCss() throws Exception {
        mockMvc.perform(get("/css/admin-visibility.css"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/css")));
    }

    @Test
    @DisplayName("插件页面静态目录 /pixiv-gallery/*.js 经 contribution handler 返回 200")
    void servesPluginPageStaticResource() throws Exception {
        mockMvc.perform(get("/pixiv-gallery/gallery-core.js"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("核心 Vue 运行时 /vendor/vue/vue.global.prod.js 经既有 /vendor/ contribution 返回 200，MIME 为 JS 类型")
    void servesVendoredVueRuntime() throws Exception {
        // 单一来源的 Vue 全局构建版落在既有 classpath:/static/vendor/ → /vendor/ 处理器下，
        // 无需新增 StaticResourceContribution / 路由声明即被 serving。
        mockMvc.perform(get("/vendor/vue/vue.global.prod.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("javascript")));
    }

    @Test
    @DisplayName("共享 Vue 槽位挂载 helper /js/pixiv-vue.js 经 /js/ contribution 返回 200，MIME 为 JS 类型")
    void servesSharedVueMountHelper() throws Exception {
        // 小说搜索网格经此 helper 懒加载核心 Vue 运行时；缺失即页面侧优雅回退命令式渲染。
        mockMvc.perform(get("/js/pixiv-vue.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("javascript")));
    }

    @Test
    @DisplayName("不存在的静态资源返回 404")
    void missingResourceReturnsNotFound() throws Exception {
        mockMvc.perform(get("/js/no-such-resource-xyz.js"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("目录穿越路径不泄露 contribution 位置之外的资源，返回 4xx")
    void directoryTraversalRejected() throws Exception {
        mockMvc.perform(get("/js/../../application.properties"))
                .andExpect(status().is4xxClientError());
    }
}
