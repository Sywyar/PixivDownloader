package top.sywyar.pixivdownload.gallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.gallery.model.GalleryItem;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.GalleryPage;
import top.sywyar.pixivdownload.core.gallery.model.GalleryWorkRef;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
import top.sywyar.pixivdownload.core.gallery.query.GalleryProjectionQuery;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortDirection;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortField;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkMetadata;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkMetadataRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Pixiv 图片中性画廊能力")
class PixivImageGalleryCapabilityProviderTest {

    @Test
    @DisplayName("旧列表结果映射为 artwork 作品身份并保留游标")
    void mapsLegacyPageToNeutralProjection() {
        PixivImageGalleryDataProvider legacy = mock(PixivImageGalleryDataProvider.class);
        WorkMetadataRepository metadata = mock(WorkMetadataRepository.class);
        when(legacy.query(any())).thenReturn(new GalleryPage(List.of(new GalleryItem(
                new GalleryWorkRef("pixiv-image", "pixiv", GalleryKind.IMAGE, "123"),
                "标题", "/thumb", "/legacy", Map.of("downloadedAt", "1000"))),
                2, true, 0, 1, List.of()));
        when(metadata.findAll(WorkType.ARTWORK, List.of(123L))).thenReturn(List.of(meta()));
        PixivImageGalleryCapabilityProvider provider =
                new PixivImageGalleryCapabilityProvider(legacy, metadata);

        var page = provider.page(query());

        assertThat(page.nextCursor()).isEqualTo("1");
        assertThat(page.projections()).singleElement().satisfies(projection -> {
            assertThat(projection.key().workKey())
                    .isEqualTo(new GalleryWorkKey("pixiv", "artwork", "123"));
            assertThat(projection.containedMediaKinds()).containsExactly(GalleryMediaKind.UGOIRA);
        });
    }

    @Test
    @DisplayName("作品详情返回全部本地页并与投影共享身份")
    void returnsCompleteArtworkMedia() {
        PixivImageGalleryDataProvider legacy = mock(PixivImageGalleryDataProvider.class);
        WorkMetadataRepository metadata = mock(WorkMetadataRepository.class);
        when(metadata.find(WorkType.ARTWORK, 123L)).thenReturn(Optional.of(meta()));
        PixivImageGalleryCapabilityProvider provider =
                new PixivImageGalleryCapabilityProvider(legacy, metadata);

        var work = provider.find(new GalleryWorkKey("pixiv", "artwork", "123")).orElseThrow();

        assertThat(work.media()).hasSize(2);
        assertThat(work.media()).extracting(asset -> asset.key().mediaId())
                .containsExactly("page-0", "page-1");
        assertThat(work.media()).extracting(asset -> asset.kind())
                .containsOnly(GalleryMediaKind.UGOIRA);
    }

    private static GalleryProjectionQuery query() {
        return new GalleryProjectionQuery(GalleryKind.IMAGE, "pixiv", List.of(),
                GallerySortField.DOWNLOADED_AT, GallerySortDirection.DESC, null, 1);
    }

    private static WorkMetadata meta() {
        return new WorkMetadata(WorkType.ARTWORK, 123L, "标题", "简介", 0, null,
                88L, "作者", null, null, null, List.of(), 1000L, 2, "webp", "/p/123",
                false, null, null, 1L, "template", null, 2000L, null, null);
    }
}
