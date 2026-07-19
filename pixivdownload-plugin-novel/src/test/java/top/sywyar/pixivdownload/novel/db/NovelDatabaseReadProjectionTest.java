package top.sywyar.pixivdownload.novel.db;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.db.pathprefix.StoredPathCodec;
import top.sywyar.pixivdownload.core.work.service.WorkTagCatalog;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("小说插件完整持久化行读取")
class NovelDatabaseReadProjectionTest {

    private NovelMapper mapper;
    private StoredPathCodec pathCodec;
    private NovelDatabase database;

    @BeforeEach
    void setUp() {
        mapper = mock(NovelMapper.class);
        pathCodec = mock(StoredPathCodec.class);
        database = new NovelDatabase(
                mapper,
                mock(WorkTagCatalog.class),
                pathCodec);
    }

    @Test
    @DisplayName("单行读取保留正文、软删除与上传时间并解析存储路径")
    void getNovelPreservesPluginOwnedFieldsAndResolvesFolder() {
        NovelRecord stored = novel(42L, "{7}/novel-42", "原始正文", true, 1717000000000L);
        when(mapper.findById(42L)).thenReturn(stored);
        when(pathCodec.resolve("{7}/novel-42")).thenReturn("D:/downloads/novel-42");

        NovelRecord resolved = database.getNovel(42L);

        assertThat(resolved.folder()).isEqualTo("D:/downloads/novel-42");
        assertThat(resolved.rawContent()).isEqualTo("原始正文");
        assertThat(resolved.deleted()).isTrue();
        assertThat(resolved.uploadTime()).isEqualTo(1717000000000L);
    }

    @Test
    @DisplayName("系列章节读取保持 Mapper 顺序、过滤契约与逐行路径解析")
    void getNovelsBySeriesPreservesMapperOrderAndResolvesFolders() {
        NovelRecord first = novel(2L, "{1}/novel-2", "第二章", false, null);
        NovelRecord second = novel(1L, "{1}/novel-1", "第一章", false, null);
        when(mapper.findBySeriesId(9L)).thenReturn(List.of(first, second));
        when(pathCodec.resolve("{1}/novel-2")).thenReturn("D:/novel-2");
        when(pathCodec.resolve("{1}/novel-1")).thenReturn("D:/novel-1");

        List<NovelRecord> rows = database.getNovelsBySeriesId(9L);

        assertThat(rows).extracting(NovelRecord::novelId).containsExactly(2L, 1L);
        assertThat(rows).extracting(NovelRecord::folder).containsExactly("D:/novel-2", "D:/novel-1");
        verify(mapper).findBySeriesId(9L);
    }

    @Test
    @DisplayName("系列行读取保留插件字段并解析封面目录")
    void getSeriesPreservesFullRowAndResolvesCoverFolder() {
        NovelSeries stored = new NovelSeries(9L, "系列", 88L, 123L, "简介", "jpg", "{3}/series-9");
        when(mapper.findSeriesById(9L)).thenReturn(stored);
        when(pathCodec.resolve("{3}/series-9")).thenReturn("D:/series-9");

        NovelSeries resolved = database.getSeries(9L);

        assertThat(resolved.description()).isEqualTo("简介");
        assertThat(resolved.updatedTime()).isEqualTo(123L);
        assertThat(resolved.coverFolder()).isEqualTo("D:/series-9");
    }

    @Test
    @DisplayName("活动判重与系列章节 SQL 固定软删除及排序语义")
    void mapperQueriesPinSoftDeleteAndOrderingSemantics() throws Exception {
        when(mapper.countActiveById(42L)).thenReturn(1);
        assertThat(database.hasActiveNovel(42L)).isTrue();

        Select select = NovelMapper.class
                .getMethod("findBySeriesId", long.class)
                .getAnnotation(Select.class);
        assertThat(String.join(" ", select.value()))
                .contains("raw_content AS rawContent")
                .contains("deleted = 0")
                .contains("ORDER BY series_order ASC, time ASC");
    }

    private static NovelRecord novel(
            long id,
            String folder,
            String rawContent,
            boolean deleted,
            Long uploadTime) {
        return new NovelRecord(
                id, "小说" + id, folder, 1, "txt", 1000L + id,
                0, false, 88L, "简介", 1L, 2L, 9L, id,
                100, 200, 30, 1, true, "ja", rawContent, "jpg", deleted, uploadTime);
    }
}
