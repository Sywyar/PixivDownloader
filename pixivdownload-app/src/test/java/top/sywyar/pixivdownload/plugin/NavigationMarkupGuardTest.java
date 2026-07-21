package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
 * 前端残留点开即 404 的坏入口）。
 * <p>
 * 扫描 app 公共资源与 gallery 外置插件页面资源（download-workbench / duplicate 页面已外置，其页面守卫随插件模块测试）：
 * <ul>
 *   <li>每个已适配页面声明其预期 placement slot（{@code data-nav-slot="<placement>"}）；</li>
 *   <li>已适配页面不再使用 {@code data-nav-include} / {@code data-nav-exclude} / {@code data-nav-requires}；</li>
 *   <li>已适配页面不得硬编码其它插件的入口 href（监控 / 统计 / 疑似重复 / 邀请码管理 / 画廊 / 小说画廊，按页面身份判定）；</li>
 *   <li>画廊家族类型切换必须使用同一个共享 slot，页面不得硬编码当前类型或其它插件入口。</li>
 * </ul>
 * 同插件内的页面自有链接不在禁止之列：画廊 / 系列页指向画廊视图的链接（{@code /pixiv-gallery.html?view=...}）是
 * 画廊家族页面的内部入口。统计页（pixiv-stats.html）与疑似重复页（pixiv-duplicates.html）已随对应插件外置，
 * 其页面守卫随静态资源一起迁到对应插件模块。
 * <p>
 * 另含两条与「跨插件未知」前提配套的渲染器守卫：公共导航渲染器不得为内置插件 id 硬编码默认 query
 * （{@link #navigationRendererHasNoBuiltInDefaultQuery()}）；类型切换跨页交接必须使用事件委托，不依赖异步 slot 链接
 * 在初始化时已存在，也不特判对方插件页面路径（{@link #typeSwitchHandoffUsesDelegatedClickHandling()}）。
 */
@DisplayName("前端导航静态守卫：已适配页面只声明空 slot、不硬编码跨插件入口")
class NavigationMarkupGuardTest {

    /** 已适配页面所在的 classpath 资源前缀（app 公共资源仍从 test runtime classpath 读取）。 */
    private static final String STATIC_CLASSPATH_ROOT = "static/";
    private static final List<Path> STATIC_SOURCE_ROOTS = List.of(
            Path.of("src/main/resources/static"),
            Path.of("pixivdownload-app/src/main/resources/static"),
            Path.of("../pixivdownload-app/src/main/resources/static"),
            Path.of("pixivdownload-plugin-gallery/src/main/resources/static"),
            Path.of("../pixivdownload-plugin-gallery/src/main/resources/static"),
            Path.of("pixivdownload-plugin-novel/src/main/resources/static"),
            Path.of("../pixivdownload-plugin-novel/src/main/resources/static"));

    /** 已接入动态导航 slot 的页面 → 其预期声明的 placement slot。 */
    private static final Map<String, List<String>> EXPECTED_SLOTS = Map.of(
            "monitor.html", List.of(NavigationPlacements.APP_TOP),
            "plugin-manage.html", List.of(NavigationPlacements.APP_TOP, NavigationPlacements.PLUGINS_SEGMENT),
            "plugin-market.html", List.of(NavigationPlacements.APP_TOP),
            "pixiv-gallery.html", List.of(NavigationPlacements.GALLERY_SIDEBAR, NavigationPlacements.GALLERY_TYPE_SWITCH),
            "pixiv-novel-gallery.html", List.of(NavigationPlacements.NOVEL_SIDEBAR, NavigationPlacements.GALLERY_TYPE_SWITCH),
            "pixiv-series.html", List.of(NavigationPlacements.GALLERY_SIDEBAR));

    /** 全部已知 placement（HTML slot 值必须取自此集合，确保前端 slot 与后端 contribution 名一致）。 */
    private static final Set<String> KNOWN_PLACEMENTS = Set.of(
            NavigationPlacements.APP_TOP, NavigationPlacements.APP_SIDEBAR,
            NavigationPlacements.GALLERY_SIDEBAR, NavigationPlacements.NOVEL_SIDEBAR,
            NavigationPlacements.GALLERY_TYPE_SWITCH,
            NavigationPlacements.DUPLICATES_HEADER_ICONS, NavigationPlacements.STATS_GALLERY_LINKS,
            NavigationPlacements.PLUGINS_SEGMENT);

    /**
     * 每页禁止再硬编码的「其它插件」入口 href（这些一律由动态 slot 渲染）。按页面身份判定：顶部栏页面（monitor /
     * batch）禁止全部功能页 + 管理入口；画廊 / 小说页禁止对方画廊 href（类型切换已是 slot）与监控 / 统计 / 疑似重复 /
     * 邀请码管理；系列页同画廊家族（允许画廊自身 href）；统计页禁止全部跨插件入口（含画廊——其借用画廊的侧栏区块已改由
     * {@code /api/page-sections} 贡献，统计页不再硬编码任何画廊 href）；疑似重复页守卫随 duplicate 模块资源迁移。
     * 各页页面自有的画廊视图链接不在此列。
     */
    private static final Map<String, List<String>> FORBIDDEN_HREFS = Map.of(
            "monitor.html", List.of("/pixiv-gallery.html", "/pixiv-novel-gallery.html",
                    "/pixiv-stats.html", "/pixiv-duplicates.html", "/pixiv-invite-manage.html"),
            "plugin-manage.html", List.of("/monitor.html", "/pixiv-gallery.html", "/pixiv-novel-gallery.html",
                    "/pixiv-stats.html", "/pixiv-duplicates.html", "/pixiv-invite-manage.html", "/plugin-market.html"),
            "pixiv-gallery.html", List.of("/monitor.html", "/pixiv-novel-gallery.html",
                    "/pixiv-stats.html", "/pixiv-duplicates.html", "/pixiv-invite-manage.html"),
            "pixiv-novel-gallery.html", List.of("/monitor.html", "/pixiv-gallery.html",
                    "/pixiv-stats.html", "/pixiv-duplicates.html", "/pixiv-invite-manage.html"),
            "pixiv-series.html", List.of("/monitor.html", "/pixiv-novel-gallery.html",
                    "/pixiv-stats.html", "/pixiv-duplicates.html", "/pixiv-invite-manage.html"));

    private static final Pattern NAV_SLOT = Pattern.compile("data-nav-slot=\"([^\"]+)\"");

    private static String read(String page) throws IOException {
        for (Path root : STATIC_SOURCE_ROOTS) {
            Path file = root.resolve(page);
            if (Files.isRegularFile(file)) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
        }
        String resource = STATIC_CLASSPATH_ROOT + page;
        try (InputStream in = NavigationMarkupGuardTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new NoSuchFileException(resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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
    @DisplayName("画廊与小说页的类型切换只声明共享 slot，不硬编码当前类型按钮")
    void typeSwitchEntriesUseSharedSlotOnly() throws IOException {
        for (String page : List.of("pixiv-gallery.html", "pixiv-novel-gallery.html")) {
            assertThat(read(page))
                    .as("页面 %s 应只由共享 slot 承载全部画廊类型入口", page)
                    .contains("data-nav-slot=\"" + NavigationPlacements.GALLERY_TYPE_SWITCH + "\"")
                    .doesNotContain("data-nav-slot=\"novel.type-switch\"")
                    .doesNotContain("<button type=\"button\" class=\"gallery-type-option");
        }
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
    @DisplayName("类型切换跨页交接使用插槽链接事件委托、不依赖链接同步存在、不特判对方插件路径")
    void typeSwitchHandoffUsesDelegatedClickHandling() throws IOException {
        // 画廊 / 小说页通过 document 事件委托接住异步生成的类型链接，无需监听导航渲染生命周期。
        for (String initJs : List.of("pixiv-gallery/gallery-init.js", "pixiv-novel-gallery/novel-gallery-init.js")) {
            assertThat(read(initJs))
                    .as("%s 应委托处理动态生成的共享类型切换链接", initJs)
                    .contains("document.addEventListener('click'", ".gallery-type-switch a[href]")
                    .doesNotContain("pixivnav:rendered");
        }
        // 类型切换交接不得特判「对方插件页面路径」（应对 slot 内全部链接统一处理）。
        assertThat(read("pixiv-gallery/gallery-state.js"))
                .as("画廊类型切换交接不应特判小说页路径")
                .doesNotContain("/pixiv-novel-gallery.html\"]");
        assertThat(read("pixiv-novel-gallery/novel-gallery-core.js"))
                .as("小说类型切换交接不应特判画廊页路径")
                .doesNotContain("/pixiv-gallery.html\"]");
    }
}
