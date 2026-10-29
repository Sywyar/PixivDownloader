package top.sywyar.pixivdownload.core.metadata.artwork;

import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.query.WorkQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插画查询仓库")
class GalleryRepositoryTest {

    private static final long AUTHOR_A = 101L;
    private static final long AUTHOR_E = 202L;
    private static final long AUTHOR_Z = 303L;
    private static final long ARTWORK_B = 1001L;
    private static final long ARTWORK_F = 1002L;
    private static final long ARTWORK_H = 1003L;
    private static final long ARTWORK_K = 1004L;
    private static final long TAG_C = 11L;
    private static final long TAG_D = 12L;
    private static final long TAG_G = 13L;
    private static final long TAG_X = 14L;

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private GalleryRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        jdbc = new JdbcTemplate(dataSource);
        repository = new GalleryRepository(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource));

        createTables();
        seedArtworks();
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    @DisplayName("仅作者筛选时合并必须作者与可选作者")
    void shouldUnionRequiredAndOptionalAuthorsWhenNoTagsAreSelected() {
        WorkQuery query = WorkQuery.builder(WorkType.ARTWORK)
                .page(0).size(24).sort("date").order("desc").r18("any").ai("any")
                .authorIds(java.util.List.of(AUTHOR_A))
                .optionalAuthorIds(java.util.List.of(AUTHOR_E))
                .build();

        GalleryRepository.QueryResult result = repository.findArtworkIds(query);

        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.ids()).containsExactly(ARTWORK_H, ARTWORK_F, ARTWORK_B);
    }

    @Test
    @DisplayName("仅标签筛选时合并必须标签与可选标签")
    void shouldUnionRequiredAndOptionalTagsWhenNoAuthorsAreSelected() {
        WorkQuery query = WorkQuery.builder(WorkType.ARTWORK)
                .page(0).size(24).sort("date").order("desc").r18("any").ai("any")
                .tagIds(java.util.List.of(TAG_C))
                .optionalTagIds(java.util.List.of(TAG_G))
                .build();

        GalleryRepository.QueryResult result = repository.findArtworkIds(query);

        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.ids()).containsExactly(ARTWORK_K, ARTWORK_F, ARTWORK_B);
    }

    @Test
    @DisplayName("未选择必须作者时合并必须标签与可选作者")
    void shouldUnionRequiredTagsAndOptionalAuthorsWhenRequiredAuthorsAreNotSelected() {
        WorkQuery query = WorkQuery.builder(WorkType.ARTWORK)
                .page(0).size(24).sort("date").order("desc").r18("any").ai("any")
                .tagIds(java.util.List.of(TAG_C))
                .optionalAuthorIds(java.util.List.of(AUTHOR_E))
                .build();

        GalleryRepository.QueryResult result = repository.findArtworkIds(query);

        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.ids()).containsExactly(ARTWORK_K, ARTWORK_F, ARTWORK_B);
    }

    @Test
    @DisplayName("必须标签与作者构成交集且保留可选条件的并集")
    void shouldUseRequiredTagAndAuthorAsMustCoreWithOptionalReleaseTerms() {
        WorkQuery query = WorkQuery.builder(WorkType.ARTWORK)
                .page(0).size(24).sort("date").order("desc").r18("any").ai("any")
                .tagIds(java.util.List.of(TAG_C))
                .optionalTagIds(java.util.List.of(TAG_G))
                .authorIds(java.util.List.of(AUTHOR_A))
                .optionalAuthorIds(java.util.List.of(AUTHOR_E))
                .build();

        GalleryRepository.QueryResult result = repository.findArtworkIds(query);

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.ids()).containsExactly(ARTWORK_F, ARTWORK_B);
    }

    @Test
    @DisplayName("未选择必须作者时可选标签仍可纳入作品")
    void shouldReleaseOptionalTagRowsWhenRequiredAuthorIsNotSelected() {
        WorkQuery query = WorkQuery.builder(WorkType.ARTWORK)
                .page(0).size(24).sort("date").order("desc").r18("any").ai("any")
                .tagIds(java.util.List.of(TAG_C))
                .optionalTagIds(java.util.List.of(TAG_X))
                .optionalAuthorIds(java.util.List.of(AUTHOR_E))
                .build();

        GalleryRepository.QueryResult result = repository.findArtworkIds(query);

        assertThat(result.totalElements()).isEqualTo(4);
        assertThat(result.ids()).containsExactly(ARTWORK_K, ARTWORK_H, ARTWORK_F, ARTWORK_B);
    }

    @Test
    @DisplayName("已选择必须标签与作者时可选作者仍可纳入作品")
    void shouldReleaseOptionalAuthorRowsWhenRequiredTagAndAuthorAreBothSelected() {
        WorkQuery query = WorkQuery.builder(WorkType.ARTWORK)
                .page(0).size(24).sort("date").order("desc").r18("any").ai("any")
                .tagIds(java.util.List.of(TAG_C))
                .authorIds(java.util.List.of(AUTHOR_A))
                .optionalAuthorIds(java.util.List.of(AUTHOR_E))
                .build();

        GalleryRepository.QueryResult result = repository.findArtworkIds(query);

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.ids()).containsExactly(ARTWORK_F, ARTWORK_B);
    }

    @Test
    @DisplayName("排除作者在正向条件匹配后排除作品")
    void shouldApplyExcludedAuthorsAfterPositiveClauses() {
        WorkQuery query = WorkQuery.builder(WorkType.ARTWORK)
                .page(0).size(24).sort("date").order("desc").r18("any").ai("any")
                .tagIds(java.util.List.of(TAG_D))
                .excludedAuthorIds(java.util.List.of(AUTHOR_A))
                .optionalAuthorIds(java.util.List.of(AUTHOR_E))
                .build();

        GalleryRepository.QueryResult result = repository.findArtworkIds(query);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.ids()).containsExactly(ARTWORK_F);
    }

    @Test
    @DisplayName("排除标签在正向条件匹配后排除作品")
    void shouldApplyExcludedTagsAfterPositiveClauses() {
        WorkQuery query = WorkQuery.builder(WorkType.ARTWORK)
                .page(0).size(24).sort("date").order("desc").r18("any").ai("any")
                .tagIds(java.util.List.of(TAG_D))
                .excludedTagIds(java.util.List.of(TAG_G))
                .optionalAuthorIds(java.util.List.of(AUTHOR_E))
                .build();

        GalleryRepository.QueryResult result = repository.findArtworkIds(query);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.ids()).containsExactly(ARTWORK_B);
    }

    @Test
    @DisplayName("上传时间范围含起点不含终点且未知时间在两个排序方向都置后")
    void uploadTimeRangeAndNullOrdering() {
        jdbc.update("UPDATE artworks SET upload_time = 100 WHERE artwork_id = ?", ARTWORK_B);
        jdbc.update("UPDATE artworks SET upload_time = 200 WHERE artwork_id = ?", ARTWORK_F);
        jdbc.update("UPDATE artworks SET upload_time = 200 WHERE artwork_id = ?", ARTWORK_H);
        var ascending = WorkQuery.builder(WorkType.ARTWORK).sort("uploadTime").order("asc").build();
        var descending = WorkQuery.builder(WorkType.ARTWORK).sort("uploadTime").order("desc").build();
        assertThat(repository.findArtworkIds(ascending).ids()).containsExactly(ARTWORK_B, ARTWORK_F, ARTWORK_H, ARTWORK_K);
        assertThat(repository.findArtworkIds(descending).ids()).containsExactly(ARTWORK_H, ARTWORK_F, ARTWORK_B, ARTWORK_K);
        var filtered = repository.findArtworkIds(ascending.withUploadTimeRange(100L, 200L));
        assertThat(filtered.totalElements()).isEqualTo(1);
        assertThat(filtered.ids()).containsExactly(ARTWORK_B);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ascending.withUploadTimeRange(200L, 100L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE artworks (
                    artwork_id INTEGER PRIMARY KEY,
                    title TEXT NOT NULL,
                    folder TEXT NOT NULL,
                    count INTEGER NOT NULL,
                    extensions TEXT NOT NULL,
                    time INTEGER NOT NULL,
                    upload_time INTEGER DEFAULT NULL,
                    "R18" INTEGER DEFAULT NULL,
                    is_ai INTEGER DEFAULT NULL,
                    author_id INTEGER DEFAULT NULL,
                    description TEXT DEFAULT NULL,
                    moved INTEGER DEFAULT 0,
                    move_folder TEXT,
                    move_time INTEGER,
                    deleted INTEGER NOT NULL DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE authors (
                    author_id INTEGER PRIMARY KEY,
                    name TEXT,
                    updated_time INTEGER
                )
                """);
        jdbc.execute("""
                CREATE TABLE artwork_tags (
                    artwork_id INTEGER NOT NULL,
                    tag_id INTEGER NOT NULL
                )
                """);
    }

    private void seedArtworks() {
        jdbc.update("INSERT INTO authors(author_id, name, updated_time) VALUES (?, ?, ?)", AUTHOR_A, "Author A", 1L);
        jdbc.update("INSERT INTO authors(author_id, name, updated_time) VALUES (?, ?, ?)", AUTHOR_E, "Author E", 1L);
        jdbc.update("INSERT INTO authors(author_id, name, updated_time) VALUES (?, ?, ?)", AUTHOR_Z, "Author Z", 1L);

        jdbc.update(
                "INSERT INTO artworks(artwork_id, title, folder, count, extensions, time, author_id, moved) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ARTWORK_B, "Artwork B", "/b", 1, "jpg", 100L, AUTHOR_A, 0
        );
        jdbc.update(
                "INSERT INTO artworks(artwork_id, title, folder, count, extensions, time, author_id, moved) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ARTWORK_F, "Artwork F", "/f", 1, "jpg", 200L, AUTHOR_E, 0
        );
        jdbc.update(
                "INSERT INTO artworks(artwork_id, title, folder, count, extensions, time, author_id, moved) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ARTWORK_H, "Artwork H", "/h", 1, "jpg", 300L, AUTHOR_A, 0
        );
        jdbc.update(
                "INSERT INTO artworks(artwork_id, title, folder, count, extensions, time, author_id, moved) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ARTWORK_K, "Artwork K", "/k", 1, "jpg", 400L, AUTHOR_Z, 0
        );

        insertTag(ARTWORK_B, TAG_C);
        insertTag(ARTWORK_B, TAG_D);
        insertTag(ARTWORK_F, TAG_D);
        insertTag(ARTWORK_F, TAG_G);
        insertTag(ARTWORK_H, TAG_X);
        insertTag(ARTWORK_K, TAG_C);
    }

    private void insertTag(long artworkId, long tagId) {
        jdbc.update("INSERT INTO artwork_tags(artwork_id, tag_id) VALUES (?, ?)", artworkId, tagId);
    }
}
