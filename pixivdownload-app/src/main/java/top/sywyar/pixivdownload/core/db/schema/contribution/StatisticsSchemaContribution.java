package top.sywyar.pixivdownload.core.db.schema.contribution;

import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.util.List;

import static top.sywyar.pixivdownload.core.db.schema.SchemaSpecs.column;

/**
 * 统计域 schema 的 contribution 声明（单行聚合计数表）。
 * 统计事实由下载链路落库，stats 插件只读聚合、无私有表，故归核心。
 */
public final class StatisticsSchemaContribution {

    public static final SchemaContribution CONTRIBUTION = createContribution();

    private StatisticsSchemaContribution() {}

    private static SchemaContribution createContribution() {
        List<TableSpec> tables = List.of(
                new TableSpec(
                        "statistics",
                        List.of(
                                column("id", "INTEGER", false, null, 1),
                                column("total_artworks", "INTEGER", false, "0", 0),
                                column("total_images", "INTEGER", false, "0", 0),
                                column("total_moved", "INTEGER", false, "0", 0)
                        ),
                        List.of(),
                        // 单行表守卫：id 恒为 1（与历史 DDL 的列内 CHECK 等价，表级表达）
                        "id = 1"
                )
        );

        return new SchemaContribution(tables, List.of(), List.of());
    }
}
