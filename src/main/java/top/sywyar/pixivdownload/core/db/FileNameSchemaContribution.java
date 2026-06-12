package top.sywyar.pixivdownload.core.db;

import top.sywyar.pixivdownload.plugin.api.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.TableSpec;

import java.util.List;

import static top.sywyar.pixivdownload.core.db.SchemaSpecs.autoIncrementPrimaryKey;
import static top.sywyar.pixivdownload.core.db.SchemaSpecs.column;
import static top.sywyar.pixivdownload.core.db.SchemaSpecs.uniqueConstraint;

/**
 * 文件名模板域 schema 的 contribution 声明（模板池与作者名 interning 池）。
 * 两张池表被 artworks 与 novels 的 file_name / file_author_name_id 共同引用，属核心共享数据。
 */
public final class FileNameSchemaContribution {

    public static final SchemaContribution CONTRIBUTION = createContribution();

    private FileNameSchemaContribution() {}

    private static SchemaContribution createContribution() {
        List<TableSpec> tables = List.of(
                new TableSpec(
                        "file_name_templates",
                        List.of(
                                autoIncrementPrimaryKey("id"),
                                column("template", "TEXT", true, null, 0)
                        ),
                        List.of(
                                uniqueConstraint("template")
                        )
                ),
                new TableSpec(
                        "file_author_names",
                        List.of(
                                autoIncrementPrimaryKey("id"),
                                column("name", "TEXT", true, null, 0)
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
