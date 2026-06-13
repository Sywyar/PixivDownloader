package top.sywyar.pixivdownload.core.db.schema;

import java.util.List;

/**
 * P3 之前各 {@code *Mapper} 逐表 DDL 的<b>原文拷贝</b>（建表 + 显式索引，按原 init() 调用顺序），
 * 作为 {@code DatabaseInitializerTest} 双路建库对照的基线：同一受管 schema 分别用本基线与
 * {@code DatabaseInitializer} 生成的 DDL 各建一个全新库，逐表对比结构（列序 / 类型 / NOT NULL /
 * 默认值 / 主键 / 索引）与 sqlite_master 中的 AUTOINCREMENT / CHECK 形态，必须完全等价。
 * <p>
 * 与 {@code plugin.LegacySchemaBaseline} 同属过渡守护：schema 改动需同步本基线，直至对照测试退役。
 */
final class LegacyDdlBaseline {

    private LegacyDdlBaseline() {}

    static final List<String> STATEMENTS = List.of(
            // ── PathPrefixMapper ───────────────────────────────────────────────
            "CREATE TABLE IF NOT EXISTS path_prefixes ("
                    + "id INTEGER PRIMARY KEY,"
                    + "path TEXT NOT NULL UNIQUE)",
            // ── PixivMapper（原 PixivDatabase.init() 顺序）────────────────────
            "CREATE TABLE IF NOT EXISTS file_author_names ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "name TEXT NOT NULL UNIQUE)",
            "CREATE TABLE IF NOT EXISTS file_name_templates ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "template TEXT NOT NULL UNIQUE)",
            "CREATE TABLE IF NOT EXISTS artworks ("
                    + "artwork_id INTEGER PRIMARY KEY,"
                    + "title TEXT NOT NULL,"
                    + "folder TEXT NOT NULL,"
                    + "count INTEGER NOT NULL,"
                    + "extensions TEXT NOT NULL,"
                    + "time INTEGER NOT NULL UNIQUE,"
                    + "\"R18\" INTEGER DEFAULT NULL,"
                    + "is_ai INTEGER DEFAULT NULL,"
                    + "author_id INTEGER DEFAULT NULL,"
                    + "description TEXT DEFAULT NULL,"
                    + "file_name INTEGER NOT NULL DEFAULT 1,"
                    + "file_author_name_id INTEGER,"
                    + "series_id INTEGER DEFAULT NULL,"
                    + "series_order INTEGER DEFAULT NULL,"
                    + "moved INTEGER DEFAULT 0,"
                    + "move_folder TEXT,"
                    + "move_time INTEGER,"
                    + "deleted INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS statistics ("
                    + "id INTEGER PRIMARY KEY CHECK (id = 1),"
                    + "total_artworks INTEGER DEFAULT 0,"
                    + "total_images INTEGER DEFAULT 0,"
                    + "total_moved INTEGER DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS tags ("
                    + "tag_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "name TEXT NOT NULL UNIQUE,"
                    + "translated_name TEXT)",
            "CREATE TABLE IF NOT EXISTS artwork_tags ("
                    + "artwork_id INTEGER NOT NULL,"
                    + "tag_id INTEGER NOT NULL,"
                    + "PRIMARY KEY (artwork_id, tag_id))",
            "CREATE INDEX IF NOT EXISTS idx_artwork_tags_tag_id ON artwork_tags(tag_id)",
            "CREATE TABLE IF NOT EXISTS artwork_image_hashes ("
                    + "artwork_id INTEGER NOT NULL,"
                    + "page INTEGER NOT NULL,"
                    + "ext TEXT NOT NULL,"
                    + "dhash INTEGER NOT NULL,"
                    + "ahash INTEGER,"
                    + "created_time INTEGER NOT NULL,"
                    + "PRIMARY KEY (artwork_id, page))",
            "CREATE INDEX IF NOT EXISTS idx_artwork_image_hashes_dhash ON artwork_image_hashes(dhash)",
            "CREATE INDEX IF NOT EXISTS idx_artworks_author_time ON artworks(author_id, time)",
            "CREATE INDEX IF NOT EXISTS idx_artworks_series_order ON artworks(series_id, series_order)",
            // ── AuthorMapper ───────────────────────────────────────────────────
            "CREATE TABLE IF NOT EXISTS authors ("
                    + "author_id INTEGER PRIMARY KEY,"
                    + "name TEXT NOT NULL,"
                    + "updated_time INTEGER NOT NULL)",
            // ── MangaSeriesMapper ──────────────────────────────────────────────
            "CREATE TABLE IF NOT EXISTS manga_series ("
                    + "series_id INTEGER PRIMARY KEY,"
                    + "title TEXT NOT NULL,"
                    + "author_id INTEGER,"
                    + "updated_time INTEGER NOT NULL,"
                    + "description TEXT DEFAULT NULL,"
                    + "cover_ext TEXT DEFAULT NULL,"
                    + "cover_folder TEXT DEFAULT NULL)",
            // ── CollectionMapper ───────────────────────────────────────────────
            "CREATE TABLE IF NOT EXISTS collections ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "name TEXT NOT NULL,"
                    + "icon_ext TEXT,"
                    + "download_root TEXT,"
                    + "sort_order INTEGER DEFAULT 0,"
                    + "created_time INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS artwork_collections ("
                    + "collection_id INTEGER NOT NULL,"
                    + "artwork_id INTEGER NOT NULL,"
                    + "added_time INTEGER NOT NULL,"
                    + "PRIMARY KEY (collection_id, artwork_id))",
            "CREATE INDEX IF NOT EXISTS idx_artwork_collections_artwork"
                    + " ON artwork_collections(artwork_id)",
            // ── GuestInviteMapper ──────────────────────────────────────────────
            "CREATE TABLE IF NOT EXISTS guest_invites ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "code TEXT NOT NULL UNIQUE,"
                    + "name TEXT NOT NULL,"
                    + "expire_time INTEGER,"
                    + "allow_sfw INTEGER NOT NULL DEFAULT 1,"
                    + "allow_r18 INTEGER NOT NULL DEFAULT 0,"
                    + "allow_r18g INTEGER NOT NULL DEFAULT 0,"
                    + "tag_unrestricted INTEGER NOT NULL DEFAULT 1,"
                    + "author_unrestricted INTEGER NOT NULL DEFAULT 1,"
                    + "novel_tag_unrestricted INTEGER,"
                    + "novel_author_unrestricted INTEGER,"
                    + "created_time INTEGER NOT NULL,"
                    + "paused INTEGER NOT NULL DEFAULT 0,"
                    + "revoked INTEGER NOT NULL DEFAULT 0,"
                    + "first_used_time INTEGER,"
                    + "last_used_time INTEGER,"
                    + "total_request_count INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX IF NOT EXISTS idx_guest_invites_code ON guest_invites(code)",
            "CREATE TABLE IF NOT EXISTS guest_invite_tags ("
                    + "invite_id INTEGER NOT NULL,"
                    + "tag_id INTEGER NOT NULL,"
                    + "PRIMARY KEY (invite_id, tag_id))",
            "CREATE TABLE IF NOT EXISTS guest_invite_authors ("
                    + "invite_id INTEGER NOT NULL,"
                    + "author_id INTEGER NOT NULL,"
                    + "PRIMARY KEY (invite_id, author_id))",
            "CREATE TABLE IF NOT EXISTS guest_invite_novel_tags ("
                    + "invite_id INTEGER NOT NULL,"
                    + "tag_id INTEGER NOT NULL,"
                    + "PRIMARY KEY (invite_id, tag_id))",
            "CREATE TABLE IF NOT EXISTS guest_invite_novel_authors ("
                    + "invite_id INTEGER NOT NULL,"
                    + "author_id INTEGER NOT NULL,"
                    + "PRIMARY KEY (invite_id, author_id))",
            "CREATE TABLE IF NOT EXISTS guest_invite_access_stats ("
                    + "invite_id INTEGER NOT NULL,"
                    + "bucket_hour INTEGER NOT NULL,"
                    + "request_count INTEGER NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (invite_id, bucket_hour))",
            "CREATE INDEX IF NOT EXISTS idx_guest_invite_access_stats_bucket"
                    + " ON guest_invite_access_stats(bucket_hour)",
            // ── NovelMapper（原 NovelDatabase.init() 顺序；novels_fts 虚拟表不入受管 schema）──
            "CREATE TABLE IF NOT EXISTS novels ("
                    + "novel_id INTEGER PRIMARY KEY,"
                    + "title TEXT NOT NULL,"
                    + "folder TEXT NOT NULL,"
                    + "count INTEGER NOT NULL,"
                    + "extensions TEXT NOT NULL,"
                    + "time INTEGER NOT NULL UNIQUE,"
                    + "\"R18\" INTEGER DEFAULT NULL,"
                    + "is_ai INTEGER DEFAULT NULL,"
                    + "author_id INTEGER DEFAULT NULL,"
                    + "description TEXT DEFAULT NULL,"
                    + "file_name INTEGER NOT NULL DEFAULT 1,"
                    + "file_author_name_id INTEGER,"
                    + "series_id INTEGER DEFAULT NULL,"
                    + "series_order INTEGER DEFAULT NULL,"
                    + "word_count INTEGER DEFAULT NULL,"
                    + "text_length INTEGER DEFAULT NULL,"
                    + "reading_time_seconds INTEGER DEFAULT NULL,"
                    + "page_count INTEGER DEFAULT NULL,"
                    + "is_original INTEGER DEFAULT NULL,"
                    + "x_language TEXT DEFAULT NULL,"
                    + "raw_content TEXT DEFAULT NULL,"
                    + "cover_ext TEXT DEFAULT NULL,"
                    + "deleted INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX IF NOT EXISTS idx_novels_author_id ON novels(author_id)",
            "CREATE INDEX IF NOT EXISTS idx_novels_series_order ON novels(series_id, series_order)",
            "CREATE TABLE IF NOT EXISTS novel_series ("
                    + "series_id INTEGER PRIMARY KEY,"
                    + "title TEXT NOT NULL,"
                    + "author_id INTEGER,"
                    + "updated_time INTEGER NOT NULL,"
                    + "description TEXT DEFAULT NULL,"
                    + "cover_ext TEXT DEFAULT NULL,"
                    + "cover_folder TEXT DEFAULT NULL)",
            "CREATE TABLE IF NOT EXISTS novel_tags ("
                    + "novel_id INTEGER NOT NULL,"
                    + "tag_id INTEGER NOT NULL,"
                    + "PRIMARY KEY (novel_id, tag_id))",
            "CREATE INDEX IF NOT EXISTS idx_novel_tags_tag_id ON novel_tags(tag_id)",
            "CREATE TABLE IF NOT EXISTS novel_series_tags ("
                    + "series_id INTEGER NOT NULL,"
                    + "tag_id INTEGER NOT NULL,"
                    + "PRIMARY KEY (series_id, tag_id))",
            "CREATE INDEX IF NOT EXISTS idx_novel_series_tags_tag_id ON novel_series_tags(tag_id)",
            "CREATE TABLE IF NOT EXISTS novel_collections ("
                    + "collection_id INTEGER NOT NULL,"
                    + "novel_id INTEGER NOT NULL,"
                    + "added_time INTEGER NOT NULL,"
                    + "PRIMARY KEY (collection_id, novel_id))",
            "CREATE INDEX IF NOT EXISTS idx_novel_collections_novel ON novel_collections(novel_id)",
            "CREATE TABLE IF NOT EXISTS novel_images ("
                    + "novel_id INTEGER NOT NULL,"
                    + "image_id TEXT NOT NULL,"
                    + "ext TEXT NOT NULL,"
                    + "PRIMARY KEY (novel_id, image_id))",
            "CREATE TABLE IF NOT EXISTS novel_translations ("
                    + "novel_id INTEGER NOT NULL,"
                    + "lang_code TEXT NOT NULL,"
                    + "raw_content TEXT NOT NULL,"
                    + "title TEXT DEFAULT NULL,"
                    + "description TEXT DEFAULT NULL,"
                    + "created_time INTEGER NOT NULL,"
                    + "PRIMARY KEY (novel_id, lang_code))",
            "CREATE TABLE IF NOT EXISTS novel_series_title_translations ("
                    + "series_id INTEGER NOT NULL,"
                    + "lang_code TEXT NOT NULL,"
                    + "title TEXT NOT NULL,"
                    + "description TEXT DEFAULT NULL,"
                    + "created_time INTEGER NOT NULL,"
                    + "PRIMARY KEY (series_id, lang_code))",
            "CREATE TABLE IF NOT EXISTS novel_glossaries ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "name TEXT NOT NULL,"
                    + "series_id INTEGER DEFAULT NULL,"
                    + "novel_id INTEGER DEFAULT NULL,"
                    + "created_time INTEGER NOT NULL,"
                    + "updated_time INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_novel_glossaries_series ON novel_glossaries(series_id)",
            "CREATE INDEX IF NOT EXISTS idx_novel_glossaries_novel ON novel_glossaries(novel_id)",
            "CREATE TABLE IF NOT EXISTS novel_glossary_entries ("
                    + "glossary_id INTEGER NOT NULL,"
                    + "source TEXT NOT NULL,"
                    + "lang_code TEXT NOT NULL,"
                    + "target TEXT NOT NULL,"
                    + "created_time INTEGER NOT NULL,"
                    + "PRIMARY KEY (glossary_id, source, lang_code))",
            "CREATE TABLE IF NOT EXISTS novel_narration_casts ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "name TEXT NOT NULL,"
                    + "series_id INTEGER DEFAULT NULL,"
                    + "novel_id INTEGER DEFAULT NULL,"
                    + "created_time INTEGER NOT NULL,"
                    + "updated_time INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_novel_narration_casts_series ON novel_narration_casts(series_id)",
            "CREATE INDEX IF NOT EXISTS idx_novel_narration_casts_novel ON novel_narration_casts(novel_id)",
            "CREATE TABLE IF NOT EXISTS novel_narration_voices ("
                    + "cast_id INTEGER NOT NULL,"
                    + "character_id INTEGER NOT NULL,"
                    + "name TEXT NOT NULL,"
                    + "gender TEXT,"
                    + "age TEXT,"
                    + "control_instruction TEXT NOT NULL,"
                    + "edited_by_user INTEGER NOT NULL DEFAULT 0,"
                    + "ref_audio_ext TEXT DEFAULT NULL,"
                    + "ref_audio_text TEXT DEFAULT NULL,"
                    + "ref_audio_source TEXT DEFAULT NULL,"
                    + "ref_audio_time INTEGER DEFAULT NULL,"
                    + "created_time INTEGER NOT NULL,"
                    + "PRIMARY KEY (cast_id, character_id))",
            "CREATE TABLE IF NOT EXISTS novel_narration_scripts ("
                    + "novel_id INTEGER NOT NULL,"
                    + "lang TEXT NOT NULL,"
                    + "cast_id INTEGER NOT NULL,"
                    + "segment_size INTEGER NOT NULL,"
                    + "analyzed_time INTEGER NOT NULL,"
                    + "script_json TEXT NOT NULL,"
                    + "PRIMARY KEY (novel_id, lang))",
            // ── ScheduledTaskMapper ────────────────────────────────────────────
            "CREATE TABLE IF NOT EXISTS scheduled_tasks ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "name TEXT NOT NULL,"
                    + "enabled INTEGER NOT NULL DEFAULT 1,"
                    + "type TEXT NOT NULL,"
                    + "params_json TEXT NOT NULL,"
                    + "trigger_kind TEXT NOT NULL,"
                    + "interval_minutes INTEGER,"
                    + "cron_expr TEXT,"
                    + "cookie_mode TEXT NOT NULL,"
                    + "cookie_snapshot TEXT,"
                    + "proxy_snapshot TEXT,"
                    + "next_run_time INTEGER,"
                    + "last_run_time INTEGER,"
                    + "last_status TEXT,"
                    + "last_message TEXT,"
                    + "watermark_id INTEGER,"
                    + "run_started_time INTEGER,"
                    + "account_id TEXT,"
                    + "ack_warning_time INTEGER,"
                    + "pending_retry_armed INTEGER DEFAULT 0,"
                    + "created_time INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_scheduled_tasks_next_run ON scheduled_tasks(next_run_time)",
            "CREATE INDEX IF NOT EXISTS idx_scheduled_tasks_account ON scheduled_tasks(account_id)",
            "CREATE TABLE IF NOT EXISTS scheduled_task_pending ("
                    + "task_id INTEGER NOT NULL,"
                    + "work_id INTEGER NOT NULL,"
                    + "reason TEXT,"
                    + "attempts INTEGER DEFAULT 0,"
                    + "first_seen_time INTEGER,"
                    + "last_attempt_time INTEGER,"
                    + "PRIMARY KEY(task_id, work_id))"
    );
}
