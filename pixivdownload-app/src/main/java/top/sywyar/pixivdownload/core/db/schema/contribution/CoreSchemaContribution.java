package top.sywyar.pixivdownload.core.db.schema.contribution;

import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.util.List;

import static top.sywyar.pixivdownload.core.db.schema.SchemaSpecs.column;
import static top.sywyar.pixivdownload.core.db.schema.SchemaSpecs.uniqueConstraint;

/**
 * 核心基础设施 schema 的 contribution 声明（路径前缀注册表）。
 * <p>
 * 其余受管表按领域拆在各自的 {@code XxxSchemaContribution} 中；按「卸载投影测试」
 * （主人插件未安装时其他部件仍需要的表归核心），现存全部长期事实表统一由
 * {@code CorePlugin.schema()} 返回，所有权由宿主使用已注册的 core 身份盖章。
 */
public final class CoreSchemaContribution {

    public static final SchemaContribution CONTRIBUTION = createContribution();

    private CoreSchemaContribution() {}

    private static SchemaContribution createContribution() {
        List<TableSpec> tables = List.of(
                new TableSpec("file_operation_entries", List.of(
                        column("entry_id", "TEXT", true, null, 1),
                        column("operation_id", "TEXT", true, null, 0),
                        column("ordinal", "INTEGER", true, null, 0),
                        column("target_path", "TEXT", true, null, 0),
                        column("old_hash", "TEXT", false, null, 0),
                        column("new_hash", "TEXT", false, null, 0),
                        column("committed", "INTEGER", true, "0", 0)
                ), List.of(uniqueConstraint("operation_id", "ordinal"), uniqueConstraint("target_path"))),
                new TableSpec("external_work_files", List.of(
                        column("reference_id", "TEXT", true, null, 1),
                        column("work_type", "TEXT", true, null, 0),
                        column("work_id", "INTEGER", true, null, 0),
                        column("page", "INTEGER", true, null, 0),
                        column("root_path", "TEXT", true, null, 0),
                        column("file_path", "TEXT", true, null, 0),
                        column("record_time", "INTEGER", true, null, 0)
                ), List.of(uniqueConstraint("work_type", "work_id", "page"))),
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

        return new SchemaContribution(tables, List.of(), List.of(
                new top.sywyar.pixivdownload.plugin.api.schema.PathColumnSpec("external_work_files", "reference_id", List.of("root_path", "file_path")),
                new top.sywyar.pixivdownload.plugin.api.schema.PathColumnSpec("file_operation_entries", "entry_id", List.of("target_path"))));
    }
}
