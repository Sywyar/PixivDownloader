package top.sywyar.pixivdownload.collection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.i18n.LocalizedException;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CoreCollectionAdapter 核心收藏端口测试")
class CoreCollectionAdapterTest {

    private final CollectionService collectionService = mock(CollectionService.class);
    private final CoreCollectionAdapter adapter = new CoreCollectionAdapter(collectionService);

    @Test
    @DisplayName("应按作品类型委托收藏关系并保留是否新增的返回值")
    void delegatesMembershipByWorkType() {
        when(collectionService.addArtwork(1L, 101L)).thenReturn(true);
        when(collectionService.addNovel(1L, 202L)).thenReturn(false);

        assertThat(adapter.addWork(WorkType.ARTWORK, 1L, 101L)).isTrue();
        assertThat(adapter.addWork(WorkType.NOVEL, 1L, 202L)).isFalse();

        verify(collectionService).addArtwork(1L, 101L);
        verify(collectionService).addNovel(1L, 202L);
    }

    @Test
    @DisplayName("应原样委托收藏夹下载根解析")
    void delegatesDownloadRootResolution() {
        Path fallback = Path.of("downloads");
        Path resolved = Path.of("collections", "one");
        when(collectionService.resolveDownloadRoot(1L, fallback)).thenReturn(resolved);

        assertThat(adapter.resolveDownloadRoot(1L, fallback)).isSameAs(resolved);
        verify(collectionService).resolveDownloadRoot(1L, fallback);
    }

    @Test
    @DisplayName("收藏夹不存在时应原样传播既有状态码与 i18n 语义")
    void propagatesMissingCollectionExceptionUnchanged() {
        LocalizedException missing = LocalizedException.badRequest(
                "collection.not-found",
                "收藏夹不存在: {0}",
                404L
        );
        when(collectionService.addNovel(404L, 101L)).thenThrow(missing);

        Throwable thrown = catchThrowable(() -> adapter.addWork(WorkType.NOVEL, 404L, 101L));

        assertThat(thrown).isSameAs(missing);
        assertThat(missing.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getMessageCode()).isEqualTo("collection.not-found");
        assertThat(missing.getMessageArgs()).containsExactly(404L);
        verify(collectionService).addNovel(404L, 101L);
    }
}
