package top.sywyar.pixivdownload.plugin.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 插件市场 Web 页面静态守卫：直接读取 classpath 下的市场页静态资源与 i18n 文案，校验前端不变量——
 * <ul>
 *   <li>页面模块按依赖顺序加载（core → data → api → vue → fallback → init），且先加载共享 Vue 助手；</li>
 *   <li>只消费正确的 {@code /api/plugin-market/**} 端点，<b>不</b>引用已迁移为 404 的旧 {@code /api/plugins/catalog/**}；</li>
 *   <li>不引入设计稿运行时（{@code support.js} / {@code _ds} / {@code .dc.html} / design_handoff）；</li>
 *   <li>Vue 视图不使用 {@code v-html}、命令式回退一律经 {@code escapeHtml} 转义（杜绝不受控 HTML/SVG/CSS 注入）；</li>
 *   <li>安装只按受控 repositoryId+pluginId+version 构造 URL（encodeURIComponent），不接受任意 URL；</li>
 *   <li>i18n zh / en 文案键集合一致且覆盖关键键；</li>
 *   <li>页面唯一硬编码的跨页入口是核心壳自有的 {@code /plugin-manage.html}（互链），不硬编码其它插件页 href；</li>
 *   <li>旧占位命令式渲染模块 {@code plugin-market-views.js} 已删除、不在 classpath（不留孤儿死代码随 jar 打包）；</li>
 *   <li>静态文本资源不含真实 NUL（{@code 0x00}）字节（如复合键分隔符须以源码转义书写，不嵌裸字节）。</li>
 * </ul>
 */
@DisplayName("插件市场 Web 页面静态守卫")
class PluginMarketPageGuardTest {

    private static final String HTML = "static/plugin-market.html";
    private static final String CORE = "static/plugin-market/plugin-market-core.js";
    private static final String DATA = "static/plugin-market/plugin-market-data.js";
    private static final String API = "static/plugin-market/plugin-market-api.js";
    private static final String VUE = "static/plugin-market/plugin-market-vue.js";
    private static final String FALLBACK = "static/plugin-market/plugin-market-fallback.js";
    private static final String INIT = "static/plugin-market/plugin-market-init.js";

    private static final List<String> ALL_STATIC = List.of(HTML, CORE, DATA, API, VUE, FALLBACK, INIT,
            "static/plugin-market/plugin-market.css");

    private static String read(String resource) throws IOException {
        try (InputStream in = PluginMarketPageGuardTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new NoSuchFileException(resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readBytes(String resource) throws IOException {
        try (InputStream in = PluginMarketPageGuardTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new NoSuchFileException(resource);
            }
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("页面按依赖顺序加载模块脚本（共享 Vue 助手在前，core → data → api → vue → fallback → init）")
    void modulesLoadInDependencyOrder() throws IOException {
        String html = read(HTML);
        int vueHelper = html.indexOf("/js/pixiv-vue.js");
        int core = html.indexOf("/plugin-market/plugin-market-core.js");
        int data = html.indexOf("/plugin-market/plugin-market-data.js");
        int api = html.indexOf("/plugin-market/plugin-market-api.js");
        int vue = html.indexOf("/plugin-market/plugin-market-vue.js");
        int fallback = html.indexOf("/plugin-market/plugin-market-fallback.js");
        int init = html.indexOf("/plugin-market/plugin-market-init.js");
        assertThat(vueHelper).as("应加载共享 Vue 助手 /js/pixiv-vue.js").isGreaterThanOrEqualTo(0);
        assertThat(List.of(vueHelper, core, data, api, vue, fallback, init))
                .as("各模块脚本都应在页面声明").allSatisfy(idx -> assertThat(idx).isGreaterThanOrEqualTo(0));
        assertThat(vueHelper).isLessThan(core);
        assertThat(core).isLessThan(data);
        assertThat(data).isLessThan(api);
        assertThat(api).isLessThan(vue);
        assertThat(vue).isLessThan(fallback);
        assertThat(fallback).isLessThan(init);
    }

    @Test
    @DisplayName("页面声明 app.top 导航 slot + 语言切换锚点 + 页面专属 CSS")
    void htmlDeclaresChrome() throws IOException {
        String html = read(HTML);
        assertThat(html).contains("data-nav-slot=\"app.top\"");
        assertThat(html).contains("id=\"langSwitcherAnchor\"");
        assertThat(html).contains("/plugin-market/plugin-market.css");
        assertThat(html).contains("id=\"pmk-app-root\"");
    }

    @Test
    @DisplayName("API 模块只消费正确的 /api/plugin-market 端点，安装经 encodeURIComponent 构造路径（不接受任意 URL）")
    void apiUsesCorrectEndpoints() throws IOException {
        String api = read(API);
        assertThat(api).contains("/api/plugin-market/repositories");
        assertThat(api).contains("/api/plugin-market/catalog");
        assertThat(api).contains("/api/plugin-market/");
        assertThat(api).contains("/install");
        assertThat(api).contains("encodeURIComponent");
        // 安装是 POST、按路径变量解析；请求体不拼任意 url 参数。
        assertThat(api).contains("method: 'POST'");
        assertThat(api).doesNotContain("packageUrl");
    }

    @Test
    @DisplayName("不引用已迁移为 404 的旧 /api/plugins/catalog 端点")
    void noLegacyCatalogEndpoint() throws IOException {
        for (String resource : ALL_STATIC) {
            assertThat(read(resource))
                    .as("%s 不应引用旧 /api/plugins/catalog 端点（已破坏性迁移为 /api/plugin-market）", resource)
                    .doesNotContain("/api/plugins/catalog");
        }
    }

    @Test
    @DisplayName("不引入设计稿运行时（support.js / _ds / .dc.html / design_handoff）")
    void noDesignRuntime() throws IOException {
        for (String resource : ALL_STATIC) {
            String content = read(resource);
            assertThat(content).as("%s 不应引用设计稿运行时 support.js", resource).doesNotContain("support.js");
            assertThat(content).as("%s 不应引用设计系统目录 _ds", resource).doesNotContain("_ds");
            assertThat(content).as("%s 不应引用设计稿原型 .dc.html", resource).doesNotContain(".dc.html");
            assertThat(content).as("%s 不应引用设计交付目录 design_handoff", resource).doesNotContain("design_handoff");
        }
    }

    @Test
    @DisplayName("旧占位命令式渲染模块 plugin-market-views.js 已删除、不在 classpath（不留孤儿死代码随 jar 打包）")
    void orphanViewsModuleAbsent() {
        assertThat(PluginMarketPageGuardTest.class.getClassLoader()
                .getResource("static/plugin-market/plugin-market-views.js"))
                .as("plugin-market-views.js 应已删除、不随 classpath / boot jar 打包").isNull();
    }

    @Test
    @DisplayName("市场页静态文本资源不含真实 NUL 字节（复合键分隔符等须用源码转义 \\u0000，不嵌裸 0x00）")
    void staticResourcesContainNoRawNulByte() throws IOException {
        for (String resource : ALL_STATIC) {
            byte[] bytes = readBytes(resource);
            int nul = -1;
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] == 0) {
                    nul = i;
                    break;
                }
            }
            assertThat(nul)
                    .as("%s 含真实 NUL 字节（0x00）@ 第 %d 字节；需要 NUL 语义请改用源码转义 \\u0000", resource, nul)
                    .isEqualTo(-1);
        }
    }

    @Test
    @DisplayName("Vue 视图不用 v-html 指令、命令式回退一律 escapeHtml 转义（杜绝不受控 HTML 注入）")
    void noUnsafeHtmlInjection() throws IOException {
        // 校验 HTML 注入指令的实际用法（属性形态），而非文档注释里偶然出现的字面词。
        assertThat(read(VUE)).as("Vue 模板不应使用 HTML 注入指令").doesNotContain("v-html=");
        String fallback = read(FALLBACK);
        assertThat(fallback).as("命令式回退应经 escapeHtml 转义后才进 innerHTML").contains("PMK.escapeHtml");
        // 回退渲染器对动态文本统一经 esc(...) 包裹（不裸拼用户 / 清单文本进 HTML）。
        assertThat(fallback).contains("esc(");
    }

    @Test
    @DisplayName("i18n zh / en 文案键集合一致并覆盖关键键")
    void i18nKeyParityAndCoverage() throws IOException {
        Properties zh = loadProps("i18n/web/plugin-market.properties");
        Properties en = loadProps("i18n/web/plugin-market_en.properties");
        assertThat(en.keySet()).as("en 文案键集合须与 zh 一致").isEqualTo(zh.keySet());

        List<String> critical = List.of(
                "nav.label", "plugin.summary", "page.heading", "seg.market", "seg.installed",
                "section.repositories", "sidebar.browse", "filter.official", "filter.compatible",
                "category.all", "category.translate", "category.utility",
                "sort.recommended", "sort.updated", "sort.downloads", "sort.rating", "sort.name",
                "install.action.install", "install.action.update", "install.state.installed",
                "install.state.incompatible", "install.state.unavailable",
                "install.state.installing", "install.state.pending-restart",
                "install.restart-hint", "install.goto-manage", "compat.needs", "fallback.notice",
                "detail.changelog", "detail.requires", "detail.sha256", "detail.verification",
                "master.disabled.title", "error.catalog", "empty.title", "security.notice", "disclaimer");
        for (String key : critical) {
            assertThat(zh.getProperty(key)).as("zh 缺关键键 %s", key).isNotBlank();
            assertThat(en.getProperty(key)).as("en 缺关键键 %s", key).isNotBlank();
        }
    }

    @Test
    @DisplayName("插件市场展示未验证 / 未签名插件安全提示，Vue 与基础回退视图共用 i18n 文案")
    void rendersSecurityNoticeInVueAndFallback() throws IOException {
        String vue = read(VUE);
        String fallback = read(FALLBACK);
        Properties zh = loadProps("i18n/web/plugin-market.properties");
        Properties en = loadProps("i18n/web/plugin-market_en.properties");

        assertThat(vue).contains("pmk-security-notice", "security.notice");
        assertThat(fallback).contains("pmk-security-notice", "security.notice");
        assertThat(zh.getProperty("security.notice"))
                .contains("无法验证", "未签名", "无法保证未验证插件的安全");
        assertThat(en.getProperty("security.notice"))
                .contains("unverifiable", "unsigned", "cannot guarantee the safety of unverified plugins");
    }

    @Test
    @DisplayName("展示元数据只保存 i18n key 和受控展示 token，不内联中文 fallback")
    void displayMetadataUsesI18nKeysOnly() throws IOException {
        String data = read(DATA);
        String core = read(CORE);

        assertThat(data).as("验签徽标元数据应只携带 i18n key / tone / icon")
                .contains("VERIFICATION_BADGE_META", "labelKey")
                .doesNotContain("fallback:");
        assertThat(core).as("安装状态元数据应只携带 i18n key / icon / variant")
                .contains("PMK.INSTALL_META", "labelKey")
                .doesNotContain("fallback:");
    }

    @Test
    @DisplayName("仓库切换异步竞态护栏：Vue / 回退按 token 丢弃旧仓库 catalog 响应，安装绑定展示条目同源仓库（不读全局 activeRepositoryId）")
    void repositorySwitchRaceGuards() throws IOException {
        String vue = read(VUE);
        String fallback = read(FALLBACK);
        // catalog 拉取按自增 token 守卫：回调只在 token 仍最新时落地（仓库快速切换时旧仓库响应被丢弃、不覆盖当前状态）。
        assertThat(vue).as("Vue catalog 拉取应有 token 竞态护栏").contains("catalogToken");
        assertThat(fallback).as("回退 catalog 拉取应有 token 竞态护栏").contains("catalogToken");
        // 安装态按 (repositoryId, pluginId) 复合键键控，使同名插件在不同仓库间互不污染。
        assertThat(vue).as("Vue 安装态按复合键键控").contains("installKey");
        assertThat(fallback).as("回退安装态按复合键键控").contains("installKey");
        // 安装绑定展示条目同源仓库：Vue 用卡片 / 当前 catalog 的 repositoryId，回退从卡片 data-pmk-repo 取仓库。
        assertThat(vue).as("Vue 安装绑定卡片 repositoryId").contains("doInstall(card.repositoryId");
        assertThat(vue).as("Vue 模态安装绑定当前 catalog 仓库").contains("activeCatalogRepositoryId");
        assertThat(fallback).as("回退安装从卡片 data-pmk-repo 取仓库").contains("data-pmk-repo");
        // 不再读易变的全局 activeRepositoryId 发起安装（展示与安装的 repositoryId 同源）。
        assertThat(vue).as("Vue 安装不读全局 activeRepositoryId")
                .doesNotContain("installPlugin(this.activeRepositoryId");
        assertThat(fallback).as("回退安装不读全局 activeRepositoryId")
                .doesNotContain("installPlugin(state.activeRepositoryId");
    }

    @Test
    @DisplayName("仅兼容筛选按条目 compatible 判定；无可安装版本条目稳定降级为不可点击的 i18n 不可安装状态")
    void compatibilityFilterAndUnavailableDegradation() throws IOException {
        // 「仅兼容当前版本」按条目自身 compatible 标记（最新可安装版本兼容性）判定，而非派生的 installStatus。
        String data = read(DATA);
        assertThat(data).as("onlyCompatible 按 entry.compatible 判定").contains("entry.compatible === false");
        assertThat(data).as("onlyCompatible 不再仅按 installStatus 判定")
                .doesNotContain("entry.installStatus === 'INCOMPATIBLE'");
        // 无可安装版本（后端 UNAVAILABLE）→ 禁用态控件 + i18n 不可安装文案，不渲染可点击但无响应的安装按钮。
        String core = read(CORE);
        assertThat(core).as("UNAVAILABLE 安装控件元数据存在").contains("UNAVAILABLE");
        assertThat(core).as("UNAVAILABLE 走 i18n 不可安装文案键").contains("install.state.unavailable");
    }

    @Test
    @DisplayName("验签状态只消费后端投影：市场卡片 / 详情 / 安装态不按 sha256、HTTPS、仓库名或 keyId 推断可信")
    void verificationRenderingUsesBackendProjectionOnly() throws IOException {
        String data = read(DATA);
        String vue = read(VUE);
        String fallback = read(FALLBACK);
        String core = read(CORE);
        String css = read("static/plugin-market/plugin-market.css");

        assertThat(data).as("卡片模型从 package.verification 取验签投影")
                .contains("pkg.verification", "verification.status", "verificationBadge: D.verificationBadge");
        assertThat(data).as("安装态按后端验签状态禁用坏签名 / 未知 key")
                .contains("VERIFIED_OFFICIAL", "VERIFIED_CUSTOM", "INVALID_SIGNATURE", "UNKNOWN_KEY");
        assertThat(vue).as("卡片与详情页突出展示后端 verification label，而非 sha256 认证发布者")
                .contains("verificationLabel(pkg.verification)", "detail.verification",
                        "pmk-verification-badge", "pmk-detail-verification", "showCardVerification");
        assertThat(fallback).as("基础回退视图也展示验签徽标")
                .contains("verificationBadgeHtml", "pmk-verification-badge");
        assertThat(css).as("验签徽标包含已验证 / 未验证 / 危险状态样式")
                .contains(".pmk-verification-badge--ok", ".pmk-verification-badge--warn",
                        ".pmk-verification-badge--danger", ".pmk-detail-verification--danger");
        assertThat(core).as("安装按钮状态覆盖验签失败状态")
                .contains("SIGNATURE_REQUIRED", "UNKNOWN_KEY", "INVALID_SIGNATURE", "HASH_MISMATCH");
        assertThat(data + core).as("可信状态不得由摘要 / key 名称 / 仓库名硬推断")
                .doesNotContain("sha256")
                .doesNotContain("keyId")
                .doesNotContain("repositoryId === 'official'");
    }

    @Test
    @DisplayName("唯一硬编码跨页入口是核心壳自有的 /plugin-manage.html，不硬编码其它插件页 href")
    void crossLinkOnlyToCorePluginManage() throws IOException {
        boolean linksManage = false;
        for (String resource : List.of(HTML, VUE, FALLBACK)) {
            String content = read(resource);
            if (content.contains("/plugin-manage.html")) {
                linksManage = true;
            }
            // 不硬编码其它插件页入口（这些应由动态导航 slot 渲染，禁用对应插件后才不会残留坏入口）。
            for (String forbidden : List.of("/pixiv-gallery.html", "/pixiv-novel-gallery.html",
                    "/monitor.html", "/pixiv-stats.html", "/pixiv-duplicates.html", "/pixiv-invite-manage.html")) {
                assertThat(content).as("%s 不应硬编码其它插件页入口 %s", resource, forbidden)
                        .doesNotContain("href=\"" + forbidden + "\"");
            }
        }
        assertThat(linksManage).as("市场页应经分段控件互链到核心插件管理页 /plugin-manage.html").isTrue();
    }

    private static Properties loadProps(String resource) throws IOException {
        Properties props = new Properties();
        try (InputStream in = PluginMarketPageGuardTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new NoSuchFileException(resource);
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        }
        return props;
    }
}
