package top.sywyar.pixivdownload.setup.guest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRow;
import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityDeniedException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("作品可见性领域服务")
class GuestWorkVisibilityServiceTest {

    private PixivDatabase pixivDatabase;
    private NovelMetadataRepository novelMetadataRepository;
    private GuestWorkVisibilityService service;

    @BeforeEach
    void setUp() {
        pixivDatabase = mock(PixivDatabase.class);
        novelMetadataRepository = mock(NovelMetadataRepository.class);
        service = new GuestWorkVisibilityService(pixivDatabase, novelMetadataRepository);
    }

    @Test
    @DisplayName("无限制作用域直接放行且不读取任何作品数据")
    void unrestrictedScopeDoesNotReadWorkData() {
        WorkVisibilityScope scope = WorkVisibilityScope.unrestricted();

        assertThat(service.isVisible(scope, WorkType.ARTWORK, 42L)).isTrue();
        assertThat(service.isVisible(scope, WorkType.NOVEL, 43L)).isTrue();
        service.requireVisible(scope, WorkType.ARTWORK, 42L);

        verifyNoInteractions(pixivDatabase, novelMetadataRepository);
    }

    @Test
    @DisplayName("受限作用域中作品不存在时判定不可见并抛出纯领域异常")
    void missingWorkIsDenied() {
        WorkVisibilityScope scope = restricted(Set.of(0), true, List.of(), true, List.of());

        assertThat(service.isVisible(scope, WorkType.ARTWORK, 42L)).isFalse();
        assertThatThrownBy(() -> service.requireVisible(scope, WorkType.NOVEL, 43L))
                .isInstanceOfSatisfying(WorkVisibilityDeniedException.class, exception -> {
                    assertThat(exception.workType()).isEqualTo(WorkType.NOVEL);
                    assertThat(exception.workId()).isEqualTo(43L);
                });
    }

    @ParameterizedTest(name = "小说分级 {0}，允许分级 {1} => {2}")
    @CsvSource({
            "0, 0, true",
            "0, 1, false",
            "1, 0, false",
            "1, 1, true",
            "2, 2, true",
            "7, 1, true",
            "7, 0, false"
    })
    @DisplayName("小说年龄分级严格匹配且未知值按 R18 处理")
    void novelRatingMatchesRestriction(int rating, int allowed, boolean expected) {
        when(novelMetadataRepository.getNovel(42L)).thenReturn(novelRecord(rating, 7L));
        when(novelMetadataRepository.getNovelTags(42L)).thenReturn(List.of());
        WorkVisibilityScope scope = restricted(Set.of(allowed), true, List.of(), true, List.of());

        assertThat(service.isVisible(scope, WorkType.NOVEL, 42L)).isEqualTo(expected);
    }

    @Test
    @DisplayName("插画标签和作者先排除不可见维度，再按 OR 白名单判定")
    void artworkWhitelistPreservesExclusionAndOrSemantics() {
        when(pixivDatabase.getArtwork(42L)).thenReturn(artworkRecord(0, 7L));
        when(pixivDatabase.getArtworkTags(42L)).thenReturn(List.of(new TagDto(11L, "tag", null)));

        WorkVisibilityScope visible = restricted(Set.of(0), false, List.of(11L), true, List.of());
        WorkVisibilityScope excluded = restricted(Set.of(0), false, List.of(12L), true, List.of());

        assertThat(service.isVisible(visible, WorkType.ARTWORK, 42L)).isTrue();
        assertThat(service.isVisible(excluded, WorkType.ARTWORK, 42L)).isFalse();
    }

    @Test
    @DisplayName("标签与作者均受限时任一维度出现未列项都会优先排除")
    void restrictedDimensionsExcludeBeforeCrossDimensionOr() {
        when(pixivDatabase.getArtwork(42L)).thenReturn(artworkRecord(0, 7L));
        when(pixivDatabase.getArtworkTags(42L)).thenReturn(List.of(new TagDto(11L, "tag", null)));

        WorkVisibilityScope bothHit = restricted(Set.of(0), false, List.of(11L), false, List.of(7L));
        WorkVisibilityScope authorExcluded = restricted(Set.of(0), false, List.of(11L), false, List.of(8L));
        WorkVisibilityScope tagExcluded = restricted(Set.of(0), false, List.of(12L), false, List.of(7L));

        assertThat(service.isVisible(bothHit, WorkType.ARTWORK, 42L)).isTrue();
        assertThat(service.isVisible(authorExcluded, WorkType.ARTWORK, 42L)).isFalse();
        assertThat(service.isVisible(tagExcluded, WorkType.ARTWORK, 42L)).isFalse();
    }

    @Test
    @DisplayName("小说使用独立的标签作者限制而不串用插画限制")
    void novelUsesItsOwnRestriction() {
        when(novelMetadataRepository.getNovel(42L)).thenReturn(novelRecord(0, 33L));
        when(novelMetadataRepository.getNovelTags(42L)).thenReturn(List.of());
        WorkRestriction artwork = restriction(Set.of(0), false, List.of(11L), false, List.of(22L));
        WorkRestriction novel = restriction(Set.of(0), false, List.of(), false, List.of(33L));
        WorkVisibilityScope scope = WorkVisibilityScope.restricted(artwork, novel);

        assertThat(service.isVisible(scope, WorkType.NOVEL, 42L)).isTrue();
    }

    private static WorkVisibilityScope restricted(
            Set<Integer> ratings,
            boolean tagUnrestricted,
            List<Long> tagIds,
            boolean authorUnrestricted,
            List<Long> authorIds) {
        WorkRestriction restriction = restriction(
                ratings, tagUnrestricted, tagIds, authorUnrestricted, authorIds);
        return WorkVisibilityScope.restricted(restriction, restriction);
    }

    private static WorkRestriction restriction(
            Set<Integer> ratings,
            boolean tagUnrestricted,
            List<Long> tagIds,
            boolean authorUnrestricted,
            List<Long> authorIds) {
        return new WorkRestriction(ratings, tagUnrestricted, tagIds, authorUnrestricted, authorIds);
    }

    private static ArtworkRecord artworkRecord(int xRestrict, Long authorId) {
        return new ArtworkRecord(
                42L, "title", "folder", 1, "jpg", 1L,
                false, null, null, xRestrict, false, authorId, null);
    }

    private static NovelMetadataRow novelRecord(int xRestrict, Long authorId) {
        return new NovelMetadataRow(
                42L, "title", "folder", 1, "txt", 1L,
                xRestrict, false, authorId, "", 1L, null,
                null, null, 10, true, null);
    }
}
