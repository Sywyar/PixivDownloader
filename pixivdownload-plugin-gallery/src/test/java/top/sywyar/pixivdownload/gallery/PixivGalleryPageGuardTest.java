package top.sywyar.pixivdownload.gallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("成熟画廊页面资源边界")
class PixivGalleryPageGuardTest {

    @Test
    @DisplayName("正式主画廊保留侧栏、搜索、筛选、收藏夹、批量管理和结果区域")
    void matureGalleryStructureRemainsAvailable() throws IOException {
        String html = read("static/pixiv-gallery.html");

        assertThat(html)
                .contains("id=\"sidebar\"", "id=\"galleryViewNav\"",
                        "data-view=\"all\"", "data-view=\"authors\"", "data-view=\"series\"",
                        "id=\"searchType\"", "id=\"searchInput\"", "id=\"filterPanel\"",
                        "id=\"filterCollectionChips\"", "id=\"filterSeriesChips\"",
                        "id=\"filterTagChips\"", "id=\"filterAuthorChips\"",
                        "id=\"collectionList\"", "id=\"batchManageBtn\"", "id=\"batchActionBar\"",
                        "id=\"galleryStatus\"", "id=\"galleryGrid\"", "id=\"pagination\"",
                        "id=\"authorView\"", "id=\"authorPagination\"", "id=\"mobileOverlay\"",
                        "data-nav-slot=\"gallery.type-switch\"");
        assertThat(html)
                .contains("value=\"authorId\"", "value=\"tagExact\"", "data-sort=\"series\"", "data-r18=\"r18g\"",
                        "data-ai=\"yes\"", "data-format=\"webp\"",
                        "data-action=\"export\"", "data-action=\"collect\"", "data-action=\"delete\"")
                .doesNotContain("id=\"galleryFrontendNav\"")
                .doesNotContain("id=\"galleryGenericFilters\"", "id=\"galleryGenericDetail\"")
                .doesNotContain("<button type=\"button\" class=\"gallery-type-option");
    }

    @Test
    @DisplayName("正式主画廊职责脚本按依赖顺序加载且 init 最后执行")
    void matureGalleryScriptsKeepResponsibilityOrder() throws IOException {
        String html = read("static/pixiv-gallery.html");
        String init = read("static/pixiv-gallery/gallery-init.js");
        List<String> scripts = List.of(
                "/pixiv-gallery/gallery-core.js",
                "/pixiv-gallery/gallery-state.js",
                "/pixiv-gallery/gallery-filters.js",
                "/pixiv-gallery/gallery-collections.js",
                "/pixiv-gallery/gallery-batch.js",
                "/pixiv-gallery/gallery-views.js",
                "/pixiv-gallery/gallery-sidebar.js",
                "/pixiv-gallery/gallery-init.js");

        int previous = -1;
        for (String script : scripts) {
            int current = html.indexOf("src=\"" + script + "\"");
            assertThat(current).as("页面应加载 %s", script).isGreaterThan(previous);
            previous = current;
        }
        assertThat(init)
                .contains("(async function init()", "wireBatchManage();", "loadPrimary();")
                .doesNotContain("PixivGalleryFrontend", "galleryGeneric", "galleryFrontendNav",
                        "navigationHost", "existingHrefs");
        assertThat(init.indexOf("await i18nReady;"))
                .isGreaterThan(init.indexOf("const i18nReady = initPageI18n()"))
                .isLessThan(init.indexOf("loadPrimary();"));
        assertThat(html).doesNotContain(
                "/pixiv-gallery/gallery-frontend-runtime.js",
                "/pixiv-gallery/gallery-generic-view.js");
    }

    @Test
    @DisplayName("画廊页面按职责顺序加载自有样式")
    void galleryStylesheetsKeepResponsibilityOrder() throws IOException {
        assertStylesheetOrder("static/pixiv-gallery.html", List.of(
                "/pixiv-gallery/pixiv-gallery.css",
                "/pixiv-gallery/pixiv-gallery-controls.css",
                "/pixiv-gallery/pixiv-gallery-grid.css",
                "/pixiv-gallery/pixiv-gallery-management.css",
                "/pixiv-gallery/pixiv-gallery-responsive.css"));
        assertStylesheetOrder("static/pixiv-showcase.html", List.of(
                "/pixiv-showcase/pixiv-showcase.css",
                "/pixiv-showcase/pixiv-showcase-hero.css",
                "/pixiv-showcase/pixiv-showcase-content.css",
                "/pixiv-showcase/pixiv-showcase-overlays.css",
                "/pixiv-showcase/pixiv-showcase-responsive.css"));
        assertStylesheetOrder("static/pixiv-series.html", List.of(
                "/pixiv-series/pixiv-series.css",
                "/pixiv-series/pixiv-series-content.css",
                "/pixiv-series/pixiv-series-responsive.css"));
        assertStylesheetOrder("static/pixiv-artwork.html", List.of(
                "/pixiv-artwork/pixiv-artwork.css",
                "/pixiv-artwork/pixiv-artwork-overlays.css",
                "/pixiv-artwork/pixiv-artwork-theme.css"));
    }

    private String read(String path) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void assertStylesheetOrder(String page, List<String> stylesheets) throws IOException {
        String html = read(page);
        int previous = -1;
        for (String stylesheet : stylesheets) {
            int current = html.indexOf("href=\"" + stylesheet + "\"");
            assertThat(current).as("%s 应加载 %s", page, stylesheet).isGreaterThan(previous);
            previous = current;
            assertThat(read("static" + stylesheet)).as(stylesheet).isNotBlank();
        }
    }

}
