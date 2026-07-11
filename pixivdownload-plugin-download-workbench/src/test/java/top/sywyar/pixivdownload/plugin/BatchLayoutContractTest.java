package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下载页经典 / 工作台双布局的静态资源契约守卫。运行态偏好、事件绑定与异常降级另由
 * {@code pixivdownload-app/src/test/js/batch-layout.test.js} 通过真实脚本执行验证。
 */
@DisplayName("下载页经典 / 工作台双布局静态契约守卫")
class BatchLayoutContractTest {

    private static final String STATIC_ROOT = "static/";
    private static final String BATCH_HTML = STATIC_ROOT + "pixiv-batch.html";
    private static final String BASE_CSS = STATIC_ROOT + "pixiv-batch/pixiv-batch.css";
    private static final String WORKBENCH_LAYOUT_CSS =
            STATIC_ROOT + "pixiv-batch/pixiv-batch-layout-workbench.css";
    private static final String CLASSIC_LAYOUT_CSS =
            STATIC_ROOT + "pixiv-batch/pixiv-batch-layout-classic.css";
    private static final String LEGACY_LAYOUT_CSS = STATIC_ROOT + "pixiv-batch/pixiv-batch-layout.css";
    private static final String LAYOUT_JS = STATIC_ROOT + "pixiv-batch/batch-layout.js";
    private static final String INIT_JS = STATIC_ROOT + "pixiv-batch/batch-init.js";
    private static final String BATCH_I18N_ZH = "i18n/web/batch.properties";
    private static final String BATCH_I18N_EN = "i18n/web/batch_en.properties";
    private static final String WORKBENCH_SCOPE = "html[data-batch-layout=\"workbench\"]";
    private static final String CLASSIC_SCOPE = "html[data-batch-layout=\"classic\"]";

