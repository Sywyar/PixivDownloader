package top.sywyar.pixivdownload.series;

import top.sywyar.pixivdownload.core.db.schema.contribution.CoreSchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.PathColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.util.List;

import static top.sywyar.pixivdownload.core.db.schema.SchemaSpecs.column;

/**
 * 漫画系列域 schema 的 contribution 声明。系列 meta 属核心长期事实数据。
 */
public final class MangaSeriesSchemaContribution {

    public static final SchemaContribution CONTRIBUTION = createContribution();

    private MangaSeriesSchemaContribution() {}

    private static SchemaContribution createContribution() {
        List<TableSpec> tables = List.of(
                new TableSpec(
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
                )
        );

        List<PathColumnSpec> pathColumns = List.of(
                new PathColumnSpec("manga_series", "series_id", List.of("cover_folder"))
        );

        return new SchemaContribution(CoreSchemaContribution.OWNER_PLUGIN_ID,
                tables, List.of(), List.of(), pathColumns);
    }
}
