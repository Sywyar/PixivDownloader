package top.sywyar.pixivdownload.core.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.db.NovelSeries;
import top.sywyar.pixivdownload.plugin.api.WorkMetadata;
import top.sywyar.pixivdownload.plugin.api.WorkTag;
import top.sywyar.pixivdownload.plugin.api.WorkType;
import top.sywyar.pixivdownload.series.MangaSeries;
import top.sywyar.pixivdownload.series.MangaSeriesService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("CoreWorkMetadataRepository 单元测试")
class CoreWorkMetadataRepositoryTest {

    private final PixivDatabase pixivDatabase = mock(PixivDatabase.class);
    private final NovelDatabase novelDatabase = mock(NovelDatabase.class);
    private final AuthorService authorService = mock(AuthorService.class);
    private final MangaSeriesService mangaSeriesService = mock(MangaSeriesService.class);

    private final CoreWorkMetadataRepository repository = new CoreWorkMetadataRepository(
            pixivDatabase, novelDatabase, authorService, mangaSeriesService);

    private static ArtworkRecord artwork(long id, Long authorId, Long seriesId, Long fileName, boolean deleted) {
        return new ArtworkRecord(id, "标题" + id, "/folder/" + id, 3, "jpg", 1700L + id,
                true, "/moved/" + id, 1800L, 1, true, authorId, "简介" + id,
                fileName, 9L, seriesId, 2L, deleted);
    }

    @Nested
    @DisplayName("find（插画）")
    class FindArtworkTests {

        @Test
        @DisplayName("应补全作者名、系列标题、标签与文件名模板，字段与下载记录逐字段一致")
        void shouldHydrateAllFields() {
            when(pixivDatabase.getArtworks(anyCollection())).thenReturn(List.of(
                    artwork(7L, 88L, 600L, 5L, false)));
            when(authorService.getAuthorNames(anyCollection())).thenReturn(Map.of(88L, "作者甲"));
            when(mangaSeriesService.getSeriesByIds(anyCollection())).thenReturn(List.of(
                    new MangaSeries(600L, "系列标题", 88L, 0L, null, null, null)));
            when(pixivDatabase.getArtworkTags(anyCollection())).thenReturn(Map.of(
                    7L, List.of(new TagDto(11L, "魔法", "magic"))));
            when(pixivDatabase.getFileNameTemplates(anyCollection())).thenReturn(Map.of(
                    5L, "{artwork_title}_p{page}"));

            Optional<WorkMetadata> found = repository.find(WorkType.ARTWORK, 7L);

            assertThat(found).isPresent();
            WorkMetadata meta = found.get();
            assertThat(meta.workType()).isEqualTo(WorkType.ARTWORK);
            assertThat(meta.workId()).isEqualTo(7L);
            assertThat(meta.title()).isEqualTo("标题7");
            assertThat(meta.description()).isEqualTo("简介7");
            assertThat(meta.xRestrict()).isEqualTo(1);
            assertThat(meta.isAi()).isTrue();
            assertThat(meta.authorId()).isEqualTo(88L);
            assertThat(meta.authorName()).isEqualTo("作者甲");
            assertThat(meta.seriesId()).isEqualTo(600L);
            assertThat(meta.seriesOrder()).isEqualTo(2L);
            assertThat(meta.seriesTitle()).isEqualTo("系列标题");
            assertThat(meta.tags()).containsExactly(new WorkTag(11L, "魔法", "magic"));
            assertThat(meta.downloadTime()).isEqualTo(1707L);
            assertThat(meta.pageCount()).isEqualTo(3);
            assertThat(meta.extensions()).isEqualTo("jpg");
            assertThat(meta.folder()).isEqualTo("/folder/7");
            assertThat(meta.moved()).isTrue();
            assertThat(meta.moveFolder()).isEqualTo("/moved/7");
            assertThat(meta.moveTime()).isEqualTo(1800L);
            assertThat(meta.fileNameTemplateId()).isEqualTo(5L);
            assertThat(meta.fileNameTemplate()).isEqualTo("{artwork_title}_p{page}");
            assertThat(meta.fileAuthorNameId()).isEqualTo(9L);
        }

        @Test
        @DisplayName("软删除行视为不存在，返回 empty")
        void shouldReturnEmptyForSoftDeletedArtwork() {
            when(pixivDatabase.getArtworks(anyCollection())).thenReturn(List.of(
                    artwork(7L, null, null, null, true)));

            assertThat(repository.find(WorkType.ARTWORK, 7L)).isEmpty();
        }

