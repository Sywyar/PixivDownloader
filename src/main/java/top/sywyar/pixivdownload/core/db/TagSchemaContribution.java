package top.sywyar.pixivdownload.core.db;

import top.sywyar.pixivdownload.plugin.api.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.TableSpec;

import java.util.List;

import static top.sywyar.pixivdownload.core.db.SchemaSpecs.column;
import static top.sywyar.pixivdownload.core.db.SchemaSpecs.uniqueConstraint;

/**
 * 标签共享池 schema 的 contribution 声明。
 * {@code tags} 被 artwork_tags / novel_tags / novel_series_tags / guest_invite_*_tags 共同引用，属核心共享数据。
 */
public final class TagSchemaContribution {

    public static final SchemaContribution CONTRIBUTION = createContribution();

    private TagSchemaContribution() {}

    private static SchemaContribution createContribution() {
        List<TableSpec> tables = List.of(
                new TableSpec(
                        "tags",
                        List.of(
                                column("tag_id", "INTEGER", false, null, 1),
                                column("name", "TEXT", true, null, 0),
                                column("translated_name", "TEXT", false, null, 0)
                        ),
                        List.of(
                                uniqueConstraint("name")
                        )
                )
        );

        return new SchemaContribution(CoreSchemaContribution.OWNER_PLUGIN_ID,
                tables, List.of(), List.of(), List.of());
    }
}
