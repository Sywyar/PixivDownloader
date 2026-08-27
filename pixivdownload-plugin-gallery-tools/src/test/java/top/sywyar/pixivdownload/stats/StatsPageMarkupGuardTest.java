package top.sywyar.pixivdownload.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.DrilldownPlacements;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统计页前端静态守卫（随 stats 外置迁出主程序后，落到本模块——统计页静态资源现住本插件 jar）。
 * 镜像 app 端 {@code NavigationMarkupGuardTest} 中原本针对统计页的那条守卫：统计页外壳借用画廊能力的侧栏区块
 * （视图 / 收藏夹）已改由画廊插件经 {@code /api/page-sections} 贡献、Top 作者 / 热门标签下钻改由 {@code /api/drilldowns}
 * 贡献、侧栏导航改用宿主中立 placement，故统计页 HTML + JS 源码不得再含任何画廊插件知识（画廊侧栏 placement、
 * 画廊 i18n namespace、画廊页面 href、收藏夹 API、画廊筛选参数名、按插件 id 的可用性判断），只声明中立 slot 与语义 placement。
 */
@DisplayName("统计页静态守卫：只声明中立 slot、不含画廊插件知识")
class StatsPageMarkupGuardTest {

    private static String read(String resource) throws IOException {
        try (InputStream in = StatsPageMarkupGuardTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new NoSuchFileException(resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("统计页（HTML + JS）不含任何画廊插件知识：无 gallery.sidebar、gallery: 命名、/pixiv-gallery.html、/api/collections、画廊筛选参数名、isAvailable('gallery')；只声明中立 slot 与语义 placement")
    void statsPageHasNoGalleryPluginKnowledge() throws IOException {
        for (String source : List.of(read("static/pixiv-stats.html"), read("static/pixiv-stats/pixiv-stats.js"))) {
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
        assertThat(read("static/pixiv-stats.html"))
                .as("统计页应只声明通用区块 slot（内容由活动插件经 /api/page-sections 贡献）")
                .contains("data-section-slot=\"" + NavigationPlacements.STATS_SIDEBAR_SECTIONS + "\"");
        assertThat(read("static/pixiv-stats.html"))
                .as("统计页主侧栏导航应声明宿主中立的 app.sidebar slot")
                .contains("data-nav-slot=\"" + NavigationPlacements.APP_SIDEBAR + "\"");
        assertThat(read("static/pixiv-stats.html"))
                .as("统计页应加载核心共享 Vue 挂载 helper /js/pixiv-vue.js（导航 / 区块据其挂 Vue 主渲染，缺失即命令式回退；"
                        + "引用核心 /js/ 资源、非本插件自带运行时）")
                .contains("src=\"/js/pixiv-vue.js\"");
        // 统计页 JS 只按语义 placement 请求下钻 href（贡献方决定目标），故源码应出现这两个语义 placement 名。
        String statsJs = read("static/pixiv-stats/pixiv-stats.js");
        assertThat(statsJs)
                .as("统计页 JS 应按语义 placement stats.top-authors 请求作者下钻 href")
                .contains(DrilldownPlacements.STATS_TOP_AUTHORS);
        assertThat(statsJs)
                .as("统计页 JS 应按语义 placement stats.top-tags 请求标签下钻 href")
                .contains(DrilldownPlacements.STATS_TOP_TAGS);
    }
}
