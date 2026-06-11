package top.sywyar.pixivdownload.plugin.api;

import java.util.List;

/**
 * 插件对核心表列的使用声明（只读契约）。
 * 核心表结构演进时据此追踪受影响插件。
 *
 * @param table   核心表名
 * @param columns 用到的列
 */
public record CoreColumnUsage(
        String table,
        List<String> columns
) {
    public CoreColumnUsage {
        columns = List.copyOf(columns);
    }
}