        @Test
        @DisplayName("无记录返回 empty")
        void shouldReturnEmptyForMissingArtwork() {
            when(pixivDatabase.getArtworks(anyCollection())).thenReturn(List.of());

            assertThat(repository.find(WorkType.ARTWORK, 404L)).isEmpty();
        }

        @Test
        @DisplayName("模板 id 缺省时按默认模板 1 解析内容，fileNameTemplateId 字段保持 null")
        void shouldFallBackToDefaultTemplateWhenTemplateIdIsNull() {
            when(pixivDatabase.getArtworks(anyCollection())).thenReturn(List.of(
                    artwork(7L, null, null, null, false)));
            when(authorService.getAuthorNames(anyCollection())).thenReturn(Map.of());
            when(pixivDatabase.getArtworkTags(anyCollection())).thenReturn(Map.of());
            when(pixivDatabase.getFileNameTemplates(anyCollection())).thenReturn(Map.of(1L, "默认模板"));

            Optional<WorkMetadata> found = repository.find(WorkType.ARTWORK, 7L);

            assertThat(found).isPresent();
            assertThat(found.get().fileNameTemplateId()).isNull();
            assertThat(found.get().fileNameTemplate()).isEqualTo("默认模板");
            verify(pixivDatabase).getFileNameTemplates(Set.of(1L));
        }
    }

    @Nested
    @DisplayName("findAll（插画）")
    class FindAllArtworkTests {

        @Test
        @DisplayName("返回顺序必须与传入 id 顺序一致，软删除行与未知 id 直接跳过")
        void shouldPreserveInputOrderAndSkipDeletedOrMissing() {
            when(pixivDatabase.getArtworks(anyCollection())).thenReturn(List.of(
                    artwork(1L, null, null, 5L, false),
                    artwork(2L, null, null, 5L, true),
                    artwork(3L, null, null, 5L, false)));
            when(authorService.getAuthorNames(anyCollection())).thenReturn(Map.of());
            when(pixivDatabase.getArtworkTags(anyCollection())).thenReturn(Map.of());
            when(pixivDatabase.getFileNameTemplates(anyCollection())).thenReturn(Map.of());

            List<WorkMetadata> out = repository.findAll(WorkType.ARTWORK, List.of(3L, 4L, 1L, 2L));

            assertThat(out).extracting(WorkMetadata::workId).containsExactly(3L, 1L);
        }

        @Test
        @DisplayName("行读取与各关联补全各发一次批量查询，绝不退化为每 id 一查")
        void shouldHydrateWithSingleBatchQueries() {
            when(pixivDatabase.getArtworks(anyCollection())).thenReturn(List.of(
                    artwork(1L, 88L, 600L, 5L, false),
                    artwork(2L, 99L, 601L, 6L, false)));
            when(authorService.getAuthorNames(anyCollection())).thenReturn(Map.of());
            when(mangaSeriesService.getSeriesByIds(anyCollection())).thenReturn(List.of());
            when(pixivDatabase.getArtworkTags(anyCollection())).thenReturn(Map.of());
            when(pixivDatabase.getFileNameTemplates(anyCollection())).thenReturn(Map.of());

            repository.findAll(WorkType.ARTWORK, List.of(1L, 2L));

            verify(pixivDatabase, times(1)).getArtworks(anyCollection());
            verify(pixivDatabase, never()).getArtwork(anyLong());
            verify(authorService, times(1)).getAuthorNames(anyCollection());
            verify(mangaSeriesService, times(1)).getSeriesByIds(anyCollection());
            verify(pixivDatabase, times(1)).getArtworkTags(anyCollection());
            verify(pixivDatabase, never()).getArtworkTags(anyLong());
            verify(pixivDatabase, times(1)).getFileNameTemplates(anyCollection());
            verify(pixivDatabase, never()).getFileNameTemplate(anyLong());
        }

        @Test
        @DisplayName("入参为空时返回空列表且不发任何查询")
        void shouldReturnEmptyWithoutQueriesForEmptyInput() {
            assertThat(repository.findAll(WorkType.ARTWORK, List.of())).isEmpty();
            assertThat(repository.findAll(WorkType.ARTWORK, null)).isEmpty();

            verifyNoInteractions(pixivDatabase, authorService, mangaSeriesService);
        }
    }

