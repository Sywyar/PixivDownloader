package top.sywyar.pixivdownload.douyin.db.history;

import top.sywyar.pixivdownload.douyin.DouyinPlugin;
import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.IndexOrigin;
import top.sywyar.pixivdownload.plugin.api.schema.IndexSpec;
import top.sywyar.pixivdownload.plugin.api.schema.PathColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.util.List;

public final class DouyinSchemaContribution {

    public static final SchemaContribution CONTRIBUTION = createContribution();

    private DouyinSchemaContribution() {
    }

    private static SchemaContribution createContribution() {
        List<TableSpec> tables = List.of(
                new TableSpec(
                        "douyin_works",
                        List.of(
                                column("work_id", "TEXT", false, null, 1),
                                column("title", "TEXT", true, null, 0),
                                column("folder", "TEXT", true, null, 0),
                                column("count", "INTEGER", true, null, 0),
                                column("extensions", "TEXT", true, null, 0),
                                column("time", "INTEGER", true, null, 0),
                                column("deleted", "INTEGER", true, "0", 0),
                                column("kind", "TEXT", true, null, 0),
                                column("source_url", "TEXT", false, null, 0),
                                column("canonical_url", "TEXT", false, null, 0),
                                column("thumbnail_url", "TEXT", false, null, 0),
                                column("author_id", "TEXT", false, null, 0),
                                column("author_name", "TEXT", false, null, 0),
                                column("description", "TEXT", false, null, 0),
                                column("item_title", "TEXT", false, null, 0),
                                column("caption", "TEXT", false, null, 0),
                                column("publish_time", "INTEGER", false, null, 0),
                                column("collection_id", "TEXT", false, null, 0),
                                column("collection_title", "TEXT", false, null, 0),
                                column("collection_order", "INTEGER", false, null, 0)
                        ),
                        List.of(
                                uniqueConstraint("time"),
                                explicitIndex("idx_douyin_works_author_time", false, "author_id", "time"),
                                explicitIndex("idx_douyin_works_collection_order", false,
                                        "collection_id", "collection_order")
                        )
                ),
                new TableSpec(
                        "douyin_work_files",
                        List.of(
                                column("work_id", "TEXT", true, null, 1),
                                column("file_index", "INTEGER", true, null, 2),
                                column("media_id", "TEXT", false, null, 0),
                                column("media_type", "TEXT", true, null, 0),
                                column("file_name", "TEXT", true, null, 0),
                                column("extension", "TEXT", true, null, 0),
                                column("bytes", "INTEGER", false, null, 0),
                                column("content_type", "TEXT", false, null, 0),
                                column("created_time", "INTEGER", true, null, 0)
                        ),
                        List.of(
                                explicitIndex("idx_douyin_work_files_work_id", false, "work_id")
                        )
                )
        );

        List<PathColumnSpec> pathColumns = List.of(
                new PathColumnSpec("douyin_works", "work_id", List.of("folder"))
        );

        return new SchemaContribution(DouyinPlugin.ID, tables, List.of(), List.of(), pathColumns);
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
