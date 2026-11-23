package top.sywyar.pixivdownload.schedule.batch;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.plugin.api.contract.InputFieldException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchDefinitionSchemaTest {
    @Test void requiredFieldInsideAnArrayPointsToTheMissingProperty() {
        var json = JsonMapper.builder().build();
        String schema = """
                {"$schema":"https://json-schema.org/draft/2020-12/schema","$id":"urn:fixture:batch","$defs":{"work":{"type":"object","required":["mode"]}},
                 "properties":{"works":{"type":"array","items":{"$ref":"#/$defs/work"}}}}
                """;
        assertThatThrownBy(() -> BatchDefinitionSchema.validate(json, schema, "{\"works\":[{}]}"))
                .isInstanceOfSatisfying(InputFieldException.class, error -> {
                    assertThat(error.field()).isEqualTo("/definition/works/0/mode");
                    assertThat(error.reason()).isEqualTo("required");
                });
        BatchDefinitionSchema.validate(json, schema, "{\"works\":[{\"mode\":\"import\"}]}");
    }
}
