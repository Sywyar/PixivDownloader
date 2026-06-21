package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.DrilldownPlacements;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 前端导航静态守卫：已接入「placement / slot」的页面只声明空 slot，跨插件入口由后端 placement 决定，
 * 页面不得再用 include/exclude/requires 过滤 id，也不得硬编码其它插件的入口 href（否则禁用对应插件后
 * 前端残留点开即 404 的坏入口，违背前端导航开槽工作包目标）。
 * <p>
 * 直接扫描 {@code src/main/resources/static} 下的页面源码：
 * <ul>
 *   <li>每个已适配页面声明其预期 placement slot（{@code data-nav-slot="<placement>"}）；</li>
 *   <li>已适配页面不再使用 {@code data-nav-include} / {@code data-nav-exclude} / {@code data-nav-requires}；</li>
 *   <li>已适配页面不得硬编码其它插件的入口 href（监控 / 统计 / 疑似重复 / 邀请码管理 / 画廊 / 小说画廊，按页面身份判定）；</li>
 *   <li>类型切换的对方入口（画廊页的「小说」、小说页的「漫画」）必须是 slot、不得硬编码对方 href。</li>
 * </ul>
 * 同插件内的页面自有链接不在禁止之列：画廊 / 系列页指向画廊视图的链接（{@code /pixiv-gallery.html?view=...}）是
 * 画廊家族页面的内部入口。统计页则不在此列——它借用画廊能力的侧栏区块（视图 / 收藏夹）已改由画廊插件经
 * {@code /api/page-sections} 贡献、由 {@code /js/pixiv-page-sections.js} 渲染，统计页源码（HTML + JS）不得再含任何
 * 画廊 href / collection API / 按插件 id 的可用性判断（由 {@link #statsPageHasNoGalleryPluginKnowledge()} 守卫）。
 * <p>
 * 另含两条与「跨插件未知」前提配套的渲染器守卫：公共导航渲染器不得为内置插件 id 硬编码默认 query
 * （{@link #navigationRendererHasNoBuiltInDefaultQuery()}）；类型切换跨页交接必须走 PixivNav 渲染生命周期事件、
 * 不依赖异步 slot 链接同步存在，也不特判对方插件页面路径（{@link #typeSwitchHandoffUsesRenderLifecycle()}）。
 */
@DisplayName("前端导航静态守卫：已适配页面只声明空 slot、不硬编码跨插件入口")
class NavigationMarkupGuardTest {

    private static final Path STATIC_ROOT = Path.of("src", "main", "resources", "static");

    /** 已接入动态导航 slot 的页面 → 其预期声明的 placement slot。 */
    private static final Map<String, List<String>> EXPECTED_SLOTS = Map.of(
            "monitor.html", List.of(NavigationPlacements.APP_TOP),
            "pixiv-batch.html", List.of(NavigationPlacements.APP_TOP),
            "pixiv-gallery.html", List.of(NavigationPlacements.GALLERY_SIDEBAR, NavigationPlacements.GALLERY_TYPE_SWITCH),
            "pixiv-novel-gallery.html", List.of(NavigationPlacements.NOVEL_SIDEBAR, NavigationPlacements.NOVEL_TYPE_SWITCH),
            "pixiv-series.html", List.of(NavigationPlacements.GALLERY_SIDEBAR),
            // 统计页改用宿主中立的主侧栏 placement（app.sidebar），不再借画廊家族侧栏（gallery.sidebar）。
            "pixiv-stats.html", List.of(NavigationPlacements.APP_SIDEBAR),
            "pixiv-duplicates.html", List.of(NavigationPlacements.DUPLICATES_HEADER_ICONS));

    /** 全部已知 placement（HTML slot 值必须取自此集合，确保前端 slot 与后端 contribution 名一致）。 */
    private static final Set<String> KNOWN_PLACEMENTS = Set.of(
            NavigationPlacements.APP_TOP, NavigationPlacements.APP_SIDEBAR,
            NavigationPlacements.GALLERY_SIDEBAR, NavigationPlacements.NOVEL_SIDEBAR,
            NavigationPlacements.GALLERY_TYPE_SWITCH, NavigationPlacements.NOVEL_TYPE_SWITCH,
            NavigationPlacements.DUPLICATES_HEADER_ICONS, NavigationPlacements.STATS_GALLERY_LINKS);

    /**
     * 每页禁止再硬编码的「其它插件」入口 href（这些一律由动态 slot 渲染）。按页面身份判定：顶部栏页面（monitor /
     * batch）禁止全部功能页 + 管理入口；画廊 / 小说页禁止对方画廊 href（类型切换已是 slot）与监控 / 统计 / 疑似重复 /
     * 邀请码管理；系列页同画廊家族（允许画廊自身 href）；统计页禁止全部跨插件入口（含画廊——其借用画廊的侧栏区块已改由
     * {@code /api/page-sections} 贡献，统计页不再硬编码任何画廊 href）；疑似重复页禁止画廊 / 统计图标硬编码（已是 slot）。
     * 各页页面自有的画廊视图链接不在此列。
     */
    private static final Map<String, List<String>> FORBIDDEN_HREFS = Map.of(
            "monitor.html", List.of("/pixiv-gallery.html", "/pixiv-novel-gallery.html",
                    "/pixiv-stats.html", "/pixiv-duplicates.html", "/pixiv-invite-manage.html"),
            "pixiv-batch.html", List.of("/monitor.html", "/pixiv-gallery.html", "/pixiv-novel-gallery.html",
                    "/pixiv-stats.html", "/pixiv-duplicates.html", "/pixiv-invite-manage.html"),
            "pixiv-gallery.html", List.of("/monitor.html", "/pixiv-novel-gallery.html",
                    "/pixiv-stats.html", "/pixiv-duplicates.html", "/pixiv-invite-manage.html"),
            "pixiv-novel-gallery.html", List.of("/monitor.html", "/pixiv-gallery.html",
                    "/pixiv-stats.html", "/pixiv-duplicates.html", "/pixiv-invite-manage.html"),
            "pixiv-series.html", List.of("/monitor.html", "/pixiv-novel-gallery.html",
                    "/pixiv-stats.html", "/pixiv-duplicates.html", "/pixiv-invite-manage.html"),
            "pixiv-stats.html", List.of("/monitor.html", "/pixiv-gallery.html", "/pixiv-novel-gallery.html",
                    "/pixiv-duplicates.html", "/pixiv-invite-manage.html"),
            "pixiv-duplicates.html", List.of("/pixiv-gallery.html", "/pixiv-stats.html",
                    "/monitor.html", "/pixiv-novel-gallery.html", "/pixiv-invite-manage.html"));

    private static final Pattern NAV_SLOT = Pattern.compile("data-nav-slot=\"([^\"]+)\"");

    private static String read(String page) throws IOException {
        return Files.readString(STATIC_ROOT.resolve(page), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("每个已适配页面都声明其预期 placement slot（跨插件导航走 /api/navigation 动态渲染）")
    void adaptedPagesDeclareExpectedSlots() throws IOException {
        for (Map.Entry<String, List<String>> entry : EXPECTED_SLOTS.entrySet()) {
            String content = read(entry.getKey());
            for (String placement : entry.getValue()) {
                assertThat(content)
                        .as("页面 %s 应声明空 slot data-nav-slot=\"%s\"", entry.getKey(), placement)
                        .contains("data-nav-slot=\"" + placement + "\"");
            }
        }
    }

    @Test
    @DisplayName("已适配页面不再使用 data-nav-include / data-nav-exclude / data-nav-requires（入口归属由后端 placement 决定）")
    void adaptedPagesDoNotUseLegacyNavFilters() throws IOException {
        for (String page : EXPECTED_SLOTS.keySet()) {
            String content = read(page);
            assertThat(content)
                    .as("页面 %s 不应再用 data-nav-include 过滤 id（改用 placement slot）", page)
                    .doesNotContain("data-nav-include=\"");
            assertThat(content)
                    .as("页面 %s 不应再用 data-nav-exclude 过滤 id（改用 placement slot）", page)
                    .doesNotContain("data-nav-exclude=\"");
            assertThat(content)
                    .as("页面 %s 插件入口不得用 data-nav-requires 条件隐藏（改用 placement slot）", page)
                    .doesNotContain("data-nav-requires=\"");
        }
    }

    @Test
    @DisplayName("已适配页面不得硬编码其它插件入口 href（应由动态 slot 渲染，否则禁用对应插件后残留点开即 404 的坏入口）")
    void adaptedPagesDoNotHardcodeCrossPluginEntries() throws IOException {
        for (Map.Entry<String, List<String>> entry : FORBIDDEN_HREFS.entrySet()) {
            String content = read(entry.getKey());
            for (String href : entry.getValue()) {
                assertThat(content)
                        .as("页面 %s 不应再硬编码跨插件入口 href=\"%s\"（应由动态导航 slot 渲染）", entry.getKey(), href)
                        .doesNotContain("href=\"" + href + "\"")
                        .doesNotContain("href=\"" + href + "?");
            }
        }
    }

    @Test
    @DisplayName("类型切换对方入口为 slot：画廊页声明 gallery.type-switch、小说页声明 novel.type-switch（不硬编码对方 href）")
    void typeSwitchEntriesAreSlots() throws IOException {
        assertThat(read("pixiv-gallery.html"))
                .as("画廊页应以 slot 承载「小说」类型切换入口")
                .contains("data-nav-slot=\"" + NavigationPlacements.GALLERY_TYPE_SWITCH + "\"");
        assertThat(read("pixiv-novel-gallery.html"))
                .as("小说画廊页应以 slot 承载「漫画」类型切换入口")
                .contains("data-nav-slot=\"" + NavigationPlacements.NOVEL_TYPE_SWITCH + "\"");
    }

    @Test
    @DisplayName("已适配页面的所有 data-nav-slot 取值都是已知 placement（前端 slot 名与后端 contribution 一致）")
    void allDeclaredSlotsAreKnownPlacements() throws IOException {
        for (String page : EXPECTED_SLOTS.keySet()) {
            String content = read(page);
            Matcher m = NAV_SLOT.matcher(content);
            while (m.find()) {
                assertThat(KNOWN_PLACEMENTS)
                        .as("页面 %s 的 slot %s 必须是已知 placement（见 NavigationPlacements）", page, m.group(1))
                        .contains(m.group(1));
            }
        }
    }

    @Test
    @DisplayName("统计页（HTML + JS）不含任何画廊插件知识：无 gallery.sidebar、gallery: 命名、/pixiv-gallery.html、/api/collections、画廊筛选参数名、isAvailable('gallery')；只声明中立 slot 与语义 placement")
    void statsPageHasNoGalleryPluginKnowledge() throws IOException {
        // 借用画廊能力的区块改由 /api/page-sections 贡献、Top 作者 / 热门标签的下钻改由 /api/drilldowns 贡献、侧栏导航
        // 改用宿主中立 placement：统计页外壳不再知道画廊是哪个插件、是否启用，故其 HTML 与 JS 源码都不得含画廊侧栏
        // placement、画廊 i18n namespace、画廊页面 href、收藏夹 API、画廊筛选查询参数名或按插件 id 的可用性判断。
        for (String source : List.of(read("pixiv-stats.html"), read("pixiv-stats/pixiv-stats.js"))) {
            assertThat(source)
                    .as("统计页外壳不应借用画廊家族侧栏 placement（改用中立的 app.sidebar）")
                    .doesNotContain(NavigationPlacements.GALLERY_SIDEBAR);
            assertThat(source)
                    .as("统计页外壳不应借用画廊 i18n namespace（品牌 / 导航标题 / 在线 / 菜单按钮改用 stats namespace）")
                    .doesNotContain("gallery:");
            assertThat(source)
                    .as("统计页不应硬编码画廊页面 href（画廊下钻 / 区块改由 /api/drilldowns、/api/page-sections 贡献）")
                    .doesNotContain("/pixiv-gallery.html");
            assertThat(source)
                    .as("统计页不应调用画廊的收藏夹 API（收藏夹列表由画廊自有模块渲染）")
                    .doesNotContain("/api/collections");
            // 画廊筛选查询参数名属画廊业务，只能出现在画廊插件贡献的 hrefTemplate 里（运行期经 /api/drilldowns 下发），
            // 不得出现在统计页源码——统计页只提供语义变量值（authorId/authorName/tagId/tagName/tagTranslatedName）。
            assertThat(source)
                    .as("统计页不应出现画廊作者筛选参数名 filterAuthorId（属画廊业务、由 hrefTemplate 承载）")
                    .doesNotContain("filterAuthorId");
            assertThat(source)
                    .as("统计页不应出现画廊作者筛选参数名 filterAuthorName")
                    .doesNotContain("filterAuthorName");
            assertThat(source)
                    .as("统计页不应出现画廊标签筛选参数名 filterTagId")
                    .doesNotContain("filterTagId");
            assertThat(source)
                    .as("统计页不应出现画廊标签筛选参数名 filterTag / filterTagTranslated")
                    .doesNotContain("filterTag");
            assertThat(source)
                    .as("统计页不应按插件 id 判断画廊是否可用（下钻 / 区块随插件禁用自然消失）")
                    .doesNotContain("isAvailable('gallery')")
                    .doesNotContain("isAvailable(\"gallery\")");
        }
        assertThat(read("pixiv-stats.html"))
                .as("统计页应只声明通用区块 slot（内容由活动插件经 /api/page-sections 贡献）")
                .contains("data-section-slot=\"" + NavigationPlacements.STATS_SIDEBAR_SECTIONS + "\"");
        assertThat(read("pixiv-stats.html"))
                .as("统计页主侧栏导航应声明宿主中立的 app.sidebar slot")
                .contains("data-nav-slot=\"" + NavigationPlacements.APP_SIDEBAR + "\"");
        // 统计页 JS 只按语义 placement 请求下钻 href（贡献方决定目标），故源码应出现这两个语义 placement 名。
        String statsJs = read("pixiv-stats/pixiv-stats.js");
        assertThat(statsJs)
                .as("统计页 JS 应按语义 placement stats.top-authors 请求作者下钻 href")
                .contains(DrilldownPlacements.STATS_TOP_AUTHORS);
        assertThat(statsJs)
                .as("统计页 JS 应按语义 placement stats.top-tags 请求标签下钻 href")
                .contains(DrilldownPlacements.STATS_TOP_TAGS);
    }

    @Test
    @DisplayName("公共导航渲染器不为内置插件 id 硬编码默认 query（href 由贡献方完整声明）")
    void navigationRendererHasNoBuiltInDefaultQuery() throws IOException {
        String js = read("js/pixiv-navigation.js");
        assertThat(js)
                .as("pixiv-navigation.js 不应再有 DEFAULT_QUERY 这类按插件 id 补 query 的表")
                .doesNotContain("DEFAULT_QUERY");
        assertThat(js)
                .as("pixiv-navigation.js 不应硬编码画廊 / 小说的 ?view=all 默认 query")
                .doesNotContain("?view=all");
    }

    @Test
    @DisplayName("类型切换跨页交接走 PixivNav 渲染生命周期事件、不依赖 slot 链接同步存在、不特判对方插件路径")
    void typeSwitchHandoffUsesRenderLifecycle() throws IOException {
        // 渲染器每次渲染（含失败降级）后派发稳定事件，供宿主在动态链接就位后再处理。
        assertThat(read("js/pixiv-navigation.js"))
                .as("pixiv-navigation.js 应在每次渲染后派发 pixivnav:rendered 生命周期事件")
                .contains("pixivnav:rendered");
        // 画廊 / 小说页交接监听该事件做 href 同步（而非 init 时一次性遍历当时尚未生成的 slot 链接逐个绑定）。
        for (String initJs : List.of("pixiv-gallery/gallery-init.js", "pixiv-novel-gallery/novel-gallery-init.js")) {
            assertThat(read(initJs))
                    .as("%s 应监听 pixivnav:rendered，在动态类型切换链接生成后再同步 view / 登记交接", initJs)
                    .contains("pixivnav:rendered");
        }
        // 类型切换 href 同步不得特判「对方插件页面路径」（应对 slot 内全部链接统一处理）。
        assertThat(read("pixiv-gallery/gallery-state.js"))
                .as("画廊类型切换 href 同步不应特判小说页路径")
                .doesNotContain("/pixiv-novel-gallery.html\"]");
        assertThat(read("pixiv-novel-gallery/novel-gallery-core.js"))
                .as("小说类型切换 href 同步不应特判画廊页路径")
                .doesNotContain("/pixiv-gallery.html\"]");
    }
}
