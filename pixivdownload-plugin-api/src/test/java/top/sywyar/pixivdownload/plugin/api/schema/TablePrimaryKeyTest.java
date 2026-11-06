package top.sywyar.pixivdownload.plugin.api.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

@DisplayName("联合主键扩展声明")
class TablePrimaryKeyTest {
    private TableSpec table(boolean notNull, String defaultValue) {
        return new TableSpec("facts", List.of(
                new ColumnSpec("id", "INTEGER", true, null, 1),
                new ColumnSpec("language", "TEXT", true, null, 2),
                new ColumnSpec("engine", "TEXT", notNull, defaultValue, 3)), List.of());
    }

    @Test @DisplayName("仅接受按旧顺序扩展且有非空默认值的联合主键")
    void rejectsUnsafeKeyChanges() {
        var table = table(true, "'unknown'");
        for (var previous : List.of(List.of("id"), List.of("language", "id"),
                List.of("id", "language", "engine"), List.of("id", "language", "engine", "extra")))
            assertThatThrownBy(() -> table.withPreviousPrimaryKey(previous))
                    .hasMessage("STORAGE_PRIMARY_KEY_MIGRATION_INVALID");
        for (var invalid : List.of(table(false, "'unknown'"), table(true, null)))
            assertThatThrownBy(() -> invalid.withPreviousPrimaryKey(List.of("id", "language")))
                    .hasMessage("STORAGE_PRIMARY_KEY_MIGRATION_INVALID");
    }

    @Test @DisplayName("键声明不可变且补充行约束时保留迁移信息")
    void retainsMigrationAcrossCheckDeclarations() {
        var previous = new java.util.ArrayList<>(List.of("id", "language"));
        var declared = table(true, "'unknown'").withPreviousPrimaryKey(previous);
        previous.clear();
        var check = call(GREATER, column("facts", "id"), value(0));
        assertThat(declared.withCheck(check).withPreviousChecks(List.of(check)).previousPrimaryKey())
                .containsExactly("id", "language");
        assertThat(table(true, "'unknown'").previousPrimaryKey()).isEmpty();
    }
}