    private static final Pattern SCRIPT_SRC = Pattern.compile(
            "<script\\s+[^>]*src=\"([^\"]+)\"[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_TAG = Pattern.compile("<link\\s+[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LAYOUT_TOKEN = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

    private record LayoutStyleLink(String href, String token, int offset) {
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = BatchLayoutContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new NoSuchFileException(resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int countOccurrences(String source, String token) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(token, from)) >= 0) {
            count++;
            from += token.length();
        }
        return count;
    }

    private static String sliceBetween(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        assertThat(start).as("源码缺少起始标记: " + startMarker).isGreaterThanOrEqualTo(0);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertThat(end).as("源码缺少结束标记: " + endMarker).isGreaterThan(start);
        return source.substring(start, end);
    }

    private static String firstTag(String source, String tagName) {
        Matcher matcher = Pattern.compile("<" + Pattern.quote(tagName) + "\\b[^>]*>",
                Pattern.CASE_INSENSITIVE).matcher(source);
        assertThat(matcher.find()).as("源码缺少 <" + tagName + "> 标签").isTrue();
        return matcher.group();
    }

    private static String attribute(String tag, String name) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "=\"([^\"]*)\"",
                Pattern.CASE_INSENSITIVE).matcher(tag);
        assertThat(matcher.find()).as("标签缺少属性 " + name + ": " + tag).isTrue();
        return matcher.group(1);
    }

    private static List<LayoutStyleLink> layoutStyleLinks(String html) {
        List<LayoutStyleLink> links = new ArrayList<>();
        Matcher matcher = LINK_TAG.matcher(html);
        while (matcher.find()) {
            String tag = matcher.group();
            if (!tag.contains("data-batch-layout-style=")) {
                continue;
            }
            links.add(new LayoutStyleLink(
                    attribute(tag, "href"),
                    attribute(tag, "data-batch-layout-style"),
                    matcher.start()));
        }
        return List.copyOf(links);
    }

    private static List<String> scriptSources(String html) {
        List<String> sources = new ArrayList<>();
        Matcher matcher = SCRIPT_SRC.matcher(html);
        while (matcher.find()) {
            sources.add(matcher.group(1));
        }
        return sources;
    }

    private static Set<String> layoutKeys(String resource) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(read(resource)));
        Set<String> keys = new LinkedHashSet<>();
        properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith("layout."))
                .sorted()
                .forEach(keys::add);
        return keys;
    }

    /**
     * 提取普通 CSS 规则的独立选择器；{@code @media} 等 at-rule 本身跳过，但保留其中的普通规则。
     */
    private static List<String> cssSelectors(String css) {
        String source = CSS_COMMENT.matcher(css).replaceAll("");
        List<String> selectors = new ArrayList<>();
        int segmentStart = 0;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '{') {
                String header = source.substring(segmentStart, i).trim();
                if (!header.isEmpty() && !header.startsWith("@")) {
                    for (String selector : header.split(",")) {
                        selectors.add(selector.trim());
                    }
                }
                segmentStart = i + 1;
            } else if (ch == '}') {
                segmentStart = i + 1;
            }
        }
        return selectors;
    }

    private static void assertNoPattern(String source, String description, String regex) {
        assertThat(Pattern.compile(regex,
                        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL).matcher(source).find())
                .as(description)
                .isFalse();
    }

    private static boolean selectsAnchor(String selector, String anchor) {
        return Pattern.compile("(^|[\\s>+~])" + Pattern.quote(anchor)
                        + "(?=$|[\\s>+~:.#\\[])")
                .matcher(selector)
                .find();
    }

    private static void assertScopedProjection(String css, String scope, String forbiddenToken) {
        List<String> selectors = cssSelectors(css);
        assertThat(selectors).as("布局投影应含普通选择器规则").isNotEmpty();
        assertThat(selectors)
                .as("布局投影中的每个普通选择器都必须以根布局作用域开头")
                .allMatch(selector -> selector.startsWith(scope));

        for (String anchor : List.of(
                ".wb-shell", ".mode-rail", ".tabs", ".tab",
                ".dash-strip", ".dash-stats", ".queue-rail")) {
            assertThat(selectors)
                    .as("布局投影必须独立声明空间锚点 " + anchor)
                    .anyMatch(selector -> selectsAnchor(selector, anchor));
        }

        assertThat(css)
                .as("布局投影不得提及另一布局 token")
                .doesNotContain(forbiddenToken)
                .as("布局投影不得依赖其它样式表")
                .doesNotContain("@import")
                .as("布局投影不得用 !important 覆盖业务隐藏状态")
                .doesNotContain("!important")
                .as("布局投影不得强制显示计划任务模式隐藏的工作区")
                .doesNotContain("#download-workbench");
    }

    private static Path pluginSourceRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path direct = workingDirectory.resolve("src/main/resources");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        return workingDirectory.resolve("pixivdownload-plugin-download-workbench/src/main/resources");
    }

    @Test
    @DisplayName("HTML 以声明式 link 唯一列出两张平级布局投影")
    void pageDeclaresAvailableLayoutsThroughStylesheetLinks() throws IOException {
        String html = read(BATCH_HTML);
        String root = firstTag(html, "html");
        List<LayoutStyleLink> links = layoutStyleLinks(html);
        int baseCssAt = html.indexOf("href=\"/pixiv-batch/pixiv-batch.css\"");

        assertThat(baseCssAt).as("下载页必须加载共享基础 CSS").isGreaterThanOrEqualTo(0);
        assertThat(links)
                .extracting(LayoutStyleLink::href)
                .containsExactly(
                        "/pixiv-batch/pixiv-batch-layout-workbench.css",
                        "/pixiv-batch/pixiv-batch-layout-classic.css");
        assertThat(links).allMatch(link -> link.offset() > baseCssAt);
        assertThat(links)
                .extracting(LayoutStyleLink::token)
                .containsExactly("workbench", "classic")
                .doesNotHaveDuplicates()
                .allMatch(token -> !token.isBlank() && LAYOUT_TOKEN.matcher(token).matches());

        Set<String> available = new LinkedHashSet<>();
        links.forEach(link -> available.add(link.token()));
        assertThat(attribute(root, "data-batch-layout")).isEqualTo("workbench").isIn(available);
        assertThat(attribute(root, "data-batch-layout-default")).isEqualTo("workbench").isIn(available);
        assertThat(read(BASE_CSS)).as("共享基础 CSS 应能从插件 classpath 读取").isNotBlank();
        assertThat(read(WORKBENCH_LAYOUT_CSS)).as("workbench 投影应能从插件 classpath 读取").isNotBlank();
        assertThat(read(CLASSIC_LAYOUT_CSS)).as("classic 投影应能从插件 classpath 读取").isNotBlank();
    }

    @Test
    @DisplayName("旧覆盖层从 HTML、源码与干净 classpath 中彻底移除")
    void legacyLayoutStylesheetIsRemoved() throws IOException {
        String html = read(BATCH_HTML);
        ClassLoader loader = BatchLayoutContractTest.class.getClassLoader();
        Path legacySource = pluginSourceRoot().resolve("static/pixiv-batch/pixiv-batch-layout.css");

        assertThat(html).doesNotContain("/pixiv-batch/pixiv-batch-layout.css");
        assertThat(legacySource).as("旧布局源码不应继续存在").doesNotExist();
        assertThat(loader.getResource(LEGACY_LAYOUT_CSS))
                .as("clean 构建后的插件 classpath 不应残留旧布局资源")
                .isNull();
    }

    @Test
    @DisplayName("共享 CSS 不再隐含 workbench 空间编排")
    void baseCssContainsOnlySharedStructureAndComponents() throws IOException {
        String css = read(BASE_CSS);

        assertThat(css).doesNotContain("data-batch-layout");
        assertNoPattern(css, "共享层不得保留 1440px workbench shell",
                "\\.wb-shell\\s*\\{[^}]*max-width\\s*:\\s*1440px");
        assertNoPattern(css, "共享层不得保留 workbench 三栏",
                "grid-template-columns\\s*:\\s*190px\\s+minmax\\(0,\\s*1fr\\)\\s+350px");
        assertNoPattern(css, "共享层不得保留 sticky mode rail",
                "\\.mode-rail\\s*\\{[^}]*position\\s*:\\s*sticky");
        assertNoPattern(css, "共享层不得保留纵向 tabs",
                "\\.tabs\\s*\\{[^}]*flex-direction\\s*:\\s*column");
        assertNoPattern(css, "共享层不得保留左对齐 tab",
                "\\.tab\\s*\\{[^}]*text-align\\s*:\\s*left");
        assertNoPattern(css, "共享层不得保留满幅 dashboard band",
                "\\.dash-strip\\s*\\{[^}]*(?:100vmax|clip-path)");
        assertNoPattern(css, "共享层不得保留 workbench 统计卡 flex 投影",
                "\\.dash-stats\\s*\\{[^}]*display\\s*:\\s*flex");
        assertNoPattern(css, "共享层不得保留 1200px queue 双列投影",
                "\\.queue-rail\\s*\\{[^}]*grid-template-columns\\s*:\\s*minmax\\(0,\\s*\\.72fr\\)");

        assertThat(css)
                .contains("#download-workbench")
                .contains("display: contents")
                .contains(".batch-layout-toggle");
    }

    @Test
    @DisplayName("workbench 投影独立承载三栏、rail、dashboard、统计与队列断点")
    void workbenchProjectionIsScopedAndIndependent() throws IOException {
        String css = read(WORKBENCH_LAYOUT_CSS);
        assertScopedProjection(css, WORKBENCH_SCOPE, "classic");

        assertThat(css)
                .contains("max-width: 1440px")
                .contains("grid-template-columns: 190px minmax(0, 1fr) 350px")
                .contains("position: sticky")
                .contains("box-shadow: 0 0 0 100vmax")
                .contains("@media (max-width: 1280px)")
                .contains("@media (max-width: 1200px)")
                .contains("@media (max-width: 820px)")
                .contains("@media (max-width: 560px)")
                .contains("@media (max-width: 380px)");
    }

    @Test
    @DisplayName("classic 投影独立承载单列、横向 tabs、卡片 dashboard 与统计网格")
    void classicProjectionIsScopedAndIndependent() throws IOException {
        String css = read(CLASSIC_LAYOUT_CSS);
        assertScopedProjection(css, CLASSIC_SCOPE, "workbench");

        assertThat(css)
                .contains("max-width: 1000px")
                .contains("grid-template-columns: minmax(0, 1fr)")
                .contains("position: static")
                .contains("grid-template-columns: repeat(3, minmax(0, 1fr))")
                .contains("@media (max-width: 560px)")
                .contains("@media (max-width: 380px)")
                .doesNotContain("100vmax");
    }

    @Test
    @DisplayName("布局按钮初始中性隐藏，等待 i18n 就绪后由控制器填充")
    void layoutToggleStartsHiddenAndWithoutBakedTarget() throws IOException {
        String html = read(BATCH_HTML);
        String button = sliceBetween(html, "<button type=\"button\" id=\"batch-layout-toggle\"", "</button>");

        assertThat(button)
                .contains("class=\"batch-layout-toggle\"")
                .contains("hidden")
                .contains("disabled")
                .contains("class=\"batch-layout-toggle-label\"></span>")
                .doesNotContain("onclick=")
                .doesNotContain("data-layout=")
                .doesNotContain("data-layout-target=")
                .doesNotContain("data-i18n")
                .doesNotContain("title=")
                .doesNotContain("aria-label=")
                .doesNotContain("switch-to-classic")
                .doesNotContain("switch-to-workbench");
    }

    @Test
    @DisplayName("batch-layout.js 位于 core 之后、init 之前，batch-init.js 保持最后页面模块")
    void pageLoadsLayoutControllerInDependencyOrder() throws IOException {
        List<String> scripts = scriptSources(read(BATCH_HTML));
        String core = "/pixiv-batch/batch-core.js";
        String layout = "/pixiv-batch/batch-layout.js";
        String init = "/pixiv-batch/batch-init.js";

        assertThat(scripts).contains(core, layout, init);
        assertThat(scripts.indexOf(layout)).as("batch-layout.js 必须在 batch-core.js 之后")
                .isGreaterThan(scripts.indexOf(core));
        assertThat(scripts.indexOf(init)).as("batch-layout.js 必须在 batch-init.js 之前")
                .isGreaterThan(scripts.indexOf(layout));
        assertThat(scripts).as("batch-init.js 必须是最后加载的页面模块").last().isEqualTo(init);
    }

    @Test
    @DisplayName("初始化先同步应用布局；DOMContentLoaded 内先 i18n、再绑定按钮、最后进入业务 init")
    void initializationOrderAvoidsLayoutFlashAndEarlyBinding() throws IOException {
        String initJs = read(INIT_JS);
        int applyStoredAt = initJs.indexOf("window.PixivBatch.layout.applyStoredLayout()");
        int domReadyAt = initJs.indexOf("document.addEventListener('DOMContentLoaded'");
        assertThat(applyStoredAt).as("batch-init 求值时应同步调用 applyStoredLayout").isGreaterThanOrEqualTo(0);
        assertThat(domReadyAt).as("batch-init 应注册 DOMContentLoaded 初始化").isGreaterThan(applyStoredAt);

        String initBody = sliceBetween(initJs, "async function init() {",
                "document.addEventListener('DOMContentLoaded'");
        assertThat(initBody)
                .as("业务 init 内仍应 await 下载类型 bootstrap，布局控制器不得替代既有初始化链")
                .contains("await window.PixivBatch.queueTypes.bootstrap()");

        String domReady = sliceBetween(initJs, "document.addEventListener('DOMContentLoaded'",
                "async function setupOnboardingOrTour(");
        int i18nAt = domReady.indexOf("await initPageI18n()");
        int bindAt = domReady.indexOf("window.PixivBatch.layout.bindLayoutToggle()");
        int initAt = domReady.indexOf("await init()");
        assertThat(i18nAt).as("DOMContentLoaded 应先完成页面 i18n").isGreaterThanOrEqualTo(0);
        assertThat(bindAt).as("布局按钮绑定必须在 i18n 完成后").isGreaterThan(i18nAt);
        assertThat(initAt).as("业务 init 必须在布局按钮绑定后执行").isGreaterThan(bindAt);
    }

    @Test
    @DisplayName("布局控制器从 link 发现布局且无业务或 DOM 重建副作用")
    void layoutControllerUsesDeclarativeDiscoveryWithoutBusinessSideEffects() throws IOException {
        String js = read(LAYOUT_JS);

        assertThat(js)
                .contains("link[data-batch-layout-style]")
                .doesNotContain("'workbench'")
                .doesNotContain("'classic'");
        assertNoPattern(js, "布局控制器不得发起 fetch", "\\bfetch\\s*\\(");
        assertNoPattern(js, "布局控制器不得创建 XMLHttpRequest", "\\bXMLHttpRequest\\b");
        assertNoPattern(js, "布局控制器不得 reload / 导航", "\\b(?:window\\s*\\.\\s*)?location\\s*\\.");
        assertNoPattern(js, "布局控制器不得克隆 / 替换 / 移动 DOM",
                "\\b(?:cloneNode|replaceWith|replaceChild|replaceChildren|appendChild|insertBefore)\\s*\\(");
        assertNoPattern(js, "布局控制器不得用 append/prepend/before/after 移动 DOM",
                "\\.\\s*(?:append|prepend|before|after)\\s*\\(");
        assertNoPattern(js, "布局控制器不得用 innerHTML/outerHTML 重建 DOM", "\\b(?:innerHTML|outerHTML)\\b");
        assertNoPattern(js, "布局控制器不得创建或操作 Vue app", "\\b(?:PixivVue|Vue|createApp)\\b");
        assertNoPattern(js, "布局控制器不得触发业务 bootstrap", "\\.\\s*bootstrap\\s*\\(");
        assertThat(js).as("布局 API 应加性挂到 window.PixivBatch.layout")
                .contains("window.PixivBatch.layout = Object.assign(window.PixivBatch.layout || {}, {");
    }

    @Test
    @DisplayName("九个插件 UI 槽位各保留唯一锚点")
    void allNineUiSlotsRemainUnique() throws IOException {
        String html = read(BATCH_HTML);
        List<String> targets = List.of(
                "cookie-tools",
                "quick-actions-bookmarks",
                "quick-actions-mine",
                "kind-option-quick",
                "import-hint",
                "kind-option-user",
                "kind-option-search",
                "search-filter",
                "settings-card");

        assertThat(countOccurrences(html, "data-qt-slot=\"")).as("下载页应恰有九个 UI 槽位").isEqualTo(9);
        for (String target : targets) {
            assertThat(countOccurrences(html, "data-qt-slot=\"" + target + "\""))
                    .as("槽位 " + target + " 必须且只能出现一次")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("模式、设置、统计、当前下载与队列挂载点保持单一 DOM 身份")
    void criticalBusinessAnchorsRemainUnique() throws IOException {
        String html = read(BATCH_HTML);
        List<String> ids = new ArrayList<>(List.of(
                "download-workbench",
                "download-settings-card",
                "s-concurrent",
                "stats-bar",
                "stat-count-pending",
                "stat-count-success",
                "stat-count-failed",
                "stat-count-active",
                "stat-count-skipped",
                "stat-speed-value",
                "stat-speed-unit",
                "current-card",
                "queue-list"));
        for (String mode : List.of("quick-fetch", "single-import", "user", "search", "series", "schedule")) {
            ids.add("tab-" + mode);
            ids.add("panel-" + mode);
        }

        for (String id : ids) {
            assertThat(countOccurrences(html, "id=\"" + id + "\""))
                    .as("关键业务 id '" + id + "' 必须且只能出现一次")
                    .isEqualTo(1);
        }
        assertThat(Pattern.compile("class=\"[^\"]*\\bdash-stats\\b[^\"]*\"").matcher(html).results().count())
                .as("Vue 统计挂载点 .dash-stats 必须且只能出现一次")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("中英文布局 i18n 键集合一致并包含双向切换文案")
    void layoutI18nKeysMatchAcrossLocales() throws IOException {
        Set<String> zh = layoutKeys(BATCH_I18N_ZH);
        Set<String> en = layoutKeys(BATCH_I18N_EN);

        assertThat(zh).containsExactly("layout.switch-to-classic", "layout.switch-to-workbench");
        assertThat(en).as("英文布局 i18n 键必须与中文完全一致").isEqualTo(zh);
    }

    @Test
    @DisplayName("下载工作台仍只贡献一个 batch HTML 页面，常见布局副本资源不存在")
    void noSecondBatchHtmlResourceIsPublished() throws IOException {
        ClassLoader loader = BatchLayoutContractTest.class.getClassLoader();
        List<URL> primaryPages = Collections.list(loader.getResources(BATCH_HTML));
        assertThat(primaryPages).as("测试 classpath 应只有一个真实 pixiv-batch.html").hasSize(1);

        for (String forbidden : List.of(
                STATIC_ROOT + "pixiv-batch-classic.html",
                STATIC_ROOT + "pixiv-batch-workbench.html",
                STATIC_ROOT + "pixiv-batch/classic.html",
                STATIC_ROOT + "pixiv-batch/workbench.html",
                STATIC_ROOT + "pixiv-batch/index.html")) {
            assertThat(loader.getResource(forbidden)).as("不得发布第二个 batch 页面资源: " + forbidden).isNull();
        }

        DownloadWorkbenchPlugin plugin = new DownloadWorkbenchPlugin();
        assertThat(plugin.staticResources())
                .extracting(resource -> resource.classpathLocation() + "|"
                        + resource.publicPathPrefix() + "|" + resource.exactFile())
                .containsExactly(
                        "classpath:/static/|/pixiv-batch.html|true",
                        "classpath:/static/pixiv-batch/|/pixiv-batch/|false");
    }
}
