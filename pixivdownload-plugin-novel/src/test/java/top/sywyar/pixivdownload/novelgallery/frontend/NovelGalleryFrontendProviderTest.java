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
    @DisplayName("声明正文 renderer 与详情操作且不再声明侧栏入口")
    void contributesTextRendererAndDetailActionsWithoutSidebarEntry() {
        Map<String, GalleryFrontendContribution> contributions =
                new NovelGalleryFrontendProvider().frontendContributions().stream()
                        .collect(Collectors.toMap(
                                GalleryFrontendContribution::contributionId, Function.identity()));

        assertThat(contributions).containsOnlyKeys("novel.text-renderer", "novel.detail-actions");
        assertThat(contributions.values()).extracting(GalleryFrontendContribution::moduleUrl)
                .containsOnly("/pixiv-novel-gallery/novel-gallery-frontend.js");

        GalleryFrontendContribution renderer = contributions.get("novel.text-renderer");
        assertThat(renderer.hooks()).containsExactly(GalleryFrontendHook.MEDIA_RENDERER);
        assertScope(renderer);
        assertThat(renderer.scope().mediaKinds()).containsExactly(GalleryMediaKind.TEXT);

        GalleryFrontendContribution actions = contributions.get("novel.detail-actions");
        assertThat(actions.hooks()).containsExactly(GalleryFrontendHook.DETAIL_ACTION);
        assertScope(actions);
        assertThat(actions.scope().mediaKinds())
                .containsExactlyInAnyOrder(GalleryMediaKind.TEXT, GalleryMediaKind.COVER);
    }

    private static void assertScope(GalleryFrontendContribution contribution) {
        assertThat(contribution.scope().sourceIds()).containsExactly("pixiv");
        assertThat(contribution.scope().sourceWorkNamespaces()).containsExactly("novel");
        assertThat(contribution.scope().galleryKinds()).containsExactly(GalleryKind.NOVEL);
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
