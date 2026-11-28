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

    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"{0}/volume\\one.jpg", "{7}/volume\\one.jpg",
            "{7}/portrait:blue.jpg", "/library/seven/portrait:blue.jpg", "{7}/AUX.jpg", "{0}/art./one.jpg"})
    void rejectsUnrepresentableSourceComponentsBeforeReplacingData(String path) {
        var schema = new MigrationSchema(1, List.of(prefixes, items), List.of(), List.of(
                new MigrationSchema.Reference("items", "folder", "path_prefixes", "id", MigrationSchema.Encoding.BRACED_PREFIX)));
        var mapper = PathPrefixTransfer.mapper(schema, new PathPrefixTransfer.Paths(1, Map.of(0L, "/library", 7L, "/library/seven")),
                Map.of(0L, temp.toString(), 7L, temp.resolve("seven").toString()));
        assertThat(mapper.apply(items, List.of(1L, "{7}/volume/one.jpg", ""))).contains("{7}/volume/one.jpg");
        assertThatThrownBy(() -> mapper.apply(items, List.of(2L, path, "")))
                .isInstanceOf(PathPrefixTransfer.UnrepresentablePath.class).hasMessage("table=items, column=folder, archiveRow=2");
    }

    @Test @DisplayName("未知前缀、相对目标、碰撞和过大的源路径拒绝进入恢复计划")
    void rejectsInvalidMappingsAndSource() {
        var paths = new PathPrefixTransfer.Paths(1, Map.of(0L, temp.toString(), 1L, temp.resolve("one").toString(),
                2L, temp.resolve("two").toString()));
        assertThatThrownBy(() -> PathPrefixTransfer.destinations(paths, Map.of(3L, temp.toString()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathPrefixTransfer.destinations(paths, Map.of(1L, "relative"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathPrefixTransfer.destinations(paths, Map.of(1L, temp.resolve("two").toString())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathPrefixTransfer.destinations(paths, Map.of(2L, temp.resolve("one/.").toString())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathPrefixTransfer.destinations(paths, Map.of(1L, temp.resolve("new").toString(),
                2L, temp.resolve("new/.").toString()))).isInstanceOf(IllegalArgumentException.class);
        var capture = new PathPrefixTransfer(temp.toString());
        assertThatThrownBy(() -> capture.capture(prefixes, List.of(1L, "x".repeat(32769)))).isInstanceOf(IllegalArgumentException.class);
        capture.capture(prefixes, List.of(1L, temp.resolve("one").toString()));
        assertThatThrownBy(() -> capture.capture(prefixes, List.of(1L, temp.resolve("other").toString()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unmappedWindowsAbsoluteReferencesRequireAWindowsDestination() {
        var schema = new MigrationSchema(1, List.of(prefixes, items), List.of(), List.of(
                new MigrationSchema.Reference("items", "folder", "path_prefixes", "id", MigrationSchema.Encoding.BRACED_PREFIX)));
        var source = new PathPrefixTransfer.Paths(1, Map.of(0L, "C:\\Library"));
        var mapper = PathPrefixTransfer.mapper(schema, source, Map.of(0L, temp.toString()));
        for (String path : List.of("C:\\External\\Cover.jpg", "\\\\server\\share\\Cover.jpg")) {
            var row = List.<Object>of(1L, path, "");
            if (java.io.File.separatorChar == '\\') assertThat(mapper.apply(items, row)).isEqualTo(row);
            else assertThatThrownBy(() -> mapper.apply(items, row))
                    .isInstanceOf(PathPrefixTransfer.UnrepresentablePath.class)
                    .hasMessageContaining("table=items, column=folder, archiveRow=");
        }
    }

    @Test
    void matchesForeignSourcePathsUsingTheirArchivedSpelling() {
        var schema = new MigrationSchema(1, List.of(prefixes, items), List.of(), List.of(
                new MigrationSchema.Reference("items", "folder", "path_prefixes", "id", MigrationSchema.Encoding.BRACED_PREFIX)));
        String root = temp.resolve("new-root").toString();
        var mapper = PathPrefixTransfer.mapper(schema, new PathPrefixTransfer.Paths(1,
                Map.of(0L, "/library:art", 7L, "/library:art/Sub")), Map.of(0L, root, 7L, temp.resolve("seven").toString()));
        assertThat(mapper.apply(items, List.of(1L, "/library:art/Sub/book", ""))).containsExactly(1L, "{7}/book", "");
        assertThat(mapper.apply(items, List.of(2L, "/library:art/sub/book", ""))).containsExactly(2L, root + "/sub/book", "");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void comparesSymlinkParentDestinationsWithoutRedirectingThem() throws Exception {
        java.nio.file.Files.createSymbolicLink(temp.resolve("link"), java.nio.file.Files.createDirectories(temp.resolve("real/nested")));
        Path actual = java.nio.file.Files.createDirectory(temp.resolve("real/art"));
        Path neighbour = java.nio.file.Files.createDirectory(temp.resolve("art"));
        String selected = temp.resolve("link/../art").toString();
        var source = new PathPrefixTransfer.Paths(1, Map.of(0L, temp.toString(), 7L, temp.resolve("old").toString(), 8L, actual.toString()));
        String alias = java.nio.file.Files.createSymbolicLink(temp.resolve("alias"), actual).toString();
        assertThatThrownBy(() -> PathPrefixTransfer.destinations(source, Map.of(7L, alias)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("transfer.database-path-invalid");
        var historical = new PathPrefixTransfer.Paths(1, Map.of(0L, temp.toString(), 7L, alias, 8L, actual.toString()));
        assertThat(PathPrefixTransfer.destinations(historical, Map.of())).isEqualTo(historical.prefixes());
        String offline = temp.resolve("offline").toString();
        assertThat(PathPrefixTransfer.destinations(source, Map.of(7L, offline))).containsEntry(7L, offline);
        assertThatThrownBy(() -> PathPrefixTransfer.destinations(source, Map.of(7L, selected)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PathPrefixTransfer.destinations(source, Map.of(7L, selected, 8L, neighbour.toString())))
                .containsEntry(7L, selected).containsEntry(8L, neighbour.toString());
    }
}
