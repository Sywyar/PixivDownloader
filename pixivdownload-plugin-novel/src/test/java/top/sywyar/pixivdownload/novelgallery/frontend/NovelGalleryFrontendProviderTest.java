package top.sywyar.pixivdownload.novelgallery.frontend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendContribution;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendHook;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("小说画廊前端贡献")
class NovelGalleryFrontendProviderTest {

    @Test
    @DisplayName("声明成熟小说画廊入口、正文 renderer 与详情操作")
    void contributesNovelViewTextRendererAndDetailActions() {
        Map<String, GalleryFrontendContribution> contributions =
                new NovelGalleryFrontendProvider().frontendContributions().stream()
                        .collect(Collectors.toMap(
                                GalleryFrontendContribution::contributionId, Function.identity()));

        assertThat(contributions).containsOnlyKeys(
                "novel.view", "novel.text-renderer", "novel.detail-actions");

        GalleryFrontendContribution view = contributions.get("novel.view");
        assertThat(view.moduleUrl()).isEqualTo("/pixiv-novel-gallery/novel-gallery-frontend.js");
        assertThat(view.hooks()).containsExactly(GalleryFrontendHook.VIEW_ENTRY);
        assertThat(view.viewHref()).isEqualTo("/pixiv-novel-gallery.html?view=all");
        assertThat(view.displayNamespace()).isEqualTo("novel-gallery");
        assertThat(view.displayI18nKey()).isEqualTo("frontend.view.novel");
        assertThat(view.scope().sourceIds()).containsExactly("pixiv");
        assertThat(view.scope().sourceWorkNamespaces()).containsExactly("novel");
        assertThat(view.scope().galleryKinds()).containsExactly(GalleryKind.NOVEL);
        assertThat(view.scope().mediaKinds())
                .containsExactlyInAnyOrder(GalleryMediaKind.TEXT, GalleryMediaKind.COVER);

        GalleryFrontendContribution renderer = contributions.get("novel.text-renderer");
        assertThat(renderer.hooks()).containsExactly(GalleryFrontendHook.MEDIA_RENDERER);
        assertThat(renderer.scope().mediaKinds()).containsExactly(GalleryMediaKind.TEXT);

        GalleryFrontendContribution actions = contributions.get("novel.detail-actions");
        assertThat(actions.hooks()).containsExactly(GalleryFrontendHook.DETAIL_ACTION);
        assertThat(actions.scope().mediaKinds())
                .containsExactlyInAnyOrder(GalleryMediaKind.TEXT, GalleryMediaKind.COVER);
    }

    @Test
    @DisplayName("模块通过稳定注册 API 安全文本渲染且不执行动态代码")
    void frontendModuleUsesStableApiAndSafeTextRendering() throws IOException {
        String script = resource("static/pixiv-novel-gallery/novel-gallery-frontend.js");

        assertThat(script)
                .contains("window.PixivGalleryFrontend.registerModule")
                .contains("registerMediaRenderer")
                .contains("registerDetailAction")
                .contains("id: 'novel.text-renderer'")
                .contains("mediaKinds: ['TEXT']")
                .contains("media.content")
                .contains("context.t")
                .contains("textContent")
                .doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "eval(");
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = NovelGalleryFrontendProviderTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
