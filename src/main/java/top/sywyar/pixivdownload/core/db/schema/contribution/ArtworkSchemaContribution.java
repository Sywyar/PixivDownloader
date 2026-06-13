package top.sywyar.pixivdownload.core.db.schema.contribution;

import top.sywyar.pixivdownload.plugin.api.schema.PathColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.util.List;

import static top.sywyar.pixivdownload.core.db.schema.SchemaSpecs.column;
import static top.sywyar.pixivdownload.core.db.schema.SchemaSpecs.explicitIndex;
import static top.sywyar.pixivdownload.core.db.schema.SchemaSpecs.uniqueConstraint;

/**
 * 插画作品域 schema 的 contribution 声明（作品主表与标签关联）。
 * 作品 meta 属核心长期事实数据，ownerPluginId 为 core（卸载投影测试：
 * 下载插件未安装时画廊等部件仍需读取这些表）。
 */
public final class ArtworkSchemaContribution {

    public static final SchemaContribution CONTRIBUTION = createContribution();

    private ArtworkSchemaContribution() {}

    private static SchemaContribution createContribution() {
        List<TableSpec> tables = List.of(
                new TableSpec(
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
                                column("deleted", "INTEGER", true, "0", 0)
                        ),
                        List.of(
                                uniqueConstraint("time"),
                                explicitIndex("idx_artworks_author_time", false, "author_id", "time"),
                                explicitIndex("idx_artworks_series_order", false, "series_id", "series_order")
                        )
                ),
                new TableSpec(
                        "artwork_tags",
                        List.of(
                                column("artwork_id", "INTEGER", true, null, 1),
                                column("tag_id", "INTEGER", true, null, 2)
                        ),
                        List.of(
                                explicitIndex("idx_artwork_tags_tag_id", false, "tag_id")
                        )
                )
        );

        List<PathColumnSpec> pathColumns = List.of(
                new PathColumnSpec("artworks", "artwork_id", List.of("folder", "move_folder"))
        );

        return new SchemaContribution(CoreSchemaContribution.OWNER_PLUGIN_ID,
                tables, List.of(), List.of(), pathColumns);
    }
}