    @Nested
    @DisplayName("小说侧")
    class NovelTests {

        private NovelRecord novel(long id, boolean deleted) {
            return new NovelRecord(id, "小说标题" + id, "/novels/" + id, 2, "", 1900L + id, 1, false, 88L,
                    "小说简介" + id, 5L, 9L, 700L, 3L, 1000, 2000, 300, 4, true, "ja", "正文", "jpg", deleted);
        }

        @Test
        @DisplayName("find 应补全作者名、系列标题、标签、模板与小说专属块，小说无移动语义")
        void shouldHydrateNovelMetadata() {
            when(novelDatabase.getNovels(anyCollection())).thenReturn(List.of(novel(42L, false)));
            when(novelDatabase.getNovelTagsBatch(anyCollection())).thenReturn(Map.of(
                    42L, List.of(new TagDto(21L, "ファンタジー", "奇幻"))));
            when(novelDatabase.getSeriesByIds(anyCollection())).thenReturn(List.of(
                    new NovelSeries(700L, "小说系列", 88L, 0L, null, null, null)));
            when(novelDatabase.getNovelImageIdsBatch(anyCollection())).thenReturn(Map.of(
                    42L, List.of("img-a", "img-b")));
            when(novelDatabase.getTranslationLangsBatch(anyCollection())).thenReturn(Map.of(
                    42L, List.of("zh-CN")));
            when(authorService.getAuthorNames(anyCollection())).thenReturn(Map.of(88L, "作者乙"));
            when(pixivDatabase.getFileNameTemplates(anyCollection())).thenReturn(Map.of(
                    5L, "{novel_title}"));

            Optional<WorkMetadata> found = repository.find(WorkType.NOVEL, 42L);

            assertThat(found).isPresent();
            WorkMetadata meta = found.get();
            assertThat(meta.workType()).isEqualTo(WorkType.NOVEL);
            assertThat(meta.workId()).isEqualTo(42L);
            assertThat(meta.title()).isEqualTo("小说标题42");
            assertThat(meta.description()).isEqualTo("小说简介42");
            assertThat(meta.xRestrict()).isEqualTo(1);
            assertThat(meta.isAi()).isFalse();
            assertThat(meta.authorId()).isEqualTo(88L);
            assertThat(meta.authorName()).isEqualTo("作者乙");
            assertThat(meta.seriesId()).isEqualTo(700L);
            assertThat(meta.seriesOrder()).isEqualTo(3L);
            assertThat(meta.seriesTitle()).isEqualTo("小说系列");
            assertThat(meta.tags()).containsExactly(new WorkTag(21L, "ファンタジー", "奇幻"));
            assertThat(meta.downloadTime()).isEqualTo(1942L);
            assertThat(meta.pageCount()).isEqualTo(2);
            assertThat(meta.folder()).isEqualTo("/novels/42");
            assertThat(meta.moved()).isFalse();
            assertThat(meta.moveFolder()).isNull();
            assertThat(meta.moveTime()).isNull();
            assertThat(meta.fileNameTemplateId()).isEqualTo(5L);
            assertThat(meta.fileNameTemplate()).isEqualTo("{novel_title}");
            assertThat(meta.fileAuthorNameId()).isEqualTo(9L);
            assertThat(meta.novel()).isNotNull();
            assertThat(meta.novel().wordCount()).isEqualTo(1000);
            assertThat(meta.novel().textLength()).isEqualTo(2000);
            assertThat(meta.novel().readingTimeSeconds()).isEqualTo(300);
            assertThat(meta.novel().pageCount()).isEqualTo(4);
            assertThat(meta.novel().isOriginal()).isTrue();
            assertThat(meta.novel().xLanguage()).isEqualTo("ja");
            assertThat(meta.novel().coverExt()).isEqualTo("jpg");
            assertThat(meta.novel().embeddedImageIds()).containsExactly("img-a", "img-b");
            assertThat(meta.novel().translatedLanguages()).containsExactly("zh-CN");
        }

        @Test
        @DisplayName("find 对软删除小说返回 empty")
        void shouldReturnEmptyForSoftDeletedNovel() {
            when(novelDatabase.getNovels(anyCollection())).thenReturn(List.of(novel(42L, true)));

            assertThat(repository.find(WorkType.NOVEL, 42L)).isEmpty();
        }

