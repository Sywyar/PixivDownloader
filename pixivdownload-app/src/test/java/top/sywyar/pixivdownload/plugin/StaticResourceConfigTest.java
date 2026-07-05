package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.web.StaticResourceConfig;

@DisplayName("StaticResourceConfig 查询期静态资源映射与服务行为")
class StaticResourceConfigTest {

    /**
     * 经 {@code @EnableWebMvc} 装配真实的 Spring MVC 静态资源处理链：
     * {@link DynamicStaticResourceHandlerMapping} 按 {@link StaticResourceRegistry} 当前快照生成
     * {@code ResourceHttpRequestHandler}，再由 MockMvc 的 {@code DispatcherServlet} 真实路由。
     */
    @Configuration
    @EnableWebMvc
    @Import(StaticResourceConfig.class)
    static class ServingConfig {

        @Bean
        StaticResourceRegistry staticResourceRegistry() {
            return new StaticResourceRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
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
    @DisplayName("core-only 不服务外置 novel 下载行为模块")
    void externalNovelStaticResourceIsAbsentInCoreOnlyContext() throws Exception {
        mockMvc.perform(get("/pixiv-novel-download/novel-queue-type.js"))
                .andExpect(status().isNotFound());
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

    @Test
    @DisplayName("上下文刷新后注册的精确文件立即可访问，注销后不可访问，再注册后恢复")
    void runtimeExactFileRegistrationIsImmediatelyReversible() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(ExactFileServingConfig.class);
        context.refresh();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

        StaticResourceRegistry registry = context.getBean(StaticResourceRegistry.class);
        mvc.perform(get("/setup.html"))
                .andExpect(status().isNotFound());

        registry.register("demo", ExactFileServingConfig.class.getClassLoader(), List.of(
                new StaticResourceContribution("demo", "classpath:/static/", "/setup.html", true)));
        mvc.perform(get("/setup.html"))
                .andExpect(status().isOk());

        registry.unregister("demo");
        mvc.perform(get("/setup.html"))
                .andExpect(status().isNotFound());

        registry.register("demo", ExactFileServingConfig.class.getClassLoader(), List.of(
                new StaticResourceContribution("demo", "classpath:/static/", "/setup.html", true)));
        mvc.perform(get("/setup.html"))
                .andExpect(status().isOk());
    }

    private static StaticResourceRegistry emptyRegistry() {
        return new StaticResourceRegistry(new PluginRegistry(List.of()));
    }

    @Configuration
    @EnableWebMvc
    @Import(StaticResourceConfig.class)
    static class ExactFileServingConfig {

        @Bean
        @Primary
        StaticResourceRegistry staticResourceRegistry() {
            return emptyRegistry();
        }
    }
}
