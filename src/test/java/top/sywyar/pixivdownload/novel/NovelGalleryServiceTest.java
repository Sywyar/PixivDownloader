package top.sywyar.pixivdownload.novel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelGalleryRepository;
import top.sywyar.pixivdownload.novel.db.NovelRecord;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NovelGalleryService 删除小说单元测试")
class NovelGalleryServiceTest {

    @Mock
    private NovelDatabase novelDatabase;
    @Mock
    private NovelGalleryRepository novelGalleryRepository;
    @Mock
    private AuthorService authorService;

    private NovelGalleryService novelGalleryService;

    @BeforeEach
    void setUp() {
        novelGalleryService = new NovelGalleryService(novelDatabase, novelGalleryRepository, authorService);
    }

    @Test
    @DisplayName("小说不存在时返回 false，且不删除数据库记录")
    void shouldNotDeleteWhenNovelMissing() {
        when(novelDatabase.getNovel(1L)).thenReturn(null);

        assertThat(novelGalleryService.deleteNovel(1L)).isFalse();

        verify(novelDatabase, never()).deleteNovel(anyLong());
    }

    @Test
    @DisplayName("删除小说应清理全部数据库留存（NovelDatabase.deleteNovel）")
    void shouldDeleteNovelDatabaseRows() {
        // folder() 返回 null 时跳过文件删除，仅验证 DB 清理被触发
        NovelRecord record = mock(NovelRecord.class);
        when(novelDatabase.getNovel(100L)).thenReturn(record);

        assertThat(novelGalleryService.deleteNovel(100L)).isTrue();

        verify(novelDatabase).deleteNovel(100L);
    }

    @Test
    @DisplayName("批量删除应去重并返回实际删除数量")
    void shouldBatchDeleteDistinctAndCount() {
        NovelRecord record = mock(NovelRecord.class);
        when(novelDatabase.getNovel(1L)).thenReturn(record);
        when(novelDatabase.getNovel(2L)).thenReturn(null);

        int deleted = novelGalleryService.deleteNovels(List.of(1L, 2L, 1L));

        assertThat(deleted).isEqualTo(1);
        verify(novelDatabase, times(1)).deleteNovel(1L);
        verify(novelDatabase, never()).deleteNovel(2L);
    }

    @Test
    @DisplayName("批量删除入参为空时返回 0")
    void shouldReturnZeroForEmptyBatch() {
        assertThat(novelGalleryService.deleteNovels(null)).isZero();
        assertThat(novelGalleryService.deleteNovels(List.of())).isZero();
        verify(novelDatabase, never()).deleteNovel(anyLong());
    }
}
