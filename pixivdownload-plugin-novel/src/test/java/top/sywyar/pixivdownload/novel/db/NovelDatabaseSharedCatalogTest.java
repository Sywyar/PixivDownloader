package top.sywyar.pixivdownload.novel.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.core.db.pathprefix.StoredPathCodec;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import top.sywyar.pixivdownload.core.work.service.WorkTagCatalog;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("小说标签共享目录边界")
class NovelDatabaseSharedCatalogTest {

    @Mock
    private NovelMapper novelMapper;
    @Mock
    private WorkTagCatalog workTagCatalog;
    @Mock
    private StoredPathCodec storedPathCodec;

    private NovelDatabase database;

    @BeforeEach
    void setUp() {
        database = new NovelDatabase(novelMapper, workTagCatalog, storedPathCodec);
    }

    @Test
    @DisplayName("单本标签通过共享目录取 ID 且目录拒绝时不写关系")
    void savesNovelTagsThroughSharedCatalog() {
        when(workTagCatalog.getOrCreateTagId("原文", "translation")).thenReturn(11L);
        when(workTagCatalog.getOrCreateTagId("未收录", null)).thenReturn(null);

        database.saveNovelTags(7L, List.of(
                new WorkTag(null, "原文", "translation"),
                new WorkTag(null, "未收录", null),
                new WorkTag(null, " ", null)));

        verify(workTagCatalog).getOrCreateTagId("原文", "translation");
        verify(workTagCatalog).getOrCreateTagId("未收录", null);
        verify(novelMapper).insertNovelTag(7L, 11L);
        verify(novelMapper, never()).insertNovelTag(7L, 0L);
    }

    @Test
    @DisplayName("系列标签通过同一共享目录取 ID 并写入系列关系")
    void savesSeriesTagsThroughSharedCatalog() {
        when(workTagCatalog.getOrCreateTagId("系列标签", "series tag")).thenReturn(29L);

        database.saveNovelSeriesTags(
                9L, List.of(new WorkTag(null, "系列标签", "series tag")));

        verify(workTagCatalog).getOrCreateTagId("系列标签", "series tag");
        verify(novelMapper).insertNovelSeriesTag(9L, 29L);
    }
}
