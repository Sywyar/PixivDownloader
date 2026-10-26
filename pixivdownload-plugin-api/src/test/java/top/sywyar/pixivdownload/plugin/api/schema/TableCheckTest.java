package top.sywyar.pixivdownload.plugin.api.schema;

import org.junit.jupiter.api.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

@DisplayName("中性行约束的声明边界")
class TableCheckTest {
    @Test @DisplayName("拒绝非布尔结果、聚合、跨表、未知列和不匹配类型")
    void rejectsUnsupportedExpressions() {
        var table = new TableSpec("facts", List.of(new ColumnSpec("id", "INTEGER", true, null, 1)), List.of());
        for (var invalid : List.of(value(1), call(SUM, column("facts", "id")),
                call(IS_NULL, column("other", "id")), call(IS_NULL, column("facts", "unknown")),
                call(LOWER_HEX, column("facts", "id")), call(EQUAL, column("facts", "id"), value("1"))))
            assertThatThrownBy(() -> table.withCheck(invalid)).hasMessage("STORAGE_CHECK_INVALID");
        var check = call(EQUAL, column("facts", "id"), value(1));
        assertThatThrownBy(() -> table.withPreviousChecks(List.of(check))).hasMessage("STORAGE_CHECK_INVALID");
        assertThatThrownBy(() -> table.withCheck(check).withPreviousChecks(List.of(value(1)))).hasMessage("STORAGE_CHECK_INVALID");
        assertThatThrownBy(() -> table.withCheck(check).withPreviousChecks(List.of(call(IS_NULL, column("other", "id")))))
                .hasMessage("STORAGE_CHECK_INVALID");
        assertThat(table.previousChecks()).isEmpty();
        var previous = new java.util.ArrayList<top.sywyar.pixivdownload.plugin.api.storage.relational.Expression>(List.of(check));
        var declared = table.withCheck(check).withPreviousChecks(previous);
        previous.clear();
        assertThat(declared.previousChecks()).containsExactly(check);
        assertThatThrownBy(() -> new TableSpec(table.name(), table.columns(), table.indexes(), "id=1", check))
                .hasMessage("STORAGE_CHECK_INVALID");
    }
}
