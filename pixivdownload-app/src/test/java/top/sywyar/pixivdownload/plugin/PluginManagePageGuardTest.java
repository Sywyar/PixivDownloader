package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 插件管理页（plugin-manage.html + plugin-manage/）静态接线守卫（轻量）：保证页面按职责拆分的模块、关键挂载锚点、
 * 对 admin 后端 API 的消费、以及「前端不硬编码插件清单」不被后续改动悄悄破坏。纯静态资源内容断言（不启 Spring 上下文）。
 * <p>
 * 仅守关键不变量、不过度 pin 实现细节：页面鉴权（ADMIN）由 {@code RouteAccessMirrorTest} 守、顶部导航 slot 契约
 * 由 {@code NavigationMarkupGuardTest} 守，本测试不重复。
 */
@DisplayName("插件管理页静态守卫：模块拆分 + 关键锚点 + 消费 admin API + 不硬编码插件清单")
class PluginManagePageGuardTest {

    private static final String STATIC_ROOT = "static/";
    private static final String HTML = "plugin-manage.html";
    private static final String CSS = "plugin-manage/plugin-manage.css";
    private static final String CORE = "plugin-manage/plugin-manage-core.js";
    private static final String API = "plugin-manage/plugin-manage-api.js";
    private static final String VIEWS = "plugin-manage/plugin-manage-views.js";
    private static final String INIT = "plugin-manage/plugin-manage-init.js";

    private static String read(String resource) throws IOException {
        String path = STATIC_ROOT + resource;
        try (InputStream in = PluginManagePageGuardTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new NoSuchFileException(path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("页面引用共享脚本（i18n / lang / theme / navigation）+ 四个页面模块 + CSS，且 init 最后加载")
    void pageWiresSharedScriptsAndModulesInOrder() throws IOException {
        String html = read(HTML);
        for (String ref : new String[]{
                "/js/pixiv-i18n.js", "/js/pixiv-lang-switcher.js", "/js/pixiv-theme.js", "/js/pixiv-navigation.js",
                "/plugin-manage/plugin-manage-core.js", "/plugin-manage/plugin-manage-api.js",
                "/plugin-manage/plugin-manage-views.js", "/plugin-manage/plugin-manage-init.js",
                "/plugin-manage/plugin-manage.css"}) {
            assertThat(html).as("plugin-manage.html 应引用 " + ref).contains(ref);
        }
        // init 收拢顶层启动，须在 core / api / views 之后加载。
        for (String earlier : new String[]{"plugin-manage-core.js", "plugin-manage-api.js", "plugin-manage-views.js"}) {
            assertThat(html.indexOf(earlier))
                    .as(earlier + " 应在 plugin-manage-init.js 之前加载")
                    .isLessThan(html.indexOf("plugin-manage-init.js"));
        }
    }

    @Test
    @DisplayName("页面关键锚点：顶部导航 slot（app.top）、语言 / 主题锚点、刷新 / 安装按钮、统计 / 标签 / 搜索 / 网格容器")
    void pageDeclaresMountAnchors() throws IOException {
        String html = read(HTML);
        assertThat(html).as("顶部应用导航栏 slot").contains("data-nav-slot=\"app.top\"");
        assertThat(html).as("语言 / 主题切换锚点").contains("id=\"langSwitcherAnchor\"");
        assertThat(html).as("刷新按钮").contains("id=\"refreshBtn\"");
        assertThat(html).as("从 URL 安装按钮").contains("id=\"installBtn\"");
        assertThat(html).as("概览统计容器").contains("id=\"pm-stats\"");
        assertThat(html).as("筛选标签容器").contains("id=\"pm-tabs\"");
        assertThat(html).as("搜索输入").contains("id=\"pm-search-input\"");
        assertThat(html).as("插件卡片网格容器").contains("id=\"pm-grid\"");
    }

    @Test
    @DisplayName("消费 admin 后端 API：core 声明 /api/plugins/status 与动词前缀 + 据响应 displayNamespace 解析（不硬编码）；api 走 POST 且携稳定机器码 code")
    void consumesAdminBackendApi() throws IOException {
        String core = read(CORE);
        assertThat(core).as("状态查询端点").contains("'/api/plugins/status'");
        assertThat(core).as("运行期动词端点前缀").contains("'/api/plugins/'");
        assertThat(core).as("据响应 displayNamespace 动态收集 i18n namespace（不硬编码）").contains("collectNamespaces");
        String api = read(API);
        assertThat(api).as("拉取状态走 STATUS_URL").contains("PM.STATUS_URL");
        assertThat(api).as("动词为 POST").contains("method: 'POST'");
        assertThat(api).as("失败携后端稳定机器码 code").contains("err.code");
    }

    @Test
    @DisplayName("前端不硬编码插件清单：列表由 /api/plugins/status 驱动，页面模块不含任何内置插件 id 字面量")
    void doesNotHardcodePluginList() throws IOException {
        String combined = read(CORE) + read(API) + read(VIEWS) + read(INIT);
        for (String pluginId : new String[]{
                "download-workbench", "gallery", "novel", "duplicate", "recovery-sentinel"}) {
            assertThat(combined)
                    .as("页面模块不得硬编码内置插件 id：" + pluginId + "（列表须由后端响应驱动）")
                    .doesNotContain("\"" + pluginId + "\"")
                    .doesNotContain("'" + pluginId + "'");
        }
    }

    @Test
    @DisplayName("页面专属样式独立成文件且支持深色模式（html[data-theme=\"dark\"] 覆盖）")
    void cssIsSeparateAndDarkModeAware() throws IOException {
        String css = read(CSS);
        assertThat(css).as("深色模式覆盖").contains("html[data-theme=\"dark\"]");
        assertThat(css).as("复用 CSS 变量主题方案").contains("--surface");
    }
}
