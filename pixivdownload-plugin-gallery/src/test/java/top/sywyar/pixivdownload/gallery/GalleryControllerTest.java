package top.sywyar.pixivdownload.gallery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.gallery.web.GalleryPageResponse;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkRestriction;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.work.query.WorkQuery;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkVisibilityService;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("GalleryController 单元测试")
class GalleryControllerTest {

    private MockMvc mockMvc;

    @Mock
    private GalleryService galleryService;
    @Mock
    private GalleryBatchService galleryBatchService;
    @Mock
    private WorkVisibilityService workVisibilityService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new GalleryController(galleryService, galleryBatchService, workVisibilityService)
        ).build();
    }

    @Test
    @DisplayName("正向与排除筛选参数映射到稳定作品查询，并透传访客限制纯值")
    void shouldMapPositiveAndNegativeFilterParams() throws Exception {
        WorkRestriction restriction = new WorkRestriction(
                Set.of(0), false, List.of(11L), true, List.of());
        when(workVisibilityService.restrictionFrom(any(HttpServletRequest.class), eq(WorkType.ARTWORK)))
                .thenReturn(restriction);
        when(galleryService.query(any()))
                .thenReturn(new GalleryPageResponse(List.of(), 0, 0, 24, 0));

        mockMvc.perform(get("/api/gallery/artworks")
                        .param("tagIds", "11,12")
                        .param("notTagIds", "13")
                        .param("orTagIds", "14,15")
                        .param("authorIds", "21")
                        .param("notAuthorIds", "31")
                        .param("orAuthorIds", "41,42")
                        .param("authorId", "22"))
                .andExpect(status().isOk());

        ArgumentCaptor<WorkQuery> captor = ArgumentCaptor.forClass(WorkQuery.class);
        verify(galleryService).query(captor.capture());
        verify(workVisibilityService).restrictionFrom(any(HttpServletRequest.class), eq(WorkType.ARTWORK));

        WorkQuery query = captor.getValue();
        assertThat(query.tagIds()).containsExactly(11L, 12L);
        assertThat(query.excludedTagIds()).containsExactly(13L);
        assertThat(query.optionalTagIds()).containsExactly(14L, 15L);
        assertThat(query.authorIds()).containsExactly(21L, 22L);
        assertThat(query.excludedAuthorIds()).containsExactly(31L);
        assertThat(query.optionalAuthorIds()).containsExactly(41L, 42L);
        assertThat(query.restriction()).isSameAs(restriction);
    }
}
