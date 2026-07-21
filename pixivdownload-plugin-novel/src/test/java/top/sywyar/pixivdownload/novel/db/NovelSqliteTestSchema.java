package top.sywyar.pixivdownload.novel.db;

import org.springframework.jdbc.core.JdbcTemplate;

final class NovelSqliteTestSchema {

    private NovelSqliteTestSchema() {
    }

    static void createNovelRows(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE novels (
                    novel_id INTEGER PRIMARY KEY,
                    raw_content TEXT,
                    deleted INTEGER NOT NULL DEFAULT 0
                )
                """);
    }

    static void createSoftDeleteDerivedTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE novel_tags (
                    novel_id INTEGER NOT NULL,
                    tag_id INTEGER NOT NULL,
                    PRIMARY KEY (novel_id, tag_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE novel_collections (
                    collection_id INTEGER NOT NULL,
                    novel_id INTEGER NOT NULL,
                    added_time INTEGER NOT NULL,
                    PRIMARY KEY (collection_id, novel_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE novel_images (
                    novel_id INTEGER NOT NULL,
                    image_id TEXT NOT NULL,
                    ext TEXT NOT NULL,
                    PRIMARY KEY (novel_id, image_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE novel_translations (
                    novel_id INTEGER NOT NULL,
                    lang_code TEXT NOT NULL,
                    raw_content TEXT NOT NULL,
                    created_time INTEGER NOT NULL,
                    PRIMARY KEY (novel_id, lang_code)
                )
                """);
        jdbc.execute("""
                CREATE TABLE novel_narration_scripts (
                    novel_id INTEGER NOT NULL,
                    lang TEXT NOT NULL,
                    cast_id INTEGER NOT NULL,
                    segment_size INTEGER NOT NULL,
                    analyzed_time INTEGER NOT NULL,
                    script_json TEXT NOT NULL,
                    PRIMARY KEY (novel_id, lang)
                )
                """);
    }
}
