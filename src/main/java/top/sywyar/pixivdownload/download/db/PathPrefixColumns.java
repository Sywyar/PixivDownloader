package top.sywyar.pixivdownload.download.db;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

/**
 * 全部经 {@link PathPrefixCodec} 编码的路径列清单（表名 / 主键列 / 路径列）。
 *
 * <p>启动迁移（绝对路径 → {@code {N}/...}）、符号根折叠（{@code {N}} → {@code {0}}）与
 * 迁移工具的符号根改写（{@code {0}} → {@code {N}}）都必须覆盖这里的<b>全部</b>列，
 * 漏掉任何一列都会留下悬空前缀引用。新增路径前缀列时必须同步登记到本清单。
 */
final class PathPrefixColumns {

    record TableColumns(String table, String idColumn, List<String> columns) {
    }

    static final List<TableColumns> ALL = List.of(
            new TableColumns("artworks", "artwork_id", List.of("folder", "move_folder")),
            new TableColumns("novels", "novel_id", List.of("folder")),
            new TableColumns("manga_series", "series_id", List.of("cover_folder")),
            new TableColumns("novel_series", "series_id", List.of("cover_folder")),
            new TableColumns("collections", "id", List.of("download_root")));

    /**
     * 任意路径前缀列中是否存在符号根 {@code {0}} 的引用行。
     * {@code /} 与 {@code \} 两种编码分隔符都要覆盖（与 {@link PathPrefixCodec} 承认的编码形态一致），
     * 否则 {@code {0}\...} 引用会被漏判，孤儿检测与折叠告警会误报「无 {0} 行」。
     */
    static boolean hasSymbolicRootRows(NamedParameterJdbcTemplate jdbc) {
        StringBuilder sql = new StringBuilder("SELECT ");
        boolean first = true;
        for (TableColumns tc : ALL) {
            for (String column : tc.columns()) {
                if (!first) sql.append(" OR ");
                first = false;
                sql.append("EXISTS(SELECT 1 FROM ").append(tc.table())
                        .append(" WHERE ").append(column).append(" = '{0}'")
                        .append(" OR ").append(column).append(" LIKE '{0}/%'")
                        .append(" OR ").append(column).append(" LIKE '{0}\\%')");
            }
        }
        Boolean exists = jdbc.queryForObject(sql.toString(), new MapSqlParameterSource(), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    private PathPrefixColumns() {
    }
}
