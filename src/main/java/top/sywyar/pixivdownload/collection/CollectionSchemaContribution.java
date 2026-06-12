package top.sywyar.pixivdownload.collection;

import top.sywyar.pixivdownload.core.db.CoreSchemaContribution;
import top.sywyar.pixivdownload.plugin.api.PathColumnSpec;
import top.sywyar.pixivdownload.plugin.api.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.TableSpec;

import java.util.List;

import static top.sywyar.pixivdownload.core.db.SchemaSpecs.column;
import static top.sywyar.pixivdownload.core.db.SchemaSpecs.explicitIndex;

/**
 * 收藏夹域 schema 的 contribution 声明（收藏夹与插画关联；小说关联表
 * {@code novel_collections} 的 DDL 随 NovelDatabase，声明在小说域 contribution）。
 * 收藏夹横跨插画与小说，属核心共享服务。
 */
public final class CollectionSchemaContribution {

    public static final SchemaContribution CONTRIBUTION = createContribution();

    private CollectionSchemaContribution() {}

    private static SchemaContribution createContribution() {
        List<TableSpec> tables = List.of(
                new TableSpec(
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
                ),
                new TableSpec(
                        "artwork_collections",
                        List.of(
                                column("collection_id", "INTEGER", true, null, 1),
                                column("artwork_id", "INTEGER", true, null, 2),
                                column("added_time", "INTEGER", true, null, 0)
                        ),
                        List.of(
                                explicitIndex("idx_artwork_collections_artwork", false, "artwork_id")
                        )
                )
        );

        List<PathColumnSpec> pathColumns = List.of(
                new PathColumnSpec("collections", "id", List.of("download_root"))
        );

        return new SchemaContribution(CoreSchemaContribution.OWNER_PLUGIN_ID,
                tables, List.of(), List.of(), pathColumns);
    }
}
