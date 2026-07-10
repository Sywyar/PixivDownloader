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
    @DisplayName("旧画廊保留侧栏、搜索、筛选、收藏夹、批量管理和结果区域")
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
                        "id=\"authorView\"", "id=\"authorPagination\"", "id=\"mobileOverlay\"");
        assertThat(html)
                .contains("value=\"authorId\"", "value=\"tagExact\"", "data-sort=\"series\"", "data-r18=\"r18g\"",
                        "data-ai=\"yes\"", "data-format=\"webp\"",
                        "data-action=\"export\"", "data-action=\"collect\"", "data-action=\"delete\"");
    }

    @Test
    @DisplayName("旧画廊职责脚本按依赖顺序加载且 init 最后执行")
    void matureGalleryScriptsKeepResponsibilityOrder() throws IOException {
        String html = read("static/pixiv-gallery.html");
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
        assertThat(read("static/pixiv-gallery/gallery-init.js"))
                .contains("(async function init()", "wireBatchManage();");
    }

    @Test
    @DisplayName("简化 unified 页面及其专属资源不再存在")
    void simplifiedUnifiedPageResourcesAreRemoved() {
        Path staticRoot = sourceStaticRoot();
        assertThat(staticRoot.resolve("unified-gallery.html")).doesNotExist();
        assertThat(staticRoot.resolve("unified-gallery")).doesNotExist();
    }

    private String read(String path) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
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
