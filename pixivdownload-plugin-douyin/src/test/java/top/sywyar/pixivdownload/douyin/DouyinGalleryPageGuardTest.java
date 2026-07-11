package top.sywyar.pixivdownload.douyin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("抖音画廊与作品详情页面边界")
class DouyinGalleryPageGuardTest {

    @Test
    @DisplayName("画廊顶部只承载共享类型插槽且侧栏只声明作品分类")
    void gallerySeparatesTypesFromCategories() throws IOException {
        String html = resource("static/pixiv-douyin-gallery.html");

        assertThat(html)
                .contains("data-nav-slot=\"gallery.type-switch\"",
                        "data-gallery-view=\"all\"",
                        "data-gallery-view=\"image\"",
                        "data-gallery-view=\"video\"")
                .doesNotContain("galleryFrontendNav", "/pixiv-gallery.html", "/pixiv-novel-gallery.html");
        assertScriptsInOrder(html, List.of(
                "/pixiv-douyin-gallery/douyin-gallery-core.js",
                "/pixiv-douyin-gallery/douyin-gallery-view.js",
                "/pixiv-douyin-gallery/douyin-gallery-init.js"));
    }

    @Test
    @DisplayName("作品卡片跳转独立详情并安全携带首选媒体与返回位置")
    void cardsOpenStandaloneDetailPage() throws IOException {
        String core = resource("static/pixiv-douyin-gallery/douyin-gallery-core.js");
        String view = resource("static/pixiv-douyin-gallery/douyin-gallery-view.js");

        assertThat(core)
                .contains("'/api/douyin/gallery/projections?'",
                        "'/pixiv-douyin.html?'",
                        "params.set('preferredMediaId'",
                        "params.set('returnTo'")
                .doesNotContain("innerHTML", "insertAdjacentHTML");
        assertThat(view)
                .contains("createElement('a')", "link.href = href", "textContent")
                .doesNotContain("innerHTML", "onclick");
    }

    @Test
    @DisplayName("详情页按职责拆分并只接受抖音画廊返回地址")
    void detailPageUsesModularSafeRendering() throws IOException {
        String html = resource("static/pixiv-douyin.html");
        String core = resource("static/pixiv-douyin/douyin-core.js");
        String render = resource("static/pixiv-douyin/douyin-render.js");
        String css = resource("static/pixiv-douyin/pixiv-douyin.css");

        assertScriptsInOrder(html, List.of(
                "/pixiv-douyin/douyin-core.js",
                "/pixiv-douyin/douyin-render.js",
                "/pixiv-douyin/douyin-init.js"));
        assertThat(core)
                .contains("'/api/douyin/gallery/works/'",
                        "resolved.pathname !== '/pixiv-douyin-gallery.html'",
                        "'/pixiv-douyin-gallery.html?view=all'")
                .doesNotContain("failure.message", "innerHTML");
        assertThat(render)
                .contains("'IMAGE'", "'VIDEO'", "'LIVE_PHOTO_VIDEO'", "'COVER'", "textContent")
                .doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML");
        assertThat(resource("static/pixiv-douyin/douyin-init.js"))
                .contains("pageI18n.apply(document)", "next.apply(document)")
                .doesNotContain("apply(document.body)");
        assertThat(css).contains("html[data-theme=\"dark\"]", "@media (max-width: 900px)");
    }

    private static void assertScriptsInOrder(String html, List<String> scripts) {
        int previous = -1;
        for (String script : scripts) {
            int current = html.indexOf("src=\"" + script + "\"");
            assertThat(current).as(script).isGreaterThan(previous);
            previous = current;
        }
    }

    private static String resource(String path) throws IOException {
        try (var input = DouyinGalleryPageGuardTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
