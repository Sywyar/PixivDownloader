package top.sywyar.pixivdownload.gallery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.download.ArtworkFileLocator;
import top.sywyar.pixivdownload.download.DownloadService;
import top.sywyar.pixivdownload.download.db.ArtworkRecord;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.series.MangaSeriesService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GalleryService 删除作品单元测试")
class GalleryServiceTest {

    @Mock
    private GalleryRepository galleryRepository;
    @Mock
    private PixivDatabase pixivDatabase;
    @Mock
    private DownloadService downloadService;
    @Mock
    private AuthorService authorService;
    @Mock
    private MangaSeriesService mangaSeriesService;
    @Mock
    private ArtworkFileLocator artworkFileLocator;

    private GalleryService galleryService;

    @BeforeEach
    void setUp() {
        galleryService = new GalleryService(galleryRepository, pixivDatabase,
                downloadService, authorService, mangaSeriesService, artworkFileLocator);
    }

    @Test
    @DisplayName("作品不存在时返回 false，且不删除文件或数据库记录")
    void shouldNotDeleteWhenArtworkMissing() {
        when(pixivDatabase.getArtwork(1L)).thenReturn(null);

        assertThat(galleryService.deleteArtwork(1L)).isFalse();

        verify(artworkFileLocator, never()).deleteArtworkFiles(any());
        verify(pixivDatabase, never()).deleteArtwork(anyLong());
    }

    @Test
    @DisplayName("删除作品应先删磁盘文件，再删数据库记录")
    void shouldDeleteFilesThenDatabase() {
        ArtworkRecord record = mock(ArtworkRecord.class);
        when(pixivDatabase.getArtwork(12345L)).thenReturn(record);
        when(artworkFileLocator.deleteArtworkFiles(record)).thenReturn(true);

        assertThat(galleryService.deleteArtwork(12345L)).isTrue();

        InOrder inOrder = inOrder(artworkFileLocator, pixivDatabase);
        inOrder.verify(artworkFileLocator).deleteArtworkFiles(record);
        inOrder.verify(pixivDatabase).deleteArtwork(12345L);
    }

    @Test
    @DisplayName("磁盘文件删除失败时不删数据库记录，并抛出异常以阻止状态不一致")
    void shouldAbortWhenFileDeletionFails() {
        ArtworkRecord record = mock(ArtworkRecord.class);
        when(pixivDatabase.getArtwork(999L)).thenReturn(record);
        when(artworkFileLocator.deleteArtworkFiles(record)).thenReturn(false);

        assertThatThrownBy(() -> galleryService.deleteArtwork(999L))
                .isInstanceOf(top.sywyar.pixivdownload.i18n.LocalizedException.class);

        verify(pixivDatabase, never()).deleteArtwork(anyLong());
    }

    @Test
    @DisplayName("批量删除应去重并返回实际删除数量")
    void shouldBatchDeleteDistinctAndCount() {
        ArtworkRecord record = mock(ArtworkRecord.class);
        when(pixivDatabase.getArtwork(1L)).thenReturn(record);
        when(pixivDatabase.getArtwork(2L)).thenReturn(null);
        when(artworkFileLocator.deleteArtworkFiles(record)).thenReturn(true);

        int deleted = galleryService.deleteArtworks(List.of(1L, 2L, 1L));

        assertThat(deleted).isEqualTo(1);
        verify(pixivDatabase, times(1)).deleteArtwork(1L);
        verify(pixivDatabase, never()).deleteArtwork(2L);
    }

    @Test
    @DisplayName("批量删除入参为空时返回 0")
    void shouldReturnZeroForEmptyBatch() {
        assertThat(galleryService.deleteArtworks(null)).isZero();
        assertThat(galleryService.deleteArtworks(List.of())).isZero();
        verify(pixivDatabase, never()).deleteArtwork(anyLong());
    }
}