        @Test
        @DisplayName("findAll 返回顺序必须与传入 id 顺序一致，软删除行与未知 id 直接跳过")
        void shouldPreserveInputOrderAndSkipDeletedOrMissing() {
            when(novelDatabase.getNovels(anyCollection())).thenReturn(List.of(
                    novel(1L, false), novel(2L, true), novel(3L, false)));
            when(novelDatabase.getNovelTagsBatch(anyCollection())).thenReturn(Map.of());
            when(novelDatabase.getSeriesByIds(anyCollection())).thenReturn(List.of());
            when(novelDatabase.getNovelImageIdsBatch(anyCollection())).thenReturn(Map.of());
            when(novelDatabase.getTranslationLangsBatch(anyCollection())).thenReturn(Map.of());
            when(authorService.getAuthorNames(anyCollection())).thenReturn(Map.of());
            when(pixivDatabase.getFileNameTemplates(anyCollection())).thenReturn(Map.of());

            List<WorkMetadata> out = repository.findAll(WorkType.NOVEL, List.of(3L, 4L, 1L, 2L));

            assertThat(out).extracting(WorkMetadata::workId).containsExactly(3L, 1L);
        }

        @Test
        @DisplayName("findAll 行读取与各关联补全各发一次批量查询，绝不退化为每 id 一查")
        void shouldHydrateWithSingleBatchQueries() {
            when(novelDatabase.getNovels(anyCollection())).thenReturn(List.of(
                    novel(1L, false), novel(2L, false)));
            when(novelDatabase.getNovelTagsBatch(anyCollection())).thenReturn(Map.of());
            when(novelDatabase.getSeriesByIds(anyCollection())).thenReturn(List.of());
            when(novelDatabase.getNovelImageIdsBatch(anyCollection())).thenReturn(Map.of());
            when(novelDatabase.getTranslationLangsBatch(anyCollection())).thenReturn(Map.of());
            when(authorService.getAuthorNames(anyCollection())).thenReturn(Map.of());
            when(pixivDatabase.getFileNameTemplates(anyCollection())).thenReturn(Map.of());

            repository.findAll(WorkType.NOVEL, List.of(1L, 2L));

            verify(novelDatabase, times(1)).getNovels(anyCollection());
            verify(novelDatabase, never()).getNovel(anyLong());
            verify(novelDatabase, times(1)).getNovelTagsBatch(anyCollection());
            verify(novelDatabase, never()).getNovelTags(anyLong());
            verify(novelDatabase, times(1)).getSeriesByIds(anyCollection());
            verify(novelDatabase, never()).getSeries(anyLong());
            verify(novelDatabase, times(1)).getNovelImageIdsBatch(anyCollection());
            verify(novelDatabase, never()).getNovelImageIds(anyLong());
            verify(novelDatabase, times(1)).getTranslationLangsBatch(anyCollection());
            verify(novelDatabase, never()).getTranslationLangs(anyLong());
            verify(authorService, times(1)).getAuthorNames(anyCollection());
            verify(pixivDatabase, times(1)).getFileNameTemplates(anyCollection());
            verify(pixivDatabase, never()).getFileNameTemplate(anyLong());
        }

        @Test
        @DisplayName("模板 id 为空时不查模板池（小说侧无「缺省取默认模板 1」规则）")
        void shouldSkipTemplateLookupWhenTemplateIdMissing() {
            NovelRecord noTemplate = new NovelRecord(9L, "无模板", "/novels/9", 1, "", 1900L, 0, false,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    "正文", null, false);
            when(novelDatabase.getNovels(anyCollection())).thenReturn(List.of(noTemplate));
            when(novelDatabase.getNovelTagsBatch(anyCollection())).thenReturn(Map.of());
            when(novelDatabase.getNovelImageIdsBatch(anyCollection())).thenReturn(Map.of());
            when(novelDatabase.getTranslationLangsBatch(anyCollection())).thenReturn(Map.of());
            when(authorService.getAuthorNames(anyCollection())).thenReturn(Map.of());

            Optional<WorkMetadata> found = repository.find(WorkType.NOVEL, 9L);

            assertThat(found).isPresent();
            assertThat(found.get().fileNameTemplateId()).isNull();
            assertThat(found.get().fileNameTemplate()).isNull();
            verify(pixivDatabase, never()).getFileNameTemplates(anyCollection());
        }
    }
}
