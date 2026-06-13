package top.sywyar.pixivdownload.plugin.api.schema;

import java.util.List;

/**
 * 含路径前缀编码的列声明。声明后该列自动纳入符号根 {@code {0}} 折叠、
 * 孤儿前缀检测与下载根迁移时的 {@code {0}}→{@code {N}} 改写——漏声明即悬空前缀事故。
 *
 * @param table       表名
 * @param idColumn    行标识列（迁移与日志定位用）
 * @param pathColumns 该表中存路径前缀编码的列
 */
public record PathColumnSpec(
        String table,
        String idColumn,
        List<String> pathColumns
) {
    public PathColumnSpec {
        pathColumns = List.copyOf(pathColumns);
    }
}
