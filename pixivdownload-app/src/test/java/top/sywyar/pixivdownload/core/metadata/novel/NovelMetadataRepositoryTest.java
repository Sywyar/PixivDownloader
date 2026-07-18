package top.sywyar.pixivdownload.core.metadata.novel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("宿主小说元数据窄投影")
class NovelMetadataRepositoryTest {

    private static final Set<String> EXPECTED_HOST_NOVEL_SOURCE_FILES = Set.of(
            "NovelAuthorSummary.java",
            "NovelGalleryRepository.java",
            "NovelMetadataRepository.java",
            "NovelMetadataRow.java",
            "NovelSeriesTitleRow.java",
            "NovelTagOption.java",
            "NovelWorkSearch.java");

    private static final List<Class<?>> HOST_NOVEL_TYPES = List.of(
            NovelAuthorSummary.class,
            NovelGalleryRepository.class,
            NovelMetadataRepository.class,
            NovelMetadataRow.class,
            NovelSeriesTitleRow.class,
            NovelTagOption.class,
            NovelWorkSearch.class);

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
    @DisplayName("宿主行不暴露正文和小说展示详情，只保留跨作品核心所需窄列")
    void shouldKeepPluginOwnedDetailsOutOfHostProjection() throws ReflectiveOperationException {
        insertNovel(42L, "{1}/chapter-42", 420L, 2L, false);

        NovelMetadataRow row = repository.getNovel(42L);

        assertThat(row).isNotNull();
        assertThat(row.novelId()).isEqualTo(42L);
        assertThat(row.folder()).isEqualTo("resolved:{1}/chapter-42");
        assertThat(componentNames(NovelMetadataRow.class))
                .containsExactly(
                        "novelId", "title", "folder", "count", "extensions", "time",
                        "xRestrict", "isAi", "authorId", "description", "fileName",
                        "fileAuthorNameId", "seriesId", "seriesOrder", "wordCount", "isOriginal",
                        "coverExt", "deleted", "uploadTime")
                .doesNotContain("textLength", "readingTimeSeconds", "pageCount",
                        "xLanguage", "rawContent");
        assertThat(sqlConstant("SELECT_NOVEL_METADATA")).doesNotContainIgnoringCase(
                "text_length", "reading_time_seconds", "page_count",
                "x_language", "raw_content");
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
    @DisplayName("宿主系列行只保留跨作品装配所需的 id 与标题")
    void shouldKeepSeriesProjectionNarrow() throws ReflectiveOperationException {
        jdbc.update(
                "INSERT INTO novel_series(series_id, title, author_id, cover_ext) VALUES (?, ?, ?, ?)",
                9L, "系列标题", 88L, "jpg");

        NovelSeriesTitleRow row = repository.getSeries(9L);

        assertThat(row).isEqualTo(new NovelSeriesTitleRow(9L, "系列标题"));
        assertThat(componentNames(NovelSeriesTitleRow.class))
                .containsExactly("seriesId", "title");
        assertThat(sqlConstant("SELECT_SERIES_TITLE"))
                .doesNotContainIgnoringCase("author_id", "cover_ext", "updated_time", "description", "cover_folder");
    }

    @Test
    @DisplayName("宿主小说包只保留显式窄投影且不得持有正文或全文索引 SQL")
    void shouldKeepPluginOwnedPersistenceAndFtsOutOfHostPackage() throws IOException {
        try (Stream<Path> sources = Files.list(hostNovelSourceRoot())) {
            assertThat(sources
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet()))
                    .as("新增宿主小说类型必须先证明是跨作品核心窄投影；完整持久化行留在小说插件")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_HOST_NOVEL_SOURCE_FILES);
        }

        for (Class<?> type : HOST_NOVEL_TYPES) {
            String bytecode = new String(classBytes(type), StandardCharsets.ISO_8859_1);
            assertThat(bytecode)
                    .as(type.getName() + " 不得读取正文列或维护小说 FTS")
                    .doesNotContain("raw_content", "novels_fts");
        }
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

    private static Path hostNovelSourceRoot() {
        Path relative = Path.of("top", "sywyar", "pixivdownload", "core", "metadata", "novel");
        for (Path candidate : List.of(
                Path.of("src", "main", "java").resolve(relative),
                Path.of("pixivdownload-app", "src", "main", "java").resolve(relative))) {
            if (Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("cannot locate app host novel source package");
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resourceName = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("cannot locate class bytes: " + type.getName());
            }
            return input.readAllBytes();
        }
    }
}
