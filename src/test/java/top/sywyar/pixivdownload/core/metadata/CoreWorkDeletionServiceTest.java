package top.sywyar.pixivdownload.core.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.plugin.api.WorkType;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("CoreWorkDeletionService 单元测试")
class CoreWorkDeletionServiceTest {

    @Mock
    private PixivDatabase pixivDatabase;
    @Mock
    private NovelDatabase novelDatabase;

    private CoreWorkDeletionService service;

    @BeforeEach
    void setUp() {
        service = new CoreWorkDeletionService(pixivDatabase, novelDatabase);
    }

    @Test
    @DisplayName("ARTWORK 软删除代理 PixivDatabase.markArtworkDeleted，不做硬删除、不触碰小说库")
    void shouldProxyArtworkSoftDeletion() {
        service.markDeleted(WorkType.ARTWORK, 10L);

        verify(pixivDatabase).markArtworkDeleted(10L);
        verify(pixivDatabase, never()).deleteArtwork(anyLong());
        verifyNoInteractions(novelDatabase);
    }

    @Test
    @DisplayName("NOVEL 软删除代理 NovelDatabase.markNovelDeleted，不做硬删除、不触碰插画库")
    void shouldProxyNovelSoftDeletion() {
        service.markDeleted(WorkType.NOVEL, 20L);

        verify(novelDatabase).markNovelDeleted(20L);
        verify(novelDatabase, never()).deleteNovel(anyLong());
        verifyNoInteractions(pixivDatabase);
    }
}
