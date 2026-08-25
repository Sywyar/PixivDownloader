package top.sywyar.pixivdownload.gallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    @DisplayName("动态结果状态在静态国际化重渲染后保持当前语义")
    void dynamicGalleryStatusSurvivesStaticI18nRendering() throws IOException {
        String core = read("static/pixiv-gallery/gallery-core.js");
        String views = read("static/pixiv-gallery/gallery-views.js");

        int staticRendering = core.indexOf("pageI18n.apply(document.body);");
        int dynamicRendering = core.indexOf("renderGalleryStatus();", staticRendering);
        assertThat(staticRendering).isGreaterThanOrEqualTo(0);
        assertThat(dynamicRendering).isGreaterThan(staticRendering);
        assertThat(views)
                .contains("galleryStatusModel = {code, values: values || {}};",
                        "if (galleryStatusModel.code === 'range')",
                        "status.textContent = t('status.gallery-range'",
                        "galleryStatusModel.code === 'failure'",
                        "status.textContent = t('status.load-failed'",
                        "setGalleryStatus('loading');",
                        "setGalleryStatus('range', {",
                        "setGalleryStatus('failure', {message: e.message});");
    }

    @Test
    @DisplayName("正式主画廊只允许最新筛选请求更新结果")
    void staleGalleryResponsesCannotOverwriteNewerFilters() throws IOException {
        String views = read("static/pixiv-gallery/gallery-views.js");

        int request = views.indexOf("const result = await api('/api/gallery/artworks?'");
        int successGuard = views.indexOf("if (requestRevision !== galleryLoadRevision) return;", request);
        int render = views.indexOf("renderGallery(result.content || []);", successGuard);
        int failure = views.indexOf("} catch (e) {", render);
        int failureGuard = views.indexOf("if (requestRevision !== galleryLoadRevision) return;", failure);
        int failureRender = views.indexOf("setGalleryStatus('failure'", failureGuard);

        assertThat(views).contains("let galleryLoadRevision = 0;",
                "const requestRevision = ++galleryLoadRevision;");
        assertThat(successGuard).isGreaterThan(request);
        assertThat(render).isGreaterThan(successGuard);
        assertThat(failureGuard).isGreaterThan(failure);
        assertThat(failureRender).isGreaterThan(failureGuard);
    }

    @Test
    @DisplayName("正式主画廊不依赖已移除的通用画廊运行时")
    void mainGalleryDoesNotDependOnRemovedGenericRuntime() throws IOException {
        String html = read("static/pixiv-gallery.html");

        assertThat(html).doesNotContain(
                "gallery-frontend-runtime.js",
                "gallery-generic-view.js",
                "/api/gallery/unified/");
    }

    @Test
    @DisplayName("通用画廊页面、运行时和来源适配资源不再存在")
    void genericGalleryResourcesAreRemoved() {
        Path staticRoot = sourceStaticRoot();
        assertThat(staticRoot.resolve("unified-gallery.html")).doesNotExist();
        assertThat(staticRoot.resolve("unified-gallery")).doesNotExist();
        assertThat(staticRoot.resolve("pixiv-gallery/gallery-frontend-runtime.js")).doesNotExist();
        assertThat(staticRoot.resolve("pixiv-gallery/gallery-generic-view.js")).doesNotExist();
        assertThat(staticRoot.resolve("pixiv-gallery/pixiv-gallery-frontend.js")).doesNotExist();
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

    private static Path sourceStaticRoot() {
        Path moduleRoot = Path.of("src/main/resources/static");
        if (Files.isDirectory(moduleRoot)) {
            return moduleRoot;
        }
        return Path.of("pixivdownload-plugin-gallery/src/main/resources/static");
    }
}
