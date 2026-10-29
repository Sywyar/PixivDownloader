package top.sywyar.pixivdownload.core.db.pathprefix;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("数据库归档的已声明路径映射")
class PathPrefixTransferTest {
    @TempDir Path temp;
    private final TableSpec prefixes = top.sywyar.pixivdownload.core.db.schema.contribution.CoreSchemaContribution.CONTRIBUTION.tables()
            .stream().filter(table -> table.name().equals("path_prefixes")).findFirst().orElseThrow();
    private final TableSpec items = new TableSpec("items", List.of(new ColumnSpec("id", "INTEGER", false, null, 1),
            new ColumnSpec("folder", "TEXT", false, null, 0), new ColumnSpec("body", "TEXT", false, null, 0)), List.of());

    @Test @DisplayName("映射前缀并冻结符号根，只改变声明路径列且优先匹配最深目录")
    void mapsDeclaredColumnsWithoutReplacingText() {
        String root = temp.resolve("source").toString(), nested = temp.resolve("source/inner").toString();
        var capture = new PathPrefixTransfer(root);
        capture.capture(prefixes, List.of(7L, nested));
        var schema = new MigrationSchema(1, List.of(prefixes, items), List.of(), List.of(
                new MigrationSchema.Reference("items", "folder", "path_prefixes", "id", MigrationSchema.Encoding.BRACED_PREFIX)));
        String destination = temp.resolve("moved").toString();
        String inner = temp.resolve("nested moved").toString();
        var mapper = PathPrefixTransfer.mapper(schema, capture.snapshot(), Map.of(0L, destination, 7L, inner));
        assertThat(mapper.apply(prefixes, List.of(7L, nested))).containsExactly(7L, inner);
        assertThat(mapper.apply(items, List.of(1L, "{0}/book", root))).containsExactly(1L, destination + "/book", root);
        assertThat(mapper.apply(items, List.of(2L, nested + "/book", nested))).containsExactly(2L, "{7}/book", nested);
        assertThat(mapper.apply(items, List.of(3L, "{7}/book", root))).containsExactly(3L, "{7}/book", root);
        assertThat(mapper.apply(items, List.of(4L, root + "-unrelated/book", root)))
                .containsExactly(4L, root + "-unrelated/book", root);
        assertThat(capture.snapshot().prefixes().get(7L)).isEqualTo(nested);
    }

    @Test @DisplayName("未知前缀、相对目标、碰撞和过大的源路径拒绝进入恢复计划")
    void rejectsInvalidMappingsAndSource() {
        var paths = new PathPrefixTransfer.Paths(1, Map.of(0L, temp.toString(), 1L, temp.resolve("one").toString(),
                2L, temp.resolve("two").toString()));
        assertThatThrownBy(() -> PathPrefixTransfer.destinations(paths, Map.of(3L, temp.toString()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathPrefixTransfer.destinations(paths, Map.of(1L, "relative"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathPrefixTransfer.destinations(paths, Map.of(1L, temp.resolve("two").toString())))
                .isInstanceOf(IllegalArgumentException.class);
        var capture = new PathPrefixTransfer(temp.toString());
        assertThatThrownBy(() -> capture.capture(prefixes, List.of(1L, "x".repeat(32769)))).isInstanceOf(IllegalArgumentException.class);
        capture.capture(prefixes, List.of(1L, temp.resolve("one").toString()));
        assertThatThrownBy(() -> capture.capture(prefixes, List.of(1L, temp.resolve("other").toString()))).isInstanceOf(IllegalArgumentException.class);
    }
}
