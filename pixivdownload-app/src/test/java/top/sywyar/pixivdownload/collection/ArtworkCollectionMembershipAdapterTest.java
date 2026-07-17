package top.sywyar.pixivdownload.collection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import top.sywyar.pixivdownload.i18n.LocalizedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ArtworkCollectionMembershipAdapter 收藏关系端口测试")
class ArtworkCollectionMembershipAdapterTest {

    private final CollectionService collectionService = mock(CollectionService.class);
    private final ArtworkCollectionMembershipAdapter adapter =
            new ArtworkCollectionMembershipAdapter(collectionService);

    @Test
    @DisplayName("应委托既有服务并保留是否新增关系的返回值")
    void delegatesAndPreservesWhetherRelationshipWasAdded() {
        when(collectionService.addArtwork(1L, 101L)).thenReturn(true);
        when(collectionService.addArtwork(1L, 102L)).thenReturn(false);

        assertThat(adapter.addArtwork(1L, 101L)).isTrue();
        assertThat(adapter.addArtwork(1L, 102L)).isFalse();

        verify(collectionService).addArtwork(1L, 101L);
        verify(collectionService).addArtwork(1L, 102L);
    }

    @Test
    @DisplayName("收藏夹不存在时应原样传播既有状态码与 i18n 语义")
    void propagatesMissingCollectionExceptionUnchanged() {
        LocalizedException missing = LocalizedException.badRequest(
                "collection.not-found",
                "收藏夹不存在: {0}",
                404L
        );
        when(collectionService.addArtwork(404L, 101L)).thenThrow(missing);

        Throwable thrown = catchThrowable(() -> adapter.addArtwork(404L, 101L));

        assertThat(thrown).isSameAs(missing);
        assertThat(missing.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getMessageCode()).isEqualTo("collection.not-found");
        assertThat(missing.getMessageArgs()).containsExactly(404L);
        verify(collectionService).addArtwork(404L, 101L);
    }
}
