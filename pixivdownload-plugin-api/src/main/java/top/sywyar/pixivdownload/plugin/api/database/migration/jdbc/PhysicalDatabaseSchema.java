package top.sywyar.pixivdownload.plugin.api.database.migration.jdbc;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** JDBC provider 共用的物理结构比较模型，不探测或选择数据库引擎。 */
public class PhysicalDatabaseSchema {

    /**
     * 仅供保留宿主结构模型别名的子类构造。
     */
    protected PhysicalDatabaseSchema() {}

    /**
     * 规范化结构比较使用的标识符。
     * @param value 物理标识符
     * @return 小写标识符；空值转为空字符串
     */
    public static String normalizeIdentifier(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 规范化结构比较使用的类型。
     * @param value 物理类型
     * @return 大写且规范空格的类型
     */
    public static String normalizeType(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /**
     * 去除默认值的冗余括号，空值与 NULL 归一。
     * @param value 物理默认值
     * @return 规范默认值或空值
     */
    public static String normalizeDefault(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        while (normalized.length() > 1 && normalized.startsWith("(") && normalized.endsWith(")")) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        if (normalized.isEmpty() || "NULL".equalsIgnoreCase(normalized)) {
            return null;
        }
        return normalized.replaceAll("\\s+", " ");
    }

    /**
     * 完整的规范物理表集合。
     * @param tables 表名到结构
     */
    public record DatabaseSchema(Map<String, TableSpec> tables) {
        /**
         * 固定物理表集合。
         * @param tables 表名到结构
         */
        public DatabaseSchema {
            tables = Map.copyOf(tables);
        }
    }

    /**
     * 用于比较的表结构。
     * @param name 表名
     * @param columns 列声明
     * @param indexes 索引声明
     */
    public record TableSpec(String name, List<ColumnSpec> columns, List<IndexSpec> indexes) {
        /**
         * 规范表名并复制结构集合。
         * @param name 表名
         * @param columns 列声明
         * @param indexes 索引声明
         */
        public TableSpec {
            name = normalizeIdentifier(name);
            columns = List.copyOf(columns);
            indexes = List.copyOf(indexes);
        }
    }

    /**
     * 用于比较的列结构。
     * @param name 列名
     * @param type 物理类型
     * @param notNull 是否非空
     * @param defaultValue 默认值
     * @param primaryKeyPosition 主键次序，零为非主键
     */
    public record ColumnSpec(String name,
                             String type,
                             boolean notNull,
                             String defaultValue,
                             int primaryKeyPosition) {
        /**
         * 规范列名、类型与默认值。
         * @param name 列名
         * @param type 物理类型
         * @param notNull 是否非空
         * @param defaultValue 默认值
         * @param primaryKeyPosition 主键次序，零为非主键
         */
        public ColumnSpec {
            name = normalizeIdentifier(name);
            type = normalizeType(type);
            defaultValue = normalizeDefault(defaultValue);
        }
    }

    /**
     * 区分显式索引和约束生成的索引。
     */
    public enum IndexOrigin {
        /**
         * 显式创建的索引。
         */
        CREATE_INDEX,
        /**
         * 唯一约束生成的索引。
         */
        UNIQUE_CONSTRAINT
    }

    /**
     * 用于比较的索引结构。
     * @param name 显式索引名
     * @param origin 来源
     * @param unique 是否唯一
     * @param columns 有序索引列
     */
    public record IndexSpec(String name,
                            IndexOrigin origin,
                            boolean unique,
                            List<String> columns) {
        /**
         * 规范索引身份和列名。
         * @param name 显式索引名
         * @param origin 来源
         * @param unique 是否唯一
         * @param columns 有序索引列
         */
        public IndexSpec {
            name = origin == IndexOrigin.CREATE_INDEX ? normalizeIdentifier(name) : null;
            columns = columns.stream()
                    .map(PhysicalDatabaseSchema::normalizeIdentifier)
                    .toList();
        }

        /**
         * 生成包含语义属性的稳定比较签名。
         * @return 结构签名
         */
        public String signature() {
            StringBuilder builder = new StringBuilder(origin.name())
                    .append('|')
                    .append(unique)
                    .append('|')
                    .append(String.join(",", columns));
            if (origin == IndexOrigin.CREATE_INDEX) {
                builder.append('|').append(name);
            }
            return builder.toString();
        }
    }
}
