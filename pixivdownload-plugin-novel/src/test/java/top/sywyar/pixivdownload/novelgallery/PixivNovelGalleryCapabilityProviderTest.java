package top.sywyar.pixivdownload.novelgallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.gallery.model.GalleryItem;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.GalleryPage;
import top.sywyar.pixivdownload.core.gallery.model.GalleryWorkRef;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
import top.sywyar.pixivdownload.core.metadata.novel.NovelRecord;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.plugin.api.work.model.NovelWorkDetails;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkMetadata;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkMetadataRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Pixiv 小说中性画廊能力")
class PixivNovelGalleryCapabilityProviderTest {

    @Test
    @DisplayName("小说详情返回正文封面和内嵌图片的完整媒体集合")
    void returnsTextCoverAndEmbeddedImages() {
        PixivNovelGalleryDataProvider novelProvider = mock(PixivNovelGalleryDataProvider.class);
        WorkMetadataRepository metadata = mock(WorkMetadataRepository.class);
        NovelDatabase database = mock(NovelDatabase.class);
        NovelRecord record = mock(NovelRecord.class);
        when(record.rawContent()).thenReturn("[chapter:正文]");
        when(database.getNovel(123L)).thenReturn(record);
        when(metadata.find(WorkType.NOVEL, 123L)).thenReturn(Optional.of(meta()));
        PixivNovelGalleryCapabilityProvider provider =
                new PixivNovelGalleryCapabilityProvider(novelProvider, metadata, database);

        var work = provider.find(new GalleryWorkKey("pixiv", "novel", "123")).orElseThrow();

        assertThat(work.media()).extracting(asset -> asset.kind()).containsExactly(
                GalleryMediaKind.TEXT, GalleryMediaKind.COVER, GalleryMediaKind.IMAGE);
        assertThat(work.media().get(0).content()).isEqualTo("[chapter:正文]");
        assertThat(work.media()).extracting(asset -> asset.key().mediaId())
                .containsExactly("text", "cover", "embedded-img-a");
    }

    @Test
    @DisplayName("正式小说列表映射到共享 novel 作品身份")
    void mapsPrimaryNovelProjection() {
        PixivNovelGalleryDataProvider novelProvider = mock(PixivNovelGalleryDataProvider.class);
        WorkMetadataRepository metadata = mock(WorkMetadataRepository.class);
        NovelDatabase database = mock(NovelDatabase.class);
        when(novelProvider.query(org.mockito.ArgumentMatchers.any())).thenReturn(new GalleryPage(List.of(
                new GalleryItem(new GalleryWorkRef("pixiv-novel", "pixiv", GalleryKind.NOVEL, "123"),
                        "小说", "/cover", "/novel", Map.of())), 1, false, 0, 1, List.of()));
        when(metadata.findAll(WorkType.NOVEL, List.of(123L))).thenReturn(List.of(meta()));
        PixivNovelGalleryCapabilityProvider provider =
                new PixivNovelGalleryCapabilityProvider(novelProvider, metadata, database);

        var page = provider.page(new top.sywyar.pixivdownload.core.gallery.query.GalleryProjectionQuery(
                GalleryKind.NOVEL, "pixiv", List.of(), null, null, null, 1));

        assertThat(page.projections()).singleElement().satisfies(projection -> {
            assertThat(projection.key().workKey()).isEqualTo(new GalleryWorkKey("pixiv", "novel", "123"));
            assertThat(projection.containedMediaKinds())
                    .containsExactlyInAnyOrder(GalleryMediaKind.TEXT, GalleryMediaKind.COVER);
        });
    }

    private static WorkMetadata meta() {
        NovelWorkDetails novel = new NovelWorkDetails(1000, 2000, 300, 4, true,
                "ja", "jpg", List.of("img-a"), List.of());
        return new WorkMetadata(WorkType.NOVEL, 123L, "小说", "简介", 0, false,
                88L, "作者", null, null, null, List.of(), 1000L, 1, "txt", "/n/123",
                false, null, null, null, null, null, 2000L, true, novel);
    }
}
