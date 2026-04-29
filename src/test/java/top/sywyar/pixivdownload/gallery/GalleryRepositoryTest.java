package top.sywyar.pixivdownload.gallery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GalleryRepository tests")
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
        repository = new GalleryRepository(dataSource);

        createTables();
        seedArtworks();
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    @DisplayName("author-only filters should union required and optional authors")
    void shouldUnionRequiredAndOptionalAuthorsWhenNoTagsAreSelected() {
        GalleryQuery query = GalleryQuery.normalize(
                0, 24, "date", "desc", null, "any", "any",
                null, null,
                null,
                null,
                null,
                java.util.List.of(AUTHOR_A),
                null,
                java.util.List.of(AUTHOR_E));

        GalleryRepository.QueryResult result = repository.findArtworkIds(query);

        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.ids()).containsExactly(ARTWORK_H, ARTWORK_F, ARTWORK_B);
    }

    @Test
    @DisplayName("tag-only filters should union required and optional tags")
    void shouldUnionRequiredAndOptionalTagsWhenNoAuthorsAreSelected() {
        GalleryQuery query = GalleryQuery.normalize(
                0, 24, "date", "desc", null, "any", "any",
                null, null,
                java.util.List.of(TAG_C),
                null,
                java.util.List.of(TAG_G),
                null,
                null,
                null);

        GalleryRepository.QueryResult result = repository.findArtworkIds(query);

        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.ids()).containsExactly(ARTWORK_K, ARTWORK_F, ARTWORK_B);
    }

    @Test
    @DisplayName("required tags and optional authors should union when required authors are not selected")
    void shouldUnionRequiredTagsAndOptionalAuthorsWhenRequiredAuthorsAreNotSelected() {
        GalleryQuery query = GalleryQuery.normalize(
                0, 24, "date", "desc", null, "any", "any",
                null, null,
                java.util.List.of(TAG_C),
                null,
                null,
                null,
                null,
                java.util.List.of(AUTHOR_E));

        GalleryRepository.QueryResult result = repository.findArtworkIds(query);

        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.ids()).containsExactly(ARTWORK_K, ARTWORK_F, ARTWORK_B);
    }

    @Test
    @DisplayName("required tag and author should form the must core while optional terms still release rows")
    void shouldUseRequiredTagAndAuthorAsMustCoreWithOptionalReleaseTerms() {
        GalleryQuery query = GalleryQuery.normalize(
                0, 24, "date", "desc", null, "any", "any",
                null, null,
                java.util.List.of(TAG_C),
                null,
                java.util.List.of(TAG_G),
                java.util.List.of(AUTHOR_A),
                null,
                java.util.List.of(AUTHOR_E));

        GalleryRepository.QueryResult result = repository.findArtworkIds(query);

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.ids()).containsExactly(ARTWORK_F, ARTWORK_B);
    }

    @Test
    @DisplayName("optional tags should release rows when only required tags are selected")
    void shouldReleaseOptionalTagRowsWhenRequiredAuthorIsNotSelected() {
        GalleryQuery query = GalleryQuery.normalize(
                0, 24, "date", "desc", null, "any", "any",
                null, null,
                java.util.List.of(TAG_C),
                null,
                java.util.List.of(TAG_X),
                null,
                null,
                java.util.List.of(AUTHOR_E));

        GalleryRepository.QueryResult result = repository.findArtworkIds(query);

        assertThat(result.totalElements()).isEqualTo(4);
        assertThat(result.ids()).containsExactly(ARTWORK_K, ARTWORK_H, ARTWORK_F, ARTWORK_B);
    }

    @Test
    @DisplayName("optional authors should release rows even when required tag and author are both selected")
    void shouldReleaseOptionalAuthorRowsWhenRequiredTagAndAuthorAreBothSelected() {
        GalleryQuery query = GalleryQuery.normalize(
                0, 24, "date", "desc", null, "any", "any",
                null, null,
                java.util.List.of(TAG_C),
                null,
                null,
                java.util.List.of(AUTHOR_A),
                null,
                java.util.List.of(AUTHOR_E));

        GalleryRepository.QueryResult result = repository.findArtworkIds(query);

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.ids()).containsExactly(ARTWORK_F, ARTWORK_B);
    }

    @Test
    @DisplayName("excluded authors should remove rows after the positive clauses match")
    void shouldApplyExcludedAuthorsAfterPositiveClauses() {
        GalleryQuery query = GalleryQuery.normalize(
                0, 24, "date", "desc", null, "any", "any",
                null, null,
                java.util.List.of(TAG_D),
                null,
                null,
                null,
                java.util.List.of(AUTHOR_A),
                java.util.List.of(AUTHOR_E));

        GalleryRepository.QueryResult result = repository.findArtworkIds(query);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.ids()).containsExactly(ARTWORK_F);
    }

    @Test
    @DisplayName("excluded tags should remove rows after the positive clauses match")
    void shouldApplyExcludedTagsAfterPositiveClauses() {
        GalleryQuery query = GalleryQuery.normalize(
                0, 24, "date", "desc", null, "any", "any",
                null, null,
                java.util.List.of(TAG_D),
                java.util.List.of(TAG_G),
                null,
                null,
                null,
                java.util.List.of(AUTHOR_E));

        GalleryRepository.QueryResult result = repository.findArtworkIds(query);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.ids()).containsExactly(ARTWORK_B);
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
                    "R18" INTEGER DEFAULT NULL,
                    is_ai INTEGER DEFAULT NULL,
                    author_id INTEGER DEFAULT NULL,
                    description TEXT DEFAULT NULL,
                    moved INTEGER DEFAULT 0,
                    move_folder TEXT,
                    move_time INTEGER
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
