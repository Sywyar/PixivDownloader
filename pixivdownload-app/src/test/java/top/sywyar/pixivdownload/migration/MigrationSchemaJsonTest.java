package top.sywyar.pixivdownload.migration;

import org.junit.jupiter.api.*;
import tools.jackson.core.JacksonException;
import top.sywyar.pixivdownload.migration.database.MigrationSchemaJson;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

@DisplayName("耐久迁移清单保留中性 CHECK")
class MigrationSchemaJsonTest {
    @Test @DisplayName("往返保留约束结构与整数类型，不接受类名或跨表表达式")
    void roundTripsCheck() throws Exception {
        var table = new TableSpec("facts", List.of(new ColumnSpec("id", "INTEGER", true, null, 1),
                new ColumnSpec("body", "TEXT", false, null, 0)), List.of()).withCheck(call(AND,
                call(EQUAL, column("facts", "id"), value(1)), call(LOWER_HEX, column("facts", "body"))))
                .withPreviousChecks(List.of(call(EQUAL, column("facts", "id"), value(1))));
        var schema = new MigrationSchema(1, List.of(table), List.of(), List.of());
        var json = MigrationSchemaJson.mapper();
        String encoded = json.writeValueAsString(schema);
        assertThat(json.readValue(encoded, MigrationSchema.class)).isEqualTo(schema);
        var legacy = json.valueToTree(schema);
        ((tools.jackson.databind.node.ObjectNode) legacy.get("tables").get(0)).remove("previousChecks");
        assertThat(json.treeToValue(legacy, MigrationSchema.class).tables().get(0).previousChecks()).isEmpty();
        assertThatThrownBy(() -> json.readValue(encoded.replace("\"kind\":\"call\"", "\"kind\":\"java.lang.Runtime\""),
                MigrationSchema.class)).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> json.readValue(encoded.replace("\"table\":\"facts\"", "\"table\":\"foreign_table\""),
                MigrationSchema.class)).isInstanceOf(JacksonException.class);
    }
    @Test @DisplayName("旧清单允许空 SQL 槽，拒绝遗失非空约束或未知字段")
    void readsEmptyLegacySlotWithoutDroppingConstraints() {
        var table = new TableSpec("facts", List.of(new ColumnSpec("id", "INTEGER", true, null, 1)), List.of())
                .withCheck(call(EQUAL, column("facts", "id"), value(1)));
        var json = MigrationSchemaJson.mapper();
        var tree = (tools.jackson.databind.node.ObjectNode) json.valueToTree(table);
        tree.putNull("checkExpression");
        assertThat(json.treeToValue(tree, TableSpec.class)).isEqualTo(table);
        tree.put("checkExpression", "id=1");
        assertThatThrownBy(() -> json.treeToValue(tree, TableSpec.class)).isInstanceOf(JacksonException.class);
        tree.remove("checkExpression");
        tree.putNull("unknownConstraint");
        assertThatThrownBy(() -> json.treeToValue(tree, TableSpec.class)).isInstanceOf(JacksonException.class);
    }

}
