package top.sywyar.pixivdownload.migration;

import org.junit.jupiter.api.*;
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
                call(EQUAL, column("facts", "id"), value(1)), call(LOWER_HEX, column("facts", "body"))));
        var schema = new MigrationSchema(1, List.of(table), List.of(), List.of());
        var json = MigrationSchemaJson.mapper();
        String encoded = json.writeValueAsString(schema);
        assertThat(json.readValue(encoded, MigrationSchema.class)).isEqualTo(schema);
        assertThatThrownBy(() -> json.readValue(encoded.replace("\"kind\":\"call\"", "\"kind\":\"java.lang.Runtime\""),
                MigrationSchema.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> json.readValue(encoded.replace("\"table\":\"facts\"", "\"table\":\"foreign_table\""),
                MigrationSchema.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}
