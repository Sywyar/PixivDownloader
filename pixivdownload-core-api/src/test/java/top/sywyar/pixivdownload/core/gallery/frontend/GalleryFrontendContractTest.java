package top.sywyar.pixivdownload.core.gallery.frontend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("画廊前端贡献纯 Java 契约")
class GalleryFrontendContractTest {

    @Test
    @DisplayName("作用域按维度交集匹配且空集合表示通配")
    void scopeMatchesByTypedDimensions() {
        GalleryFrontendScope scope = new GalleryFrontendScope(
                Set.of("pixiv"), Set.of("artwork"),
                Set.of(GalleryKind.IMAGE), Set.of(GalleryMediaKind.IMAGE, GalleryMediaKind.UGOIRA));

        assertThat(scope.matches("pixiv", "artwork", GalleryKind.IMAGE, GalleryMediaKind.IMAGE)).isTrue();
        assertThat(scope.matches("pixiv", "novel", GalleryKind.IMAGE, GalleryMediaKind.IMAGE)).isFalse();
        assertThat(scope.matches("pixiv", "artwork", GalleryKind.VIDEO, GalleryMediaKind.IMAGE)).isFalse();
        assertThat(GalleryFrontendScope.any()
                .matches("third-party", "work", GalleryKind.VIDEO, GalleryMediaKind.VIDEO)).isTrue();
    }

    @Test
    @DisplayName("作用域与 hook 集合不可变且不受调用方集合后续修改影响")
    void contributionCollectionsAreImmutableCopies() {
        Set<String> sourceIds = new HashSet<>(Set.of("pixiv"));
        Set<GalleryFrontendHook> hooks = new HashSet<>(Set.of(GalleryFrontendHook.CARD_EXTENSION));
        GalleryFrontendContribution contribution = new GalleryFrontendContribution(
                "pixiv.card", "/pixiv-gallery/gallery-frontend.js",
                new GalleryFrontendScope(sourceIds, Set.of(), Set.of(GalleryKind.IMAGE), Set.of()),
                hooks, null, null, null, null, 20);

        sourceIds.add("other");
        hooks.add(GalleryFrontendHook.DETAIL_ACTION);

        assertThat(contribution.scope().sourceIds()).containsExactly("pixiv");
        assertThat(contribution.hooks()).containsExactly(GalleryFrontendHook.CARD_EXTENSION);
        assertThatThrownBy(() -> contribution.hooks().add(GalleryFrontendHook.DETAIL_ACTION))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("视图入口必须声明 href 与完整 i18n 展示元数据")
    void viewEntryRequiresTypedPresentation() {
        GalleryFrontendScope scope = new GalleryFrontendScope(
                Set.of("pixiv"), Set.of(), Set.of(GalleryKind.IMAGE), Set.of());

        assertThatThrownBy(() -> new GalleryFrontendContribution(
                "pixiv.view", "/pixiv-gallery/gallery-frontend.js", scope,
                Set.of(GalleryFrontendHook.VIEW_ENTRY), null,
                "gallery", "view.all", "images", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("viewHref");
        assertThatThrownBy(() -> new GalleryFrontendContribution(
                "pixiv.view", "/pixiv-gallery/gallery-frontend.js", scope,
                Set.of(GalleryFrontendHook.VIEW_ENTRY), "/pixiv-gallery.html?view=all",
                null, "view.all", "images", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("displayNamespace");
        assertThatThrownBy(() -> new GalleryFrontendContribution(
                "pixiv.view", "/pixiv-gallery/gallery-frontend.js", scope,
                Set.of(GalleryFrontendHook.VIEW_ENTRY), "/pixiv-gallery.html?view=all",
                "gallery", "view.all", "not an icon", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("iconToken");
    }

    @Test
    @DisplayName("非视图 hook 不得夹带未受约束的视图展示字段")
    void nonViewContributionRejectsViewPresentation() {
        assertThatThrownBy(() -> new GalleryFrontendContribution(
                "pixiv.card", "/pixiv-gallery/gallery-frontend.js", GalleryFrontendScope.any(),
                Set.of(GalleryFrontendHook.CARD_EXTENSION), "/pixiv-gallery.html",
                "gallery", "view.all", "images", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VIEW_ENTRY");
    }
}
