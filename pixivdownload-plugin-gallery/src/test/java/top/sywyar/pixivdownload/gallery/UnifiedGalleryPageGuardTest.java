package top.sywyar.pixivdownload.gallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("统一画廊页面资源边界")
class UnifiedGalleryPageGuardTest {

    @Test
    @DisplayName("页面文案仅由 i18n 资源提供")
    void userVisibleCopyComesOnlyFromI18nResources() throws IOException {
        String html = read("static/unified-gallery.html");
        String core = read("static/unified-gallery/unified-gallery-core.js");
        String views = read("static/unified-gallery/unified-gallery-views.js");

        assertThat(html)
                .doesNotContain(">Gallery<", ">Unified gallery<", ">Images<", ">Videos<", ">Novels<",
                        ">Load more<", "aria-label=\"Gallery views\"", "aria-label=\"Gallery results\"",
                        "aria-label=\"Close\"");
        assertThat(core).doesNotContain("Loading…", "Unable to load gallery", "Unified gallery");
        assertThat(views).doesNotContain("Unable to load details", "Results: {count}", "No works found");
    }

    @Test
    @DisplayName("职责脚本保持经典模块且立即执行只位于 init")
    void classicModulesKeepImmediateExecutionInInitOnly() throws IOException {
        String core = read("static/unified-gallery/unified-gallery-core.js");
        String views = read("static/unified-gallery/unified-gallery-views.js");
        String init = read("static/unified-gallery/unified-gallery-init.js");

        assertThat(core).doesNotContain("(function(", "(window);");
        assertThat(views).doesNotContain("(function(", "(window);");
        assertThat(init).contains("initUnifiedGallery();");
    }

    private String read(String path) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
