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
                        column("file_names", "TEXT", false, null, 0),
                        column("moved", "INTEGER", false, "0", 0),
                        column("move_folder", "TEXT", false, null, 0),
                        column("move_time", "INTEGER", false, null, 0)
                ),
                List.of(
                        uniqueConstraint("time")
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
