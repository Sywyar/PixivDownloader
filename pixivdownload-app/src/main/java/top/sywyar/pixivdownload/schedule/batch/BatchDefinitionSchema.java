package top.sywyar.pixivdownload.schedule.batch;

import com.networknt.schema.FailFastAssertionException;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import tools.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.plugin.api.contract.InputFieldException;

/** Validates the selected source's self-contained schema before a batch is published. */
final class BatchDefinitionSchema {
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder()
                    .failFast(true).pathType(PathType.JSON_POINTER).build())
                    .schemaLoader(loader -> loader.block(location -> true)));

    private BatchDefinitionSchema() {}

    static void validate(ObjectMapper json, String schema, String definition) {
        try {
            var errors = SCHEMAS.getSchema(json.readTree(schema)).validate(json.readTree(definition));
            if (!errors.isEmpty()) throw invalid(errors.getFirst());
        } catch (FailFastAssertionException failure) {
            throw invalid(failure.getError());
        }
    }

    private static InputFieldException invalid(com.networknt.schema.Error error) {
        var field = error.getInstanceLocation();
        if ("required".equals(error.getKeyword()) && error.getProperty() != null)
            field = field.append(error.getProperty());
        return new InputFieldException("/definition" + field, error.getKeyword());
    }
}
