package top.sywyar.pixivdownload.core.db.pathprefix;

import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;

/** 只处理宿主前缀表和已声明路径列；符号根冻结为用户确认的位置，不继承另一安装的工作目录。 */
public final class PathPrefixTransfer {
    public record Paths(int version, Map<Long, String> prefixes) {
        public Paths {
            if (version != 1 || prefixes == null || prefixes.size() > 20_000 || !prefixes.containsKey(0L)) throw invalid();
            prefixes.forEach((id, value) -> {
                if (id == null || id < 0 || value == null || value.isBlank() || value.length() > 32768
                        || value.indexOf('\0') >= 0) throw invalid();
            });
            prefixes = Collections.unmodifiableMap(new TreeMap<>(prefixes));
        }
    }

    private final Map<Long, String> paths = new TreeMap<>();
    private long remaining = 8 * 1024 * 1024;

    public PathPrefixTransfer(String downloadRoot) {
        String root = Path.of(downloadRoot).toAbsolutePath().normalize().toString();
        reserve(root);
        paths.put(0L, root);
    }

    /** 从同一个导出游标收集前缀，不另开连接读取可能已变化的数据。 */
    public List<Object> capture(TableSpec table, List<Object> row) {
        if (table.name().equals("path_prefixes")) {
            long id = ((Number) row.get(column(table, "id"))).longValue();
            String path = (String) row.get(column(table, "path"));
            reserve(path);
            if (id <= 0 || paths.putIfAbsent(id, path) != null || paths.size() > 20_000) throw invalid();
        }
        return row;
    }

    public Paths snapshot() { return new Paths(1, paths); }

    private void reserve(String value) {
        if (value == null || value.isBlank() || value.length() > 32768) throw invalid();
        remaining -= 64L + 6L * value.length();
        if (remaining < 0) throw invalid();
    }

    public static void validate(top.sywyar.pixivdownload.plugin.api.database.migration.MigrationVerification verification, Paths paths) {
        var table = verification.tables().get("path_prefixes");
        if (paths.prefixes().size() - 1L != (table == null ? 0 : table.rows())) throw invalid();
    }

    public static Map<Long, String> destinations(Paths source, Map<Long, String> selected) {
        if (!source.prefixes().keySet().containsAll(selected.keySet())) throw invalid();
        Map<Long, String> result = new TreeMap<>(source.prefixes());
        result.putAll(selected);
        Set<String> unique = new HashSet<>();
        result.replaceAll((id, value) -> {
            if (value == null || value.length() > 32768 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) throw invalid();
            Path path = Path.of(value);
            if (!path.isAbsolute()) throw invalid();
            String normalized = PathPrefixCodec.stripTrailingSeparators(path.normalize().toString());
            if (id != 0 && !unique.add(normalized.replace('\\', '/').toLowerCase(Locale.ROOT))) throw invalid();
            return normalized;
        });
        return Map.copyOf(result);
    }

    public static BiFunction<TableSpec, List<Object>, List<Object>> mapper(MigrationSchema schema, Paths source,
            Map<Long, String> selected) {
        var destination = destinations(source, selected);
        String root = destination.get(0L);
        var from = PathPrefixCodec.snapshot(root, source.prefixes().entrySet().stream()
                .map(entry -> new PathPrefix(entry.getKey(), entry.getValue())).toList());
        var to = PathPrefixCodec.snapshot(root, destination.entrySet().stream()
                .map(entry -> new PathPrefix(entry.getKey(), entry.getValue())).toList());
        Map<String, Set<String>> columns = new HashMap<>();
        for (var reference : schema.references()) if (reference.encoding() == MigrationSchema.Encoding.BRACED_PREFIX
                && reference.targetTable().equals("path_prefixes") && reference.targetColumn().equals("id"))
            columns.computeIfAbsent(reference.table(), ignored -> new HashSet<>()).add(reference.column());
        return (table, row) -> {
            if (table.name().equals("path_prefixes")) {
                long id = ((Number) row.get(column(table, "id"))).longValue();
                int pathColumn = column(table, "path");
                if (id <= 0 || !Objects.equals(source.prefixes().get(id), row.get(pathColumn))) throw invalid();
                var result = new ArrayList<>(row);
                result.set(pathColumn, destination.get(id));
                return result;
            }
            var declared = columns.get(table.name());
            if (declared == null) return row;
            var result = new ArrayList<>(row);
            for (String name : declared) {
                int index = column(table, name);
                String value = (String) row.get(index);
                String encoded = from.encode(value);
                if (encoded != null && (encoded.equals("{0}") || encoded.startsWith("{0}/") || encoded.startsWith("{0}\\")))
                    encoded = to.resolve(encoded);
                result.set(index, encoded);
            }
            return result;
        };
    }

    private static int column(TableSpec table, String name) {
        for (int i = 0; i < table.columns().size(); i++) if (table.columns().get(i).name().equals(name)) return i;
        throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("transfer.database-path-invalid"); }
}
