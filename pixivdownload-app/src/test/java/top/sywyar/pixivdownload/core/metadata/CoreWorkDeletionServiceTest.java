package top.sywyar.pixivdownload.core.metadata;

import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;
import top.sywyar.pixivdownload.core.work.service.WorkDeletionException;
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CoreWorkDeletionService 统一删除编排单元测试")
class CoreWorkDeletionServiceTest {

    @Mock
    private WorkQueryService workQueryService;
    @Mock
    private WorkAssetService workAssetService;
    @Mock
    private PixivDatabase pixivDatabase;
    @Mock
    private NovelMetadataRepository novelMetadataRepository;

    private CoreWorkDeletionService service;

    @BeforeEach
    void setUp() {
        service = new CoreWorkDeletionService(workQueryService, workAssetService,
                pixivDatabase, novelMetadataRepository, TestI18nBeans.appMessages());
    }

    @Test
    @DisplayName("作品不存在或已软删时返回 false，不删文件、不软删数据库")
    void shouldReturnFalseWhenArtworkMissing() {
        when(workQueryService.hasActiveWork(WorkType.ARTWORK, 10L)).thenReturn(false);

        assertThat(service.delete(WorkType.ARTWORK, 10L)).isFalse();

        verify(workAssetService, never()).deleteLocalFiles(any(), anyLong());
        verify(pixivDatabase, never()).markArtworkDeleted(anyLong());
        verifyNoInteractions(novelMetadataRepository);
    }

    @Test
    @DisplayName("插画磁盘文件删除失败时抛纯领域失败，且数据库主行未软删")
    void shouldThrowConflictWithoutSoftDeleteWhenArtworkFileDeletionFails() {
        when(workQueryService.hasActiveWork(WorkType.ARTWORK, 10L)).thenReturn(true);
        when(workAssetService.deleteLocalFiles(WorkType.ARTWORK, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(WorkType.ARTWORK, 10L))
                .isInstanceOfSatisfying(WorkDeletionException.class, exception -> {
                    assertThat(exception.reason())
                            .isEqualTo(WorkDeletionException.Reason.LOCAL_FILE_DELETE_FAILED);
                    assertThat(exception.workType()).isEqualTo(WorkType.ARTWORK);
                    assertThat(exception.workId()).isEqualTo(10L);
                });

        verify(pixivDatabase, never()).markArtworkDeleted(anyLong());
        verifyNoInteractions(novelMetadataRepository);
    }

    @Test
    @DisplayName("插画删除成功：先删磁盘文件，再软删数据库主行（软删而非硬删）")
    void shouldDeleteArtworkFilesThenSoftDeleteDatabase() {
        when(workQueryService.hasActiveWork(WorkType.ARTWORK, 10L)).thenReturn(true);
        when(workAssetService.deleteLocalFiles(WorkType.ARTWORK, 10L)).thenReturn(true);

        assertThat(service.delete(WorkType.ARTWORK, 10L)).isTrue();

        InOrder inOrder = inOrder(workAssetService, pixivDatabase);
        inOrder.verify(workAssetService).deleteLocalFiles(WorkType.ARTWORK, 10L);
        inOrder.verify(pixivDatabase).markArtworkDeleted(10L);
        verify(pixivDatabase, never()).deleteArtwork(anyLong());
        verifyNoInteractions(novelMetadataRepository);
    }

    @Test
    @DisplayName("小说删除成功：先删磁盘文件，再软删小说主行，不触碰插画库")
    void shouldDeleteNovelFilesThenSoftDeleteDatabase() {
        when(workQueryService.hasActiveWork(WorkType.NOVEL, 20L)).thenReturn(true);
        when(workAssetService.deleteLocalFiles(WorkType.NOVEL, 20L)).thenReturn(true);

        assertThat(service.delete(WorkType.NOVEL, 20L)).isTrue();

        InOrder inOrder = inOrder(workAssetService, novelMetadataRepository);
        inOrder.verify(workAssetService).deleteLocalFiles(WorkType.NOVEL, 20L);
        inOrder.verify(novelMetadataRepository).markNovelDeleted(20L);
        verifyNoInteractions(pixivDatabase);
    }

    @Test
    @DisplayName("小说磁盘文件删除失败时抛纯领域失败，且小说主行未软删")
    void shouldThrowConflictWithoutSoftDeleteWhenNovelFileDeletionFails() {
        when(workQueryService.hasActiveWork(WorkType.NOVEL, 20L)).thenReturn(true);
        when(workAssetService.deleteLocalFiles(WorkType.NOVEL, 20L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(WorkType.NOVEL, 20L))
                .isInstanceOfSatisfying(WorkDeletionException.class, exception -> {
                    assertThat(exception.reason())
                            .isEqualTo(WorkDeletionException.Reason.LOCAL_FILE_DELETE_FAILED);
                    assertThat(exception.workType()).isEqualTo(WorkType.NOVEL);
                    assertThat(exception.workId()).isEqualTo(20L);
                });

        verify(novelMetadataRepository, never()).markNovelDeleted(anyLong());
        verifyNoInteractions(pixivDatabase);
    }

    @Test
    @DisplayName("批量删除 best-effort：去重、单个失败不中断、返回实际删除数")
    void shouldBatchDeleteBestEffortAndCountActualDeletions() {
        // 去重后 [1, 2, 3, 4]：1 成功、2 文件失败、3 在失败后仍成功、4 不存在。
        when(workQueryService.hasActiveWork(WorkType.ARTWORK, 1L)).thenReturn(true);
        when(workAssetService.deleteLocalFiles(WorkType.ARTWORK, 1L)).thenReturn(true);
        when(workQueryService.hasActiveWork(WorkType.ARTWORK, 2L)).thenReturn(true);
        when(workAssetService.deleteLocalFiles(WorkType.ARTWORK, 2L)).thenReturn(false);
        when(workQueryService.hasActiveWork(WorkType.ARTWORK, 3L)).thenReturn(true);
        when(workAssetService.deleteLocalFiles(WorkType.ARTWORK, 3L)).thenReturn(true);
        when(workQueryService.hasActiveWork(WorkType.ARTWORK, 4L)).thenReturn(false);

        int deleted = service.deleteAll(WorkType.ARTWORK, List.of(1L, 2L, 3L, 4L, 1L));

        assertThat(deleted).isEqualTo(2);
        verify(pixivDatabase, times(1)).markArtworkDeleted(1L);
        verify(pixivDatabase, never()).markArtworkDeleted(2L);
        verify(pixivDatabase, times(1)).markArtworkDeleted(3L);
    }

    @Test
    @DisplayName("批量删除入参为空或 null 时返回 0，不触碰文件与数据库")
    void shouldReturnZeroForEmptyBatch() {
        assertThat(service.deleteAll(WorkType.ARTWORK, null)).isZero();
        assertThat(service.deleteAll(WorkType.ARTWORK, List.of())).isZero();

        verifyNoInteractions(workQueryService, workAssetService, pixivDatabase, novelMetadataRepository);
    }
}
