package top.sywyar.pixivdownload.core.db.pathprefix;

import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import java.util.Map;
import java.util.ArrayList;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

import java.util.List;

/**
 * 全部经 {@link PathPrefixCodec} 编码的路径列清单（表名 / 主键列 / 路径列）。
 *
 * <p>清单内容来自 schema registry 对各 contribution 声明的 {@code PathColumnSpec} 的合并结果
 * （由 {@code DatabaseSchemaRegistry.pathPrefixColumns()} 构造为本类实例），不再是静态总表。
 * 新增路径前缀列时必须在所属领域 contribution 中同步声明 {@code PathColumnSpec}。
 *
 * <p>启动迁移（绝对路径 → {@code {N}/...}）、符号根折叠（{@code {N}} → {@code {0}}）与
 * 迁移工具的符号根改写（{@code {0}} → {@code {N}}）都必须覆盖这里的<b>全部</b>列，
 * 漏掉任何一列都会留下悬空前缀引用。
 */
public final class PathPrefixColumns {

    public record TableColumns(String table, String idColumn, List<String> columns) {
    }

    private final List<TableColumns> all;

    public PathPrefixColumns(List<TableColumns> all) {
        this.all = List.copyOf(all);
    }

    /** 合并后的全部路径前缀列。 */
    public List<TableColumns> all() {
        return all;
    }

    /**
     * 任意路径前缀列中是否存在符号根 {@code {0}} 的引用行。
     * {@code /} 与 {@code \} 两种编码分隔符都要覆盖（与 {@link PathPrefixCodec} 承认的编码形态一致），
     * 否则 {@code {0}\...} 引用会被漏判，孤儿检测与折叠告警会误报「无 {0} 行」。
     */
    public boolean hasSymbolicRootRows(MainDatabase database) {
        var queries = new ArrayList<Query>();
        for (TableColumns tc : all) {
            for (String column : tc.columns()) {
                queries.add(new Query(tc.table(), Map.of("found", value(1)),
                        references(tc.table(), column, PathPrefixCodec.SYMBOLIC_ROOT_TOKEN),
                        List.of(), List.of(), 1, 0));
            }
        }
        if (queries.isEmpty()) return false;
        Query query = queries.size() == 1 ? queries.get(0) : new Query(new Query.Union(queries, true, "paths"),
                List.of(), Map.of("found", column("paths", "found")), null, List.of(), null, List.of(), false, 1, 0);
        return !database.read(s -> s.query(query)).isEmpty();
    }

    /** 保留原始分隔符，同时识别 token 本身及斜杠、反斜杠后缀。 */
    public static int retargetColumn(MainDatabase database, String table, String column,
                                     String fromToken, String toToken) {
        var path = column(table, column);
        return database.transaction(s -> s.update(table, Map.of(column, call(CONCAT, value(toToken),
                call(SUBSTRING, path, value(fromToken.length() + 1), call(LENGTH, path)))),
                references(table, column, fromToken)));
    }

    private static Expression references(String table, String name, String token) {
        var path = column(table, name);
        return call(OR, call(EQUAL, path, value(token)), call(LIKE, path, value(token + "/%")),
                call(LIKE, path, value(token + "\\%")));
    }
}
