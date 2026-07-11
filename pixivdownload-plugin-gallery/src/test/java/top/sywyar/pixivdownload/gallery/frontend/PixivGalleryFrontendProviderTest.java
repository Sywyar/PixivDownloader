package top.sywyar.pixivdownload.gallery.frontend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendContribution;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendHook;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
import top.sywyar.pixivdownload.gallery.GalleryPluginConfiguration;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Pixiv 图片画廊前端贡献")
class PixivGalleryFrontendProviderTest {

    @Test
    @DisplayName("为 Pixiv artwork 提供卡片、媒体和详情扩展且不再声明侧栏入口")
    void contributesArtworkRuntimeEnhancementsWithoutSidebarEntry() {
        Map<String, GalleryFrontendContribution> contributions =
                new PixivGalleryFrontendProvider().frontendContributions().stream()
                        .collect(Collectors.toMap(
                                GalleryFrontendContribution::contributionId, Function.identity()));

        assertThat(contributions).containsOnlyKeys("pixiv.card", "pixiv.media", "pixiv.detail-actions");
        assertThat(contributions.values()).extracting(GalleryFrontendContribution::moduleUrl)
                .containsOnly("/pixiv-gallery/pixiv-gallery-frontend.js");

        GalleryFrontendContribution card = contributions.get("pixiv.card");
        assertThat(card.hooks()).containsExactly(GalleryFrontendHook.CARD_EXTENSION);
        assertScope(card);
        assertThat(card.scope().mediaKinds()).isEmpty();

        GalleryFrontendContribution media = contributions.get("pixiv.media");
        assertThat(media.hooks()).containsExactly(GalleryFrontendHook.MEDIA_RENDERER);
        assertScope(media);
        assertThat(media.scope().mediaKinds()).containsExactlyInAnyOrder(
                GalleryMediaKind.IMAGE, GalleryMediaKind.UGOIRA);

        GalleryFrontendContribution actions = contributions.get("pixiv.detail-actions");
        assertThat(actions.hooks()).containsExactly(GalleryFrontendHook.DETAIL_ACTION);
        assertScope(actions);
        assertThat(actions.scope().mediaKinds()).containsExactlyInAnyOrder(
                GalleryMediaKind.IMAGE, GalleryMediaKind.UGOIRA);
    }

    @Test
    @DisplayName("配置类按 gallery 开关装配前端 provider")
    void configurationRegistersFrontendProvider() throws NoSuchMethodException {
        Method method = GalleryPluginConfiguration.class.getMethod("pixivGalleryFrontendProvider");

        assertThat(method.getAnnotation(Bean.class)).isNotNull();
        assertThat(method.getAnnotation(ConditionalOnPluginEnabled.class))
                .extracting(ConditionalOnPluginEnabled::value)
                .isEqualTo("gallery");
        assertThat(new GalleryPluginConfiguration().pixivGalleryFrontendProvider())
                .isInstanceOf(PixivGalleryFrontendProvider.class);
    }

    @Test
    @DisplayName("浏览器模块通过稳定 API 安全文本渲染并复用标准媒体 helper")
    void frontendModuleUsesStableApiAndSafeRendering() throws IOException {
        String script = resource("static/pixiv-gallery/pixiv-gallery-frontend.js");

        assertThat(script)
                .contains("window.PixivGalleryFrontend.registerModule")
                .contains("registerCardExtension")
                .contains("registerMediaRenderer")
                .contains("registerDetailAction")
                .contains("id: 'pixiv.card'")
                .contains("id: 'pixiv.media'")
                .contains("id: 'pixiv.detail-actions'")
                .contains("mediaKinds: ['IMAGE', 'UGOIRA']")
                .contains("window.PixivGalleryFrontend.renderStandardMedia")
                .contains("gallery:frontend.card.pages")
                .contains("gallery:frontend.action.open-artwork")
                .contains("'/pixiv-artwork.html?id='")
                .contains("encodeURIComponent")
                .contains("textContent")
                .doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "eval(");
    }

    private static void assertScope(GalleryFrontendContribution contribution) {
        assertThat(contribution.scope().sourceIds()).containsExactly("pixiv");
        assertThat(contribution.scope().sourceWorkNamespaces()).containsExactly("artwork");
        assertThat(contribution.scope().galleryKinds()).containsExactly(GalleryKind.IMAGE);
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = PixivGalleryFrontendProviderTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
