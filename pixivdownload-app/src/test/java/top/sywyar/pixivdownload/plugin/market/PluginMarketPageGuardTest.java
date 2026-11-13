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

/** 市场页的资源装配、文案可用性与安全静态检查。 */
@DisplayName("插件市场 Web 页面静态守卫")
class PluginMarketPageGuardTest {

    private static final String HTML = "static/plugin-market.html";
    private static final String CORE = "static/plugin-market/plugin-market-core.js";
    private static final String DATA = "static/plugin-market/plugin-market-data.js";
    private static final String API = "static/plugin-market/plugin-market-api.js";
    private static final String RECOVERY = "static/plugin-market/plugin-market-recovery.js";
    private static final String VUE = "static/plugin-market/plugin-market-vue.js";
    private static final String FALLBACK = "static/plugin-market/plugin-market-fallback.js";
    private static final String INIT = "static/plugin-market/plugin-market-init.js";

    private static final List<String> ALL_STATIC = List.of(HTML, CORE, DATA, API, RECOVERY, VUE, FALLBACK, INIT,
            "static/plugin-market/plugin-market.css");

    private static String read(String resource) throws IOException {
        return new String(readBytes(resource), StandardCharsets.UTF_8);
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
        int recovery = html.indexOf("/plugin-market/plugin-market-recovery.js");
        int vue = html.indexOf("/plugin-market/plugin-market-vue.js");
        int fallback = html.indexOf("/plugin-market/plugin-market-fallback.js");
        int init = html.indexOf("/plugin-market/plugin-market-init.js");
        assertThat(List.of(vueHelper, core, data, api, recovery, vue, fallback, init))
                .as("各模块脚本都应在页面声明").allSatisfy(idx -> assertThat(idx).isGreaterThanOrEqualTo(0));
        assertThat(vueHelper).isLessThan(core);
        assertThat(core).isLessThan(data);
        assertThat(data).isLessThan(api);
        assertThat(api).isLessThan(recovery);
        assertThat(recovery).isLessThan(vue);
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
        assertThat(html).contains("id=\"pmk-recovery\"");
        assertThat(html).doesNotContain("repository-import", "pmk-repository-url", "pmk-database", "plugin-market-database.js");
        assertThat(read(INIT)).doesNotContain("mountRepositoryImport", "PMK.database");
        assertThat(read(API)).doesNotContain("/repositories/import/", "/api/plugins/database");
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
                "filter.hide-default-installed", "filter.hide-dependencies",
                "category.all", "category.all.description",
                "category.translate", "category.translate.description",
                "category.download-type", "category.download-type.description",
                "category.download", "category.download.description",
                "category.convert", "category.convert.description",
                "category.notify", "category.notify.description",
                "category.backup", "category.backup.description",
                "category.security", "category.security.description",
                "category.ui", "category.ui.description",
                "category.utility", "category.utility.description",
                "category.dependency", "category.dependency.description",
                "sort.recommended", "sort.updated", "sort.downloads", "sort.rating", "sort.name",
                "install.action.install", "install.action.update", "install.state.installed",
                "install.state.incompatible", "install.state.unavailable",
                "install.state.installing", "install.state.pending-restart",
                "install.restart-hint", "install.goto-manage", "compat.needs", "fallback.notice",
                "detail.changelog", "detail.requires", "detail.verification",
                "master.disabled.title", "recovery.banner.title",
                "recovery.explanation", "recovery.error-summary", "recovery.status-unavailable",
                "error.catalog", "empty.title", "security.notice", "host.elevated.notice",
                "install.trust.declarative-risk", "install.trust.elevated-risk", "disclaimer");
        for (String key : critical) {
            assertThat(zh.getProperty(key)).as("zh 缺关键键 %s", key).isNotBlank();
            assertThat(en.getProperty(key)).as("en 缺关键键 %s", key).isNotBlank();
        }
    }

    @Test
    @DisplayName("Vue 与基础回退视图共用插件执行说明和高权限宿主警告")
    void rendersSecurityNoticeInVueAndFallback() throws IOException {
        String vue = read(VUE);
        String fallback = read(FALLBACK);

        assertThat(vue).contains("pmk-security-notice", "security.notice", "host.elevated.notice");
        assertThat(fallback).contains("pmk-security-notice", "security.notice", "host.elevated.notice");
    }

    @Test
    @DisplayName("安装信任入口复用共享反馈框，不包含旧身份迁移参数或原生确认框")
    void artifactTrustConfirmationUsesSharedGuard() throws IOException {
        String core = read(CORE);
        String api = read(API);
        String vue = read(VUE);
        String fallback = read(FALLBACK);

        assertThat(core).contains("TRUST_CONFIRMATION_REQUIRED",
                "trustRequirement", "artifactSha256", "PixivFeedback.confirm", "installPluginWithConfirmation");
        assertThat(api).contains("confirmTrust=").doesNotContain("confirmIdentityMigration");
        assertThat(core + vue + fallback).doesNotContain("window.confirm(", "global.confirm(");
    }

    @Test
    @DisplayName("验签状态只消费后端投影：市场卡片 / 详情 / 安装态不按摘要、仓库名或 keyId 推断可信")
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
                .contains("pmk-verification-badge", "pmk-detail-verification");
        assertThat(fallback).as("基础回退视图也展示验签徽标")
                .contains("verificationBadgeHtml", "pmk-verification-badge");
        assertThat(css).as("验签徽标包含已验证 / 未验证 / 危险状态样式")
                .contains(".pmk-verification-badge--ok", ".pmk-verification-badge--warn",
                        ".pmk-verification-badge--danger", ".pmk-detail-verification--danger");
        assertThat(core).as("安装按钮状态覆盖验签失败状态")
                .contains("SIGNATURE_REQUIRED", "UNKNOWN_KEY", "INVALID_SIGNATURE", "HASH_MISMATCH");
        assertThat(data).as("本机摘要可展示，但内容比较只消费后端机器码")
                .contains("local.sha256 || unknown", "pkg.installationMatch")
                .doesNotContainPattern("sha256\\s*[!=]==")
                .doesNotContainPattern("[!=]==\\s*[^;\\n]*sha256");
        assertThat(data + core).as("可信状态不得由 key 名称 / 仓库名硬推断")
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
