package top.sywyar.pixivdownload.author;

import top.sywyar.pixivdownload.core.db.schema.contribution.CoreSchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.util.List;

import static top.sywyar.pixivdownload.core.db.schema.SchemaSpecs.column;

/**
 * 作者域 schema 的 contribution 声明。作者表被 artworks / novels / guest-invite 共同引用，属核心共享数据。
 */
public final class AuthorSchemaContribution {

    public static final SchemaContribution CONTRIBUTION = createContribution();

    private AuthorSchemaContribution() {}

    private static SchemaContribution createContribution() {
        List<TableSpec> tables = List.of(
                new TableSpec(
                        "authors",
                        List.of(
                                column("author_id", "INTEGER", false, null, 1),
                                column("name", "TEXT", true, null, 0),
                                column("updated_time", "INTEGER", true, null, 0)
                        ),
                        List.of()
                )
        );

        return new SchemaContribution(CoreSchemaContribution.OWNER_PLUGIN_ID,
                tables, List.of(), List.of(), List.of());
    }
}
