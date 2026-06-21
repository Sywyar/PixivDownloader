package top.sywyar.pixivdownload.plugin;

import top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema.ColumnSpec;
import top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema.DatabaseSchema;
import top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema.IndexOrigin;
import top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema.IndexSpec;
import top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema.TableSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对照基线：v1.13.0 周期 {@code ManagedDatabaseSchema.createSpec()} 的原文拷贝。
 * 用于断言 {@code DatabaseSchemaRegistry} 的合并结果与旧静态总表逐表逐列等价。
 */
final class LegacySchemaBaseline {

    private LegacySchemaBaseline() {}

    static DatabaseSchema spec() {
        LinkedHashMap<String, TableSpec> tables = new LinkedHashMap<>();

        tables.put("path_prefixes", new TableSpec(
                "path_prefixes",
                List.of(
                        column("id", "INTEGER", false, null, 1),
                        column("path", "TEXT", true, null, 0)
                ),
                List.of(
                        uniqueConstraint("path")
                )
        ));

        tables.put("artworks", new TableSpec(
                "artworks",
                List.of(
                        column("artwork_id", "INTEGER", false, null, 1),
                        column("title", "TEXT", true, null, 0),
                        column("folder", "TEXT", true, null, 0),
                        column("count", "INTEGER", true, null, 0),
                        column("extensions", "TEXT", true, null, 0),
                        column("time", "INTEGER", true, null, 0),
                        column("R18", "INTEGER", false, null, 0),
                        column("is_ai", "INTEGER", false, null, 0),
                        column("author_id", "INTEGER", false, null, 0),
                        column("description", "TEXT", false, null, 0),
                        column("file_name", "INTEGER", true, "1", 0),
                        column("file_author_name_id", "INTEGER", false, null, 0),
                        column("series_id", "INTEGER", false, null, 0),
                        column("series_order", "INTEGER", false, null, 0),
                        column("moved", "INTEGER", false, "0", 0),
                        column("move_folder", "TEXT", false, null, 0),
                        column("move_time", "INTEGER", false, null, 0),
                        column("deleted", "INTEGER", true, "0", 0),
                        column("upload_time", "INTEGER", false, null, 0),
                        column("is_original", "INTEGER", false, null, 0)
                ),
                List.of(
                        uniqueConstraint("time"),
                        explicitIndex("idx_artworks_author_time", false, "author_id", "time"),
                        explicitIndex("idx_artworks_series_order", false, "series_id", "series_order")
                )
        ));

        tables.put("file_name_templates", new TableSpec(
                "file_name_templates",
                List.of(
                        column("id", "INTEGER", false, null, 1),
                        column("template", "TEXT", true, null, 0)
                ),
                List.of(
                        uniqueConstraint("template")
                )
        ));

        tables.put("file_author_names", new TableSpec(
                "file_author_names",
                List.of(
                        column("id", "INTEGER", false, null, 1),
                        column("name", "TEXT", true, null, 0)
                ),
                List.of(
                        uniqueConstraint("name")
                )
        ));

        tables.put("statistics", new TableSpec(
                "statistics",
                List.of(
                        column("id", "INTEGER", false, null, 1),
                        column("total_artworks", "INTEGER", false, "0", 0),
                        column("total_images", "INTEGER", false, "0", 0),
                        column("total_moved", "INTEGER", false, "0", 0)
                ),
                List.of()
        ));

        tables.put("tags", new TableSpec(
                "tags",
                List.of(
                        column("tag_id", "INTEGER", false, null, 1),
                        column("name", "TEXT", true, null, 0),
                        column("translated_name", "TEXT", false, null, 0)
                ),
                List.of(
                        uniqueConstraint("name")
                )
        ));

        tables.put("artwork_tags", new TableSpec(
                "artwork_tags",
                List.of(
                        column("artwork_id", "INTEGER", true, null, 1),
                        column("tag_id", "INTEGER", true, null, 2)
                ),
                List.of(
                        explicitIndex("idx_artwork_tags_tag_id", false, "tag_id")
                )
        ));

        tables.put("authors", new TableSpec(
                "authors",
                List.of(
                        column("author_id", "INTEGER", false, null, 1),
                        column("name", "TEXT", true, null, 0),
                        column("updated_time", "INTEGER", true, null, 0)
                ),
                List.of()
        ));

        tables.put("manga_series", new TableSpec(
                "manga_series",
                List.of(
                        column("series_id", "INTEGER", false, null, 1),
                        column("title", "TEXT", true, null, 0),
                        column("author_id", "INTEGER", false, null, 0),
                        column("updated_time", "INTEGER", true, null, 0),
                        column("description", "TEXT", false, null, 0),
                        column("cover_ext", "TEXT", false, null, 0),
                        column("cover_folder", "TEXT", false, null, 0)
                ),
                List.of()
        ));

        tables.put("collections", new TableSpec(
                "collections",
                List.of(
                        column("id", "INTEGER", false, null, 1),
                        column("name", "TEXT", true, null, 0),
                        column("icon_ext", "TEXT", false, null, 0),
                        column("download_root", "TEXT", false, null, 0),
                        column("sort_order", "INTEGER", false, "0", 0),
                        column("created_time", "INTEGER", true, null, 0)
                ),
                List.of()
        ));

        tables.put("artwork_collections", new TableSpec(
                "artwork_collections",
                List.of(
                        column("collection_id", "INTEGER", true, null, 1),
                        column("artwork_id", "INTEGER", true, null, 2),
                        column("added_time", "INTEGER", true, null, 0)
                ),
                List.of(
                        explicitIndex("idx_artwork_collections_artwork", false, "artwork_id")
                )
        ));

        tables.put("artwork_image_hashes", new TableSpec(
                "artwork_image_hashes",
                List.of(
                        column("artwork_id", "INTEGER", true, null, 1),
                        column("page", "INTEGER", true, null, 2),
                        column("ext", "TEXT", true, null, 0),
                        column("dhash", "INTEGER", true, null, 0),
                        column("ahash", "INTEGER", false, null, 0),
                        column("created_time", "INTEGER", true, null, 0)
                ),
                List.of(
                        explicitIndex("idx_artwork_image_hashes_dhash", false, "dhash")
                )
        ));

        tables.put("guest_invites", new TableSpec(
                "guest_invites",
                List.of(
                        column("id", "INTEGER", false, null, 1),
                        column("code", "TEXT", true, null, 0),
                        column("name", "TEXT", true, null, 0),
                        column("expire_time", "INTEGER", false, null, 0),
                        column("allow_sfw", "INTEGER", true, "1", 0),
                        column("allow_r18", "INTEGER", true, "0", 0),
                        column("allow_r18g", "INTEGER", true, "0", 0),
                        column("tag_unrestricted", "INTEGER", true, "1", 0),
                        column("author_unrestricted", "INTEGER", true, "1", 0),
                        column("novel_tag_unrestricted", "INTEGER", false, null, 0),
                        column("novel_author_unrestricted", "INTEGER", false, null, 0),
                        column("created_time", "INTEGER", true, null, 0),
                        column("paused", "INTEGER", true, "0", 0),
                        column("revoked", "INTEGER", true, "0", 0),
                        column("first_used_time", "INTEGER", false, null, 0),
                        column("last_used_time", "INTEGER", false, null, 0),
                        column("total_request_count", "INTEGER", true, "0", 0)
                ),
                List.of(
                        uniqueConstraint("code"),
                        explicitIndex("idx_guest_invites_code", false, "code")
                )
        ));

        tables.put("guest_invite_tags", new TableSpec(
                "guest_invite_tags",
                List.of(
                        column("invite_id", "INTEGER", true, null, 1),
                        column("tag_id", "INTEGER", true, null, 2)
                ),
                List.of()
        ));

        tables.put("guest_invite_authors", new TableSpec(
                "guest_invite_authors",
                List.of(
                        column("invite_id", "INTEGER", true, null, 1),
                        column("author_id", "INTEGER", true, null, 2)
                ),
                List.of()
        ));

        tables.put("guest_invite_novel_tags", new TableSpec(
                "guest_invite_novel_tags",
                List.of(
                        column("invite_id", "INTEGER", true, null, 1),
                        column("tag_id", "INTEGER", true, null, 2)
                ),
                List.of()
        ));

        tables.put("guest_invite_novel_authors", new TableSpec(
                "guest_invite_novel_authors",
                List.of(
                        column("invite_id", "INTEGER", true, null, 1),
                        column("author_id", "INTEGER", true, null, 2)
                ),
                List.of()
        ));

        tables.put("guest_invite_access_stats", new TableSpec(
                "guest_invite_access_stats",
                List.of(
                        column("invite_id", "INTEGER", true, null, 1),
                        column("bucket_hour", "INTEGER", true, null, 2),
                        column("request_count", "INTEGER", true, "0", 0)
                ),
                List.of(
                        explicitIndex("idx_guest_invite_access_stats_bucket", false, "bucket_hour")
                )
        ));

        tables.put("novels", new TableSpec(
                "novels",
                List.of(
                        column("novel_id", "INTEGER", false, null, 1),
                        column("title", "TEXT", true, null, 0),
                        column("folder", "TEXT", true, null, 0),
                        column("count", "INTEGER", true, null, 0),
                        column("extensions", "TEXT", true, null, 0),
                        column("time", "INTEGER", true, null, 0),
                        column("R18", "INTEGER", false, null, 0),
                        column("is_ai", "INTEGER", false, null, 0),
                        column("author_id", "INTEGER", false, null, 0),
                        column("description", "TEXT", false, null, 0),
                        column("file_name", "INTEGER", true, "1", 0),
                        column("file_author_name_id", "INTEGER", false, null, 0),
                        column("series_id", "INTEGER", false, null, 0),
                        column("series_order", "INTEGER", false, null, 0),
                        column("word_count", "INTEGER", false, null, 0),
                        column("text_length", "INTEGER", false, null, 0),
                        column("reading_time_seconds", "INTEGER", false, null, 0),
                        column("page_count", "INTEGER", false, null, 0),
                        column("is_original", "INTEGER", false, null, 0),
                        column("x_language", "TEXT", false, null, 0),
                        column("raw_content", "TEXT", false, null, 0),
                        column("cover_ext", "TEXT", false, null, 0),
                        column("deleted", "INTEGER", true, "0", 0),
                        column("upload_time", "INTEGER", false, null, 0)
                ),
                List.of(
                        uniqueConstraint("time"),
                        explicitIndex("idx_novels_author_id", false, "author_id"),
                        explicitIndex("idx_novels_series_order", false, "series_id", "series_order")
                )
        ));

        tables.put("novel_series", new TableSpec(
                "novel_series",
                List.of(
                        column("series_id", "INTEGER", false, null, 1),
                        column("title", "TEXT", true, null, 0),
                        column("author_id", "INTEGER", false, null, 0),
                        column("updated_time", "INTEGER", true, null, 0),
                        column("description", "TEXT", false, null, 0),
                        column("cover_ext", "TEXT", false, null, 0),
                        column("cover_folder", "TEXT", false, null, 0)
                ),
                List.of()
        ));

        tables.put("novel_tags", new TableSpec(
                "novel_tags",
                List.of(
                        column("novel_id", "INTEGER", true, null, 1),
                        column("tag_id", "INTEGER", true, null, 2)
                ),
                List.of(
                        explicitIndex("idx_novel_tags_tag_id", false, "tag_id")
                )
        ));

        tables.put("novel_series_tags", new TableSpec(
                "novel_series_tags",
                List.of(
                        column("series_id", "INTEGER", true, null, 1),
                        column("tag_id", "INTEGER", true, null, 2)
                ),
                List.of(
                        explicitIndex("idx_novel_series_tags_tag_id", false, "tag_id")
                )
        ));

        tables.put("novel_collections", new TableSpec(
                "novel_collections",
                List.of(
                        column("collection_id", "INTEGER", true, null, 1),
                        column("novel_id", "INTEGER", true, null, 2),
                        column("added_time", "INTEGER", true, null, 0)
                ),
                List.of(
                        explicitIndex("idx_novel_collections_novel", false, "novel_id")
                )
        ));

        tables.put("novel_images", new TableSpec(
                "novel_images",
                List.of(
                        column("novel_id", "INTEGER", true, null, 1),
                        column("image_id", "TEXT", true, null, 2),
                        column("ext", "TEXT", true, null, 0)
                ),
                List.of()
        ));

        tables.put("novel_translations", new TableSpec(
                "novel_translations",
                List.of(
                        column("novel_id", "INTEGER", true, null, 1),
                        column("lang_code", "TEXT", true, null, 2),
                        column("raw_content", "TEXT", true, null, 0),
                        column("title", "TEXT", false, null, 0),
                        column("description", "TEXT", false, null, 0),
                        column("created_time", "INTEGER", true, null, 0)
                ),
                List.of()
        ));

        tables.put("novel_series_title_translations", new TableSpec(
                "novel_series_title_translations",
                List.of(
                        column("series_id", "INTEGER", true, null, 1),
                        column("lang_code", "TEXT", true, null, 2),
                        column("title", "TEXT", true, null, 0),
                        column("description", "TEXT", false, null, 0),
                        column("created_time", "INTEGER", true, null, 0)
                ),
                List.of()
        ));

        tables.put("novel_glossaries", new TableSpec(
                "novel_glossaries",
                List.of(
                        column("id", "INTEGER", false, null, 1),
                        column("name", "TEXT", true, null, 0),
                        column("series_id", "INTEGER", false, null, 0),
                        column("novel_id", "INTEGER", false, null, 0),
                        column("created_time", "INTEGER", true, null, 0),
                        column("updated_time", "INTEGER", true, null, 0)
                ),
                List.of(
                        explicitIndex("idx_novel_glossaries_series", false, "series_id"),
                        explicitIndex("idx_novel_glossaries_novel", false, "novel_id")
                )
        ));

        tables.put("novel_glossary_entries", new TableSpec(
                "novel_glossary_entries",
                List.of(
                        column("glossary_id", "INTEGER", true, null, 1),
                        column("source", "TEXT", true, null, 2),
                        column("lang_code", "TEXT", true, null, 3),
                        column("target", "TEXT", true, null, 0),
                        column("created_time", "INTEGER", true, null, 0)
                ),
                List.of()
        ));

        tables.put("novel_narration_casts", new TableSpec(
                "novel_narration_casts",
                List.of(
                        column("id", "INTEGER", false, null, 1),
                        column("name", "TEXT", true, null, 0),
                        column("series_id", "INTEGER", false, null, 0),
                        column("novel_id", "INTEGER", false, null, 0),
                        column("created_time", "INTEGER", true, null, 0),
                        column("updated_time", "INTEGER", true, null, 0)
                ),
                List.of(
                        explicitIndex("idx_novel_narration_casts_series", false, "series_id"),
                        explicitIndex("idx_novel_narration_casts_novel", false, "novel_id")
                )
        ));

        tables.put("novel_narration_voices", new TableSpec(
                "novel_narration_voices",
                List.of(
                        column("cast_id", "INTEGER", true, null, 1),
                        column("character_id", "INTEGER", true, null, 2),
                        column("name", "TEXT", true, null, 0),
                        column("gender", "TEXT", false, null, 0),
                        column("age", "TEXT", false, null, 0),
                        column("control_instruction", "TEXT", true, null, 0),
                        column("edited_by_user", "INTEGER", true, "0", 0),
                        column("ref_audio_ext", "TEXT", false, null, 0),
                        column("ref_audio_text", "TEXT", false, null, 0),
                        column("ref_audio_source", "TEXT", false, null, 0),
                        column("ref_audio_time", "INTEGER", false, null, 0),
                        column("created_time", "INTEGER", true, null, 0)
                ),
                List.of()
        ));

        tables.put("novel_narration_scripts", new TableSpec(
                "novel_narration_scripts",
                List.of(
                        column("novel_id", "INTEGER", true, null, 1),
                        column("lang", "TEXT", true, null, 2),
                        column("cast_id", "INTEGER", true, null, 0),
                        column("segment_size", "INTEGER", true, null, 0),
                        column("analyzed_time", "INTEGER", true, null, 0),
                        column("script_json", "TEXT", true, null, 0)
                ),
                List.of()
        ));

        tables.put("scheduled_tasks", new TableSpec(
                "scheduled_tasks",
                List.of(
                        column("id", "INTEGER", false, null, 1),
                        column("name", "TEXT", true, null, 0),
                        column("enabled", "INTEGER", true, "1", 0),
                        column("type", "TEXT", true, null, 0),
                        column("params_json", "TEXT", true, null, 0),
                        column("trigger_kind", "TEXT", true, null, 0),
                        column("interval_minutes", "INTEGER", false, null, 0),
                        column("cron_expr", "TEXT", false, null, 0),
                        column("cookie_mode", "TEXT", true, null, 0),
                        column("cookie_snapshot", "TEXT", false, null, 0),
                        column("proxy_snapshot", "TEXT", false, null, 0),
                        column("next_run_time", "INTEGER", false, null, 0),
                        column("last_run_time", "INTEGER", false, null, 0),
                        column("last_status", "TEXT", false, null, 0),
                        column("last_message", "TEXT", false, null, 0),
                        column("watermark_id", "INTEGER", false, null, 0),
                        column("run_started_time", "INTEGER", false, null, 0),
                        column("account_id", "TEXT", false, null, 0),
                        column("ack_warning_time", "INTEGER", false, null, 0),
                        column("pending_retry_armed", "INTEGER", false, "0", 0),
                        column("created_time", "INTEGER", true, null, 0)
                ),
                List.of(
                        explicitIndex("idx_scheduled_tasks_next_run", false, "next_run_time"),
                        explicitIndex("idx_scheduled_tasks_account", false, "account_id")
                )
        ));

        tables.put("scheduled_task_pending", new TableSpec(
                "scheduled_task_pending",
                List.of(
                        column("task_id", "INTEGER", true, null, 1),
                        column("work_id", "INTEGER", true, null, 2),
                        column("reason", "TEXT", false, null, 0),
                        column("attempts", "INTEGER", false, "0", 0),
                        column("first_seen_time", "INTEGER", false, null, 0),
                        column("last_attempt_time", "INTEGER", false, null, 0)
                ),
                List.of()
        ));

        return new DatabaseSchema(Map.copyOf(tables));
    }

    private static ColumnSpec column(String name,
                                     String type,
                                     boolean notNull,
                                     String defaultValue,
                                     int primaryKeyPosition) {
        return new ColumnSpec(name, type, notNull, defaultValue, primaryKeyPosition);
    }

    private static IndexSpec explicitIndex(String name, boolean unique, String... columns) {
        return new IndexSpec(name, IndexOrigin.CREATE_INDEX, unique, List.of(columns));
    }

    private static IndexSpec uniqueConstraint(String... columns) {
        return new IndexSpec(null, IndexOrigin.UNIQUE_CONSTRAINT, true, List.of(columns));
    }
}
