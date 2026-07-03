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
import top.sywyar.pixivdownload.core.metadata.artwork.GalleryQuery;
import top.sywyar.pixivdownload.core.download.response.PagedHistoryResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("GalleryController tests")
class GalleryControllerTest {

    private MockMvc mockMvc;

    @Mock
    private GalleryService galleryService;
    @Mock
    private GalleryBatchService galleryBatchService;
    @Mock
    private top.sywyar.pixivdownload.setup.guest.GuestAccessGuard guestAccessGuard;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new GalleryController(galleryService, galleryBatchService, guestAccessGuard)
        ).build();
    }

    @Test
    @DisplayName("should map positive and negative filter params into GalleryQuery")
    void shouldMapPositiveAndNegativeFilterParams() throws Exception {
        when(galleryService.query(any())).thenReturn(new PagedHistoryResponse(List.of(), 0, 0, 24, 0));

        mockMvc.perform(get("/api/gallery/artworks")
                        .param("tagIds", "11,12")
                        .param("notTagIds", "13")
                        .param("orTagIds", "14,15")
                        .param("authorIds", "21")
                        .param("notAuthorIds", "31")
                        .param("orAuthorIds", "41,42")
                        .param("authorId", "22"))
                .andExpect(status().isOk());

        ArgumentCaptor<GalleryQuery> captor = ArgumentCaptor.forClass(GalleryQuery.class);
        verify(galleryService).query(captor.capture());

        GalleryQuery query = captor.getValue();
        assertThat(query.getTagIds()).containsExactly(11L, 12L);
        assertThat(query.getExcludedTagIds()).containsExactly(13L);
        assertThat(query.getOptionalTagIds()).containsExactly(14L, 15L);
        assertThat(query.getAuthorIds()).containsExactly(21L, 22L);
        assertThat(query.getExcludedAuthorIds()).containsExactly(31L);
        assertThat(query.getOptionalAuthorIds()).containsExactly(41L, 42L);
    }
}
