package top.sywyar.pixivdownload.novelgallery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.plugin.api.work.model.PagedResult;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkSummary;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.work.query.WorkQuery;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("小说插件私有正文搜索编排")
class NovelOwnedWorkSearchTest {

    @Mock
    private WorkQueryService workQueryService;
    @Mock
    private NovelDatabase novelDatabase;

    private NovelOwnedWorkSearch search;

    @BeforeEach
    void setUp() {
        search = new NovelOwnedWorkSearch(workQueryService, novelDatabase);
    }

    @Test
    @DisplayName("正文命中在插件内求得并按宿主过滤排序结果分页")
    void shouldIntersectPrivateContentMatchesWithHostMetadataQuery() {
        WorkQuery query = WorkQuery.builder(WorkType.NOVEL)
                .page(1)
                .size(1)
                .searchType("content")
                .search("冒险旅程")
                .authorIds(List.of(88L))
                .build();
        when(novelDatabase.searchNovelContentIds("冒险旅程")).thenReturn(Set.of(2L, 4L));
        when(workQueryService.searchAll(any())).thenReturn(List.of(
                summary(4L), summary(3L), summary(2L), summary(1L)));

        PagedResult<WorkSummary> result = search.search(query);

        assertThat(result.content()).extracting(WorkSummary::workId).containsExactly(2L);
        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(2);
        ArgumentCaptor<WorkQuery> hostQuery = ArgumentCaptor.forClass(WorkQuery.class);
        verify(workQueryService).searchAll(hostQuery.capture());
        assertThat(hostQuery.getValue().search()).isNull();
        assertThat(hostQuery.getValue().searchType()).isEqualTo("all");
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

        assertThat(search.search(query)).isSameAs(expected);

        verifyNoInteractions(novelDatabase);
    }

    private static WorkSummary summary(long id) {
        return new WorkSummary(WorkType.NOVEL, id);
    }
}
