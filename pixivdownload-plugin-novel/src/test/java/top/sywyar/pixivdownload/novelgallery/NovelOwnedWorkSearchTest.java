package top.sywyar.pixivdownload.novelgallery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.metadata.NovelWorkDetailsRepository;
import top.sywyar.pixivdownload.core.work.model.PagedResult;
import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.core.work.model.WorkSummary;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.query.WorkQuery;
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("小说插件私有查询编排")
class NovelOwnedWorkSearchTest {

    @Mock
    private WorkQueryService workQueryService;
    @Mock
    private NovelDatabase novelDatabase;
    @Mock
    private NovelWorkDetailsRepository novelWorkDetailsRepository;

    private NovelOwnedWorkSearch search;

    @BeforeEach
    void setUp() {
        search = new NovelOwnedWorkSearch(
                workQueryService, novelDatabase, novelWorkDetailsRepository);
    }

    @Test
    @DisplayName("正文命中在插件内求得并按宿主过滤排序结果分页")
    void shouldIntersectPrivateContentMatchesWithHostMetadataQuery() {
        WorkQuery query = WorkQuery.builder(WorkType.NOVEL)
                .page(1)
                .size(1)
                .authorIds(List.of(88L))
                .build();
        when(novelDatabase.searchNovelContentIds("冒险旅程")).thenReturn(Set.of(2L, 4L));
        when(workQueryService.searchAll(any())).thenReturn(List.of(
                summary(4L), summary(3L), summary(2L), summary(1L)));

        PagedResult<WorkSummary> result = search.search(query, "冒险旅程");

        assertThat(result.content()).extracting(WorkSummary::workId).containsExactly(2L);
        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(2);
        ArgumentCaptor<WorkQuery> hostQuery = ArgumentCaptor.forClass(WorkQuery.class);
        verify(workQueryService).searchAll(hostQuery.capture());
        assertThat(hostQuery.getValue()).isSameAs(query);
        assertThat(hostQuery.getValue().authorIds()).containsExactly(88L);
    }

    @Test
    @DisplayName("非正文搜索直接委托宿主通用查询且不读取小说正文")
    void shouldDelegateNonContentSearch() {
        WorkQuery query = WorkQuery.builder(WorkType.NOVEL)
                .searchType("title")
                .search("标题")
                .build();
        PagedResult<WorkSummary> expected = new PagedResult<>(List.of(summary(7L)), 1, 0, 24, 1);
        when(workQueryService.search(query)).thenReturn(expected);

        assertThat(search.search(query, null)).isSameAs(expected);

        verifyNoInteractions(novelDatabase, novelWorkDetailsRepository);
    }

    @Test
    @DisplayName("字数升序在插件内将空值视为零并保持时间顺序后分页")
    void shouldSortWordCountAscendingAndPageWithStableTimeOrder() {
        WorkRestriction restriction = new WorkRestriction(
                Set.of(0), true, List.of(21L), false, List.of(34L));
        WorkQuery query = WorkQuery.builder(WorkType.NOVEL)
                .page(0)
                .size(2)
                .sort("wordCount")
                .order("asc")
                .search("标题")
                .searchType("title")
                .r18("yes")
                .ai("no")
                .formats(List.of("txt"))
                .collectionIds(List.of(11L))
                .tagIds(List.of(12L))
                .excludedTagIds(List.of(13L))
                .optionalTagIds(List.of(14L))
                .authorIds(List.of(88L))
                .excludedAuthorIds(List.of(89L))
                .optionalAuthorIds(List.of(90L))
                .seriesIds(List.of(15L))
                .excludedSeriesIds(List.of(16L))
                .restriction(restriction)
                .build();
        when(workQueryService.searchAll(any())).thenReturn(List.of(
                summary(4L), summary(3L), summary(2L), summary(1L)));
        Map<Long, Integer> wordCounts = new LinkedHashMap<>();
        wordCounts.put(4L, 0);
        wordCounts.put(3L, null);
        wordCounts.put(2L, 100);
        wordCounts.put(1L, 200);
        when(novelWorkDetailsRepository.findWordCounts(List.of(4L, 3L, 2L, 1L)))
                .thenReturn(wordCounts);

        PagedResult<WorkSummary> result = search.search(query, null);

        assertThat(result.content()).extracting(WorkSummary::workId).containsExactly(4L, 3L);
        assertThat(result.totalElements()).isEqualTo(4);
        assertThat(result.page()).isZero();
        assertThat(result.totalPages()).isEqualTo(2);
        ArgumentCaptor<WorkQuery> hostQuery = ArgumentCaptor.forClass(WorkQuery.class);
        verify(workQueryService).searchAll(hostQuery.capture());
        assertThat(hostQuery.getValue()).isEqualTo(new WorkQuery(
                WorkType.NOVEL, 0, 2, "date", "desc", "标题", "title", "yes", "no",
                List.of("txt"), List.of(11L), List.of(12L), List.of(13L), List.of(14L),
                List.of(88L), List.of(89L), List.of(90L), List.of(15L), List.of(16L), restriction));
        verifyNoInteractions(novelDatabase);
    }

    @Test
    @DisplayName("正文与访客限制先求交，再按字数倒序且同字数保持时间倒序")
    void shouldCombineContentRestrictionAndDescendingWordCountSort() {
        WorkRestriction restriction = new WorkRestriction(
                Set.of(0, 1), false, List.of(21L), true, List.of());
        WorkQuery query = WorkQuery.builder(WorkType.NOVEL)
                .sort("wordCount")
                .order("desc")
                .restriction(restriction)
                .build();
        when(novelDatabase.searchNovelContentIds("冒险旅程")).thenReturn(Set.of(4L, 2L, 1L));
        when(workQueryService.searchAll(any())).thenReturn(List.of(
                summary(4L), summary(3L), summary(2L), summary(1L)));
        Map<Long, Integer> wordCounts = new LinkedHashMap<>();
        wordCounts.put(4L, null);
        wordCounts.put(2L, 100);
        wordCounts.put(1L, 100);
        when(novelWorkDetailsRepository.findWordCounts(List.of(4L, 2L, 1L)))
                .thenReturn(wordCounts);

        List<WorkSummary> result = search.searchAll(query, "冒险旅程");

        assertThat(result).extracting(WorkSummary::workId).containsExactly(2L, 1L, 4L);
        ArgumentCaptor<WorkQuery> hostQuery = ArgumentCaptor.forClass(WorkQuery.class);
        verify(workQueryService).searchAll(hostQuery.capture());
        assertThat(hostQuery.getValue().sort()).isEqualTo("date");
        assertThat(hostQuery.getValue().order()).isEqualTo("desc");
        assertThat(hostQuery.getValue().restriction()).isSameAs(restriction);
    }

    @Test
    @DisplayName("正文无命中时在插件内短路且不访问宿主查询或字数详情")
    void shouldShortCircuitWhenPrivateContentHasNoMatches() {
        WorkQuery query = WorkQuery.builder(WorkType.NOVEL)
                .sort("wordCount")
                .build();
        when(novelDatabase.searchNovelContentIds("无命中")).thenReturn(Set.of());

        assertThat(search.searchAll(query, "无命中")).isEmpty();

        verifyNoInteractions(workQueryService, novelWorkDetailsRepository);
    }

    private static WorkSummary summary(long id) {
        return new WorkSummary(WorkType.NOVEL, id);
    }
}
