package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.Properties;

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
    private static final String I18N_ZH = "i18n/web/plugins.properties";
    private static final String I18N_EN = "i18n/web/plugins_en.properties";

    private static String read(String resource) throws IOException {
        String path = STATIC_ROOT + resource;
        try (InputStream in = PluginManagePageGuardTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new NoSuchFileException(path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Properties readProperties(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = PluginManagePageGuardTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new NoSuchFileException(resource);
            }
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    @Test
    @DisplayName("页面引用共享脚本（含 PixivFeedback）+ 四个页面模块 + CSS，且 init 最后加载")
    void pageWiresSharedScriptsAndModulesInOrder() throws IOException {
        String html = read(HTML);
        for (String ref : new String[]{
                "/js/pixiv-i18n.js", "/js/pixiv-lang-switcher.js", "/js/pixiv-theme.js", "/js/pixiv-navigation.js",
                "/js/pixiv-feedback.js", "/css/pixiv-feedback.css",
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
        assertThat(html.indexOf("pixiv-feedback.js"))
                .as("共享反馈组件应在页面 init 之前加载")
                .isLessThan(html.indexOf("plugin-manage-init.js"));
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
    @DisplayName("消费 admin 后端 API：运行期动词、持久化启停与后端重启使用约定的方法和请求体")
    void consumesAdminBackendApi() throws IOException {
        String core = read(CORE);
        assertThat(core).as("状态查询端点").contains("'/api/plugins/status'");
        assertThat(core).as("运行期动词端点前缀").contains("'/api/plugins/'");
        assertThat(core).as("据响应 displayNamespace 动态收集 i18n namespace（不硬编码）").contains("collectNamespaces");
        assertThat(core).as("验签展示只消费后端 verification 投影").contains("entry.verification");
        assertThat(core).as("验签状态映射覆盖关键稳定状态")
                .contains("VERIFIED_OFFICIAL", "VERIFIED_CUSTOM", "UNVERIFIED_LOCAL",
                        "INVALID_SIGNATURE", "UNKNOWN_KEY");
        assertThat(core).as("插件管理页不得按 sha256/keyId/repositoryId 自行推断可信状态")
                .doesNotContain("sha256")
                .doesNotContain("keyId")
                .doesNotContain("repositoryId === 'official'");
        String api = read(API);
        assertThat(api).as("拉取状态走 STATUS_URL").contains("PM.STATUS_URL");
        assertThat(api).as("动词为 POST").contains("method: 'POST'");
        assertThat(api).as("持久化启停为 PUT").contains("method: 'PUT'");
        assertThat(api).as("持久化启停使用 JSON enabled 请求体")
                .contains("JSON.stringify({ enabled: enabled === true })");
        assertThat(api).as("后端重启使用专用端点")
                .contains("PM.BACKEND_RESTART_URL");
        assertThat(api).as("失败携后端稳定机器码 code").contains("err.code");
    }

    @Test
    @DisplayName("生命周期策略完全由 DTO 驱动：三类标签、持久化开关与反馈弹窗均接线且不使用原生对话框")
    void lifecyclePolicyDrivesLabelsTogglesAndRestartGuidance() throws IOException {
        String core = read(CORE);
        assertThat(core).contains("entry.lifecyclePolicy", "entry.configuredEnabled", "entry.toggleable");
        assertThat(core).contains("HOT_RELOAD", "BACKEND_RESTART", "PROCESS_RESTART");

        String views = read(VIEWS);
        assertThat(views).contains("pm-tag--lifecycle-", "vm.showLifecycleTag", "vm.lifecycleLabel", "vm.enabled");

        String init = read(INIT);
        assertThat(init).as("需重启策略持久化启停配置").contains("PM.setEnabled");
        assertThat(init).as("后端重启需先走共享确认框").contains("PixivFeedback.confirm", "PM.restartBackend");
        assertThat(init).as("完整进程重启只走共享提醒框").contains("PixivFeedback.alert");
        assertThat(init).as("禁止原生 confirm / alert")
                .doesNotContain("window.confirm(", "window.alert(", "global.confirm(", "global.alert(");
        assertThat(init).as("前端不得调用完整进程重启端点").doesNotContain("/api/gui/restart");
    }

    @Test
    @DisplayName("生命周期与重启提示文案中英键集合一致且关键文案非空")
    void lifecycleI18nKeysMatchAcrossLocales() throws IOException {
        Properties zh = readProperties(I18N_ZH);
        Properties en = readProperties(I18N_EN);
        assertThat(zh.stringPropertyNames()).as("plugins 中英文案键集合一致")
                .isEqualTo(en.stringPropertyNames());
        for (String key : new String[]{
                "lifecycle.hot-reload", "lifecycle.backend-restart", "lifecycle.process-restart",
                "toggle.saved.enabled", "toggle.saved.disabled", "toggle.failed",
                "restart.backend.message", "restart.backend.confirm", "restart.backend.later",
                "restart.process.message", "restart.process.done"}) {
            assertThat(zh.getProperty(key)).as("中文文案 " + key).isNotBlank();
            assertThat(en.getProperty(key)).as("英文文案 " + key).isNotBlank();
        }
    }

    @Test
    @DisplayName("前端不硬编码插件清单：列表由 /api/plugins/status 驱动，页面模块不含任何内置插件 id 字面量")
    void doesNotHardcodePluginList() throws IOException {
        String combined = read(CORE) + read(API) + read(VIEWS) + read(INIT);
        for (String pluginId : new String[]{
                "download-workbench", "gallery", "novel", "duplicate", "plugin-market", "recovery-sentinel"}) {
            assertThat(combined)
                    .as("页面模块不得硬编码内置插件 id：" + pluginId + "（列表须由后端响应驱动）")
                    .doesNotContain("\"" + pluginId + "\"")
                    .doesNotContain("'" + pluginId + "'");
        }
    }

    @Test
    @DisplayName("页内分段控件为生命周期感知：可选页签由通用导航完整渲染并随 contribution 撤销")
    void pluginSegmentIsLifecycleAware() throws IOException {
        String html = read(HTML);
        // 分段控件直接声明通用导航 slot；贡献方拥有 href、图标与标签，本页只拥有「已安装」当前页签。
        assertThat(html).as("分段控件挂载锚点").contains("id=\"pm-seg-host\"");
        assertThat(html).as("可选页签走通用导航 renderer")
                .contains("data-nav-slot=\"" + NavigationPlacements.PLUGINS_SEGMENT + "\"",
                        "data-nav-link-class=\"pm-seg-item\"");
        assertThat(html).as("HTML 不硬编码市场页 href（应由导航数据动态渲染）").doesNotContain("/plugin-market.html");
        // 容器显隐由导航生命周期事件驱动；页面模块不读取插件 id，也不复制可选页签的展示语义。
        String init = read(INIT);
        assertThat(init).as("init 监听导航渲染生命周期事件").contains("pixivnav:rendered");
        assertThat(init).as("据 plugins.segment placement 判定可选页签是否存在")
                .contains(NavigationPlacements.PLUGINS_SEGMENT, "hasNavigationForPlacement")
                .doesNotContain("plugin-market");
        assertThat(init).as("init 不硬编码可选页 href（href 由导航贡献提供）").doesNotContain("/plugin-market.html");
        String core = read(CORE);
        String views = read(VIEWS);
        assertThat(core + views).as("页面状态 / 视图不复制可选页签 href、图标或标签")
                .doesNotContain("marketNav", "seg.market", "renderMarketSegment", "/plugin-market.html");
        Properties zh = readProperties(I18N_ZH);
        Properties en = readProperties(I18N_EN);
        assertThat(zh).as("可选页签中文文案归导航贡献方").doesNotContainKey("seg.market");
        assertThat(en).as("可选页签英文文案归导航贡献方").doesNotContainKey("seg.market");
        assertThat(zh.getProperty("seg.installed")).isNotBlank();
        assertThat(en.getProperty("seg.installed")).isNotBlank();
    }

    @Test
    @DisplayName("页面专属样式独立成文件且支持深色模式（html[data-theme=\"dark\"] 覆盖）")
    void cssIsSeparateAndDarkModeAware() throws IOException {
        String css = read(CSS);
        assertThat(css).as("深色模式覆盖").contains("html[data-theme=\"dark\"]");
        assertThat(css).as("复用 CSS 变量主题方案").contains("--surface");
    }
}
