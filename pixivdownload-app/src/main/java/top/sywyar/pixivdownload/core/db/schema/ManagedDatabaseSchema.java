package top.sywyar.pixivdownload.core.db.schema;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;

/**
 * 受管 SQLite schema 的归一化 record 模型。
 * 期望 schema 由 {@code plugin.DatabaseSchemaRegistry} 合并核心与各插件的
 * {@code SchemaContribution} 生成（核心表声明见 {@code CoreSchemaContribution}），
 * 启动检查用它与磁盘上的实际 schema 比对。
 */
public final class ManagedDatabaseSchema {

    private ManagedDatabaseSchema() {}

    public static String normalizeIdentifier(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    static String normalizeType(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    static String normalizeDefault(String value) {
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

    public record DatabaseSchema(Map<String, TableSpec> tables) {
        public DatabaseSchema {
            tables = Map.copyOf(tables);
        }
    }

    public record TableSpec(String name, List<ColumnSpec> columns, List<IndexSpec> indexes) {
        public TableSpec {
            name = normalizeIdentifier(name);
            columns = List.copyOf(columns);
            indexes = List.copyOf(indexes);
        }
    }

    public record ColumnSpec(String name,
                             String type,
                             boolean notNull,
                             String defaultValue,
                             int primaryKeyPosition) {
        public ColumnSpec {
            name = normalizeIdentifier(name);
            type = normalizeType(type);
            defaultValue = normalizeDefault(defaultValue);
        }
    }

    public enum IndexOrigin {
        CREATE_INDEX,
        UNIQUE_CONSTRAINT
    }

    public record IndexSpec(String name,
                            IndexOrigin origin,
                            boolean unique,
                            List<String> columns) {
        public IndexSpec {
            name = origin == IndexOrigin.CREATE_INDEX ? normalizeIdentifier(name) : null;
            columns = columns.stream()
                    .map(ManagedDatabaseSchema::normalizeIdentifier)
                    .toList();
        }

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
