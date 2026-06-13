package top.sywyar.pixivdownload.core.db;

import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.util.List;

import static top.sywyar.pixivdownload.core.db.SchemaSpecs.column;
import static top.sywyar.pixivdownload.core.db.SchemaSpecs.uniqueConstraint;

/**
 * 核心基础设施 schema 的 contribution 声明（路径前缀注册表）。
 * <p>
 * 其余受管表按领域拆在各自的 {@code XxxSchemaContribution} 中；按「卸载投影测试」
 * （主人插件未安装时其他部件仍需要的表归核心），现存全部长期事实表的
 * ownerPluginId 一律为 {@link #OWNER_PLUGIN_ID}，统一由 {@code CorePlugin.schema()} 返回。
 */
public final class CoreSchemaContribution {

    public static final String OWNER_PLUGIN_ID = "core";

    public static final SchemaContribution CONTRIBUTION = createContribution();

    private CoreSchemaContribution() {}

    private static SchemaContribution createContribution() {
        List<TableSpec> tables = List.of(
                new TableSpec(
                        "path_prefixes",
                        List.of(
                                column("id", "INTEGER", false, null, 1),
                                column("path", "TEXT", true, null, 0)
                        ),
                        List.of(
                                uniqueConstraint("path")
                        )
                )
        );

        return new SchemaContribution(OWNER_PLUGIN_ID, tables, List.of(), List.of(), List.of());
    }
}
