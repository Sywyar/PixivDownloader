package top.sywyar.pixivdownload.core.metadata.novel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("宿主小说元数据窄投影")
class NovelMetadataRepositoryTest {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private NovelMetadataRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);
        jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("""
                CREATE TABLE novels (
                    novel_id INTEGER PRIMARY KEY,
                    title TEXT,
                    folder TEXT,
                    count INTEGER,
                    extensions TEXT,
                    time INTEGER,
                    "R18" INTEGER,
                    is_ai INTEGER,
                    author_id INTEGER,
                    description TEXT,
                    file_name INTEGER,
                    file_author_name_id INTEGER,
                    series_id INTEGER,
                    series_order INTEGER,
                    word_count INTEGER,
                    text_length INTEGER,
                    reading_time_seconds INTEGER,
                    page_count INTEGER,
                    is_original INTEGER,
                    x_language TEXT,
                    cover_ext TEXT,
                    deleted INTEGER,
                    upload_time INTEGER
                )
                """);
        jdbc.execute("""
                CREATE TABLE novel_series (
                    series_id INTEGER PRIMARY KEY,
                    title TEXT,
                    author_id INTEGER,
                    cover_ext TEXT
                )
                """);

        PathPrefixCodec pathPrefixCodec = mock(PathPrefixCodec.class);
        when(pathPrefixCodec.resolve(anyString()))
                .thenAnswer(invocation -> "resolved:" + invocation.getArgument(0, String.class));
        repository = new NovelMetadataRepository(dataSource, pathPrefixCodec);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    @DisplayName("宿主行不暴露正文且查询能在无 raw_content 列的窄表上执行")
    void shouldKeepRawContentOutOfHostProjection() throws ReflectiveOperationException {
        insertNovel(42L, "{1}/chapter-42", 420L, 2L, false);

        NovelMetadataRow row = repository.getNovel(42L);

        assertThat(row).isNotNull();
        assertThat(row.novelId()).isEqualTo(42L);
        assertThat(row.folder()).isEqualTo("resolved:{1}/chapter-42");
        assertThat(componentNames(NovelMetadataRow.class))
                .containsExactly(
                        "novelId", "title", "folder", "count", "extensions", "time",
                        "xRestrict", "isAi", "authorId", "description", "fileName",
                        "fileAuthorNameId", "seriesId", "seriesOrder", "wordCount",
                        "textLength", "readingTimeSeconds", "pageCount", "isOriginal",
                        "xLanguage", "coverExt", "deleted", "uploadTime")
                .doesNotContain("rawContent");
        assertThat(sqlConstant("SELECT_NOVEL_METADATA")).doesNotContainIgnoringCase("raw_content");
    }

    @Test
    @DisplayName("同系列读取过滤软删除并保持系列序号、时间排序和路径解析")
    void shouldFilterDeletedRowsAndPreserveSeriesOrdering() {
        insertNovel(1L, "{1}/chapter-3", 300L, 2L, false);
        insertNovel(2L, "{1}/chapter-2", 200L, 1L, false);
        insertNovel(3L, "{1}/deleted", 100L, 1L, true);
        insertNovel(4L, "{1}/chapter-1", 100L, 1L, false);

        var rows = repository.getNovelsBySeriesId(9L);

        assertThat(rows).extracting(NovelMetadataRow::novelId).containsExactly(4L, 2L, 1L);
        assertThat(rows).extracting(NovelMetadataRow::folder).containsExactly(
                "resolved:{1}/chapter-1",
                "resolved:{1}/chapter-2",
                "resolved:{1}/chapter-3");
        assertThat(rows).allMatch(row -> !row.deleted());
    }

    @Test
    @DisplayName("宿主系列行只保留四个画廊与聚合所需字段")
    void shouldKeepSeriesProjectionNarrow() throws ReflectiveOperationException {
        jdbc.update(
                "INSERT INTO novel_series(series_id, title, author_id, cover_ext) VALUES (?, ?, ?, ?)",
                9L, "系列标题", 88L, "jpg");

        NovelSeriesMetadataRow row = repository.getSeries(9L);

        assertThat(row).isEqualTo(new NovelSeriesMetadataRow(9L, "系列标题", 88L, "jpg"));
        assertThat(componentNames(NovelSeriesMetadataRow.class))
                .containsExactly("seriesId", "title", "authorId", "coverExt");
        assertThat(sqlConstant("SELECT_SERIES_METADATA"))
                .doesNotContainIgnoringCase("updated_time", "description", "cover_folder");
    }

    @Test
    @DisplayName("短关键词 LIKE 回退按字面量匹配通配符")
    void shouldEscapeLikeWildcardsForShortTerms() {
        jdbc.execute("ALTER TABLE novels ADD COLUMN raw_content TEXT");
        insertNovel(30L, "/novel/30", 30L, 0L, false);
        insertNovel(31L, "/novel/31", 31L, 0L, false);
        insertNovel(32L, "/novel/32", 32L, 0L, false);
        insertNovel(33L, "/novel/33", 33L, 0L, false);
        jdbc.update("UPDATE novels SET raw_content = ? WHERE novel_id = ?", "100% literal", 30L);
        jdbc.update("UPDATE novels SET raw_content = ? WHERE novel_id = ?", "100 percent", 31L);
        jdbc.update("UPDATE novels SET raw_content = ? WHERE novel_id = ?", "A_B literal", 32L);
        jdbc.update("UPDATE novels SET raw_content = ? WHERE novel_id = ?", "ACB literal", 33L);

        assertThat(repository.searchNovelContentIds("%")).containsExactly(30L);
        assertThat(repository.searchNovelContentIds("_")).containsExactly(32L);
    }

    private void insertNovel(long novelId, String folder, long time, long seriesOrder, boolean deleted) {
        jdbc.update("""
                        INSERT INTO novels(
                            novel_id, title, folder, count, extensions, time, "R18", is_ai,
                            author_id, description, file_name, file_author_name_id, series_id,
                            series_order, word_count, text_length, reading_time_seconds, page_count,
                            is_original, x_language, cover_ext, deleted, upload_time
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                novelId, "novel-" + novelId, folder, 1, "txt", time, 0, false,
                7L, "description", 1L, 2L, 9L, seriesOrder, 100, 200, 30, 1,
                true, "ja", "jpg", deleted, 123L);
    }

    private static String[] componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(component -> component.getName())
                .toArray(String[]::new);
    }

    private static String sqlConstant(String fieldName) throws ReflectiveOperationException {
        Field field = NovelMetadataRepository.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(null);
    }
}
