package top.sywyar.pixivdownload.download.db;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Canonical schema definition for the managed SQLite database.
 * Startup checks compare the on-disk schema against this constant catalog.
 */
public final class ManagedDatabaseSchema {

    public static final DatabaseSchema SPEC = createSpec();

    private ManagedDatabaseSchema() {}

    private static DatabaseSchema createSpec() {
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
                        column("move_time", "INTEGER", false, null, 0)
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
                        column("cover_ext", "TEXT", false, null, 0)
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
                        column("next_run_time", "INTEGER", false, null, 0),
                        column("last_run_time", "INTEGER", false, null, 0),
                        column("last_status", "TEXT", false, null, 0),
                        column("created_time", "INTEGER", true, null, 0)
                ),
                List.of(
                        explicitIndex("idx_scheduled_tasks_next_run", false, "next_run_time")
                )
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

    static String normalizeIdentifier(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    static String normalizeType(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    static String normalizeDefault(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        while (normalized.length() > 1 && normalized.startsWith("(") && normalized.endsWith(")")) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        if (normalized.isEmpty() || "NULL".equalsIgnoreCase(normalized)) {
            return null;
        }
        return normalized.replaceAll("\\s+", " ");
    }

    public record DatabaseSchema(Map<String, TableSpec> tables) {
        public DatabaseSchema {
            tables = Map.copyOf(tables);
        }
    }

    public record TableSpec(String name, List<ColumnSpec> columns, List<IndexSpec> indexes) {
        public TableSpec {
            name = normalizeIdentifier(name);
            columns = List.copyOf(columns);
            indexes = List.copyOf(indexes);
        }
    }

    public record ColumnSpec(String name,
                             String type,
                             boolean notNull,
                             String defaultValue,
                             int primaryKeyPosition) {
        public ColumnSpec {
            name = normalizeIdentifier(name);
            type = normalizeType(type);
            defaultValue = normalizeDefault(defaultValue);
        }
    }

    public enum IndexOrigin {
        CREATE_INDEX,
        UNIQUE_CONSTRAINT
    }

    public record IndexSpec(String name,
                            IndexOrigin origin,
                            boolean unique,
                            List<String> columns) {
        public IndexSpec {
            name = origin == IndexOrigin.CREATE_INDEX ? normalizeIdentifier(name) : null;
            columns = columns.stream()
                    .map(ManagedDatabaseSchema::normalizeIdentifier)
                    .toList();
        }

        public String signature() {
            StringBuilder builder = new StringBuilder(origin.name())
                    .append('|')
                    .append(unique)
                    .append('|')
                    .append(String.join(",", columns));
            if (origin == IndexOrigin.CREATE_INDEX) {
                builder.append('|').append(name);
            }
            return builder.toString();
        }
    }
}
