package top.sywyar.pixivdownload.douyin.gallery.frontend;

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

@DisplayName("Douyin 画廊前端贡献")
class DouyinGalleryFrontendProviderTest {

    @Test
    @DisplayName("分别声明图片视频入口并由同一模块扩展卡片和完整媒体集合")
    void contributesImageVideoViewsAndSharedRenderers() {
        Map<String, GalleryFrontendContribution> contributions =
                new DouyinGalleryFrontendProvider().frontendContributions().stream()
                        .collect(Collectors.toMap(
                                GalleryFrontendContribution::contributionId, Function.identity()));

        assertThat(contributions).containsOnlyKeys(
                "douyin.image-view", "douyin.video-view", "douyin.card", "douyin.media");
        assertThat(contributions.values()).extracting(GalleryFrontendContribution::moduleUrl)
                .containsOnly("/pixiv-douyin-download/douyin-gallery-frontend.js");

        assertView(contributions.get("douyin.image-view"), GalleryKind.IMAGE,
                "/pixiv-gallery.html?galleryKind=IMAGE&sourceId=douyin", "frontend.view.image", "image");
        assertView(contributions.get("douyin.video-view"), GalleryKind.VIDEO,
                "/pixiv-gallery.html?galleryKind=VIDEO&sourceId=douyin", "frontend.view.video", "video");

        GalleryFrontendContribution card = contributions.get("douyin.card");
        assertThat(card.hooks()).containsExactly(GalleryFrontendHook.CARD_EXTENSION);
        assertThat(card.scope().sourceIds()).containsExactly("douyin");
        assertThat(card.scope().sourceWorkNamespaces()).containsExactly("aweme");
        assertThat(card.scope().galleryKinds())
                .containsExactlyInAnyOrder(GalleryKind.IMAGE, GalleryKind.VIDEO);
        assertThat(card.scope().mediaKinds()).isEmpty();

        GalleryFrontendContribution media = contributions.get("douyin.media");
        assertThat(media.hooks()).containsExactly(GalleryFrontendHook.MEDIA_RENDERER);
        assertThat(media.scope().mediaKinds()).containsExactlyInAnyOrder(
                GalleryMediaKind.IMAGE,
                GalleryMediaKind.VIDEO,
                GalleryMediaKind.LIVE_PHOTO_VIDEO,
                GalleryMediaKind.COVER);
    }

    @Test
    @DisplayName("模块通过稳定注册 API 安全文本渲染且不执行动态代码")
    void frontendModuleUsesStableApiAndSafeTextRendering() throws IOException {
        String script = resource("static/pixiv-douyin-download/douyin-gallery-frontend.js");

        assertThat(script)
                .contains("window.PixivGalleryFrontend.registerModule")
                .contains("registerCardExtension")
                .contains("registerMediaRenderer")
                .contains("id: 'douyin.card'")
                .contains("id: 'douyin.media'")
                .contains("mediaKinds: ['IMAGE', 'VIDEO', 'LIVE_PHOTO_VIDEO', 'COVER']")
                .contains("renderStandardMedia")
                .contains("gallery-media-source gallery-media-douyin")
                .contains("work.author")
                .contains("context.t")
                .contains("textContent")
                .doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "eval(");
    }

    private static void assertView(GalleryFrontendContribution contribution,
                                   GalleryKind kind,
                                   String href,
                                   String i18nKey,
                                   String iconToken) {
        assertThat(contribution.hooks()).containsExactly(GalleryFrontendHook.VIEW_ENTRY);
        assertThat(contribution.viewHref()).isEqualTo(href);
        assertThat(contribution.displayNamespace()).isEqualTo("douyin");
        assertThat(contribution.displayI18nKey()).isEqualTo(i18nKey);
        assertThat(contribution.iconToken()).isEqualTo(iconToken);
        assertThat(contribution.scope().sourceIds()).containsExactly("douyin");
        assertThat(contribution.scope().sourceWorkNamespaces()).containsExactly("aweme");
        assertThat(contribution.scope().galleryKinds()).containsExactly(kind);
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = DouyinGalleryFrontendProviderTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
