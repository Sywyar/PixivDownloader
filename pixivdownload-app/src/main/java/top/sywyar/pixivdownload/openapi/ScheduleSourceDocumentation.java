package top.sywyar.pixivdownload.openapi;

import top.sywyar.pixivdownload.schedule.batch.ManualBatchController;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import top.sywyar.pixivdownload.schedule.dto.ScheduleTaskRequest;
import java.math.BigDecimal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import io.swagger.v3.oas.models.examples.Example;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriUtils;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** Publishes only provider-declared schema metadata from the active schedule registry. */
final class ScheduleSourceDocumentation {
    private static final Logger LOG = LoggerFactory.getLogger(ScheduleSourceDocumentation.class);

    private ScheduleSourceDocumentation() {}

    static void describe(OpenAPI api, ScheduleCapabilityRegistry.SnapshotView snapshot) {
        var catalog = new TreeMap<String, ObjectNode>();
        var definitions = new TreeMap<String, String>();
        var examplesByType = new TreeMap<String, JsonNode>();
        var workPermissions = new TreeMap<String, String>();
        for (var owner : snapshot.owners()) {
            for (String type : owner.workTypes()) workPermissions.put(type, owner.owner().featurePluginId() + ":batches.execute");
        }
        for (var owner : snapshot.owners()) {
            String pluginId = owner.owner().featurePluginId();
            for (var source : owner.sourceDescriptors()) {
                var entry = Json31.mapper().createObjectNode();
                entry.put("sourceType", source.sourceType());
                entry.put("ownerPluginId", pluginId);
                entry.put("batchSubmitPermission", pluginId + ":batches.execute");
                entry.put("schedulePermission", pluginId + ":schedule.manage");
                var executors = entry.putObject("executePermissionsByWorkType");
                source.possibleWorkTypes().stream().sorted().filter(workPermissions::containsKey)
                        .forEach(type -> executors.put(type, workPermissions.get(type)));
                entry.put("definitionSchema", source.definitionSchema());
                entry.put("definitionVersion", source.definitionVersion());
                var schema = schema(pluginId, source);
                // Keep each provider schema a standalone JSON document, including its local references.
                entry.put("inputSchemaJson", schema == null ? null : schema.toString());
                String definitionRef = null;
                if (schema instanceof ObjectNode declared) {
                    String name = "ScheduleDefinition." + source.sourceType();
                    String ref = "#/components/schemas/" + name;
                    var embedded = declared.deepCopy();
                    embedded.remove("$id");
                    api.getComponents().addSchemas(name, Json31.mapper().convertValue(
                            rebaseLocalRefs(embedded, ref) ? embedded : declared, Schema.class));
                    definitionRef = ref;
                }
                definitions.put(source.sourceType(), definitionRef);
                if (schema != null && schema.path("examples").isArray() && !schema.path("examples").isEmpty()) {
                    examplesByType.put(source.sourceType(), schema.path("examples").get(0));
                }
                catalog.put(source.sourceType(), entry);
            }
        }
        api.addExtension("x-schedule-sources", catalog);
        String requestName = ScheduleTaskRequest.class.getCanonicalName();
        Schema<?> trigger = api.getComponents().getSchemas().get(requestName);
        if (trigger != null) {
            var interval = new StringSchema();
            interval.setConst("interval");
            var cron = new StringSchema();
            cron.setConst("cron");
            trigger.addOneOfItem(new ObjectSchema().addProperty("triggerKind", interval)
                    .addProperty("intervalMinutes", new IntegerSchema().minimum(BigDecimal.ONE)).addRequiredItem("intervalMinutes"));
            trigger.addOneOfItem(new ObjectSchema().addProperty("triggerKind", cron)
                    .addProperty("cronExpr", new StringSchema().pattern("\\S")).addRequiredItem("cronExpr"));
            var version = trigger.getProperties().remove("expectedStateVersion");
            var create = new ObjectSchema().addProperty("expectedStateVersion", new Schema<>().types(java.util.Set.of("null")).description(version.getDescription()));
            var update = new ObjectSchema().addProperty("expectedStateVersion", version).addRequiredItem("expectedStateVersion");
            bindTaskRequest(api, "/api/schedule/tasks", HttpMethod.POST, requestName, "Create", create);
            bindTaskRequest(api, "/api/schedule/tasks/{id}", HttpMethod.PUT, requestName, "Update", update);
        }
        addExamples(api, "/api/schedule/tasks", examplesByType, true);
        Schema<?> batchRequest = api.getComponents().getSchemas().get(ManualBatchController.Create.class.getCanonicalName());
        if (batchRequest != null && !definitions.isEmpty()) {
            var discriminator = new io.swagger.v3.oas.models.media.Discriminator().propertyName("sourceType");
            definitions.forEach((sourceType, definitionRef) -> {
                var variant = new ObjectSchema();
                variant.setProperties(new java.util.LinkedHashMap<>(batchRequest.getProperties()));
                variant.setRequired(batchRequest.getRequired());
                var kind = new StringSchema();
                kind.setConst(sourceType);
                kind.setDescription(batchRequest.getProperties().get("sourceType").getDescription());
                variant.addProperty("sourceType", kind);
                if (definitionRef != null) variant.addProperty("definition", new Schema<>().$ref(definitionRef)
                        .description(batchRequest.getProperties().get("definition").getDescription()));
                String name = "BatchRequest." + sourceType;
                String ref = "#/components/schemas/" + name;
                api.getComponents().addSchemas(name, variant);
                batchRequest.addOneOfItem(new Schema<>().$ref(ref));
                discriminator.mapping(sourceType, ref);
            });
            batchRequest.setDiscriminator(discriminator);
        }
        addExamples(api, "/api/download/batches", examplesByType, false);
    }

    private static void bindTaskRequest(OpenAPI api, String route, HttpMethod method,
                                        String baseName, String variant, Schema<?> schema) {
        var path = api.getPaths().get(route);
        if (path == null) return;
        var operation = path.readOperationsMap().get(method);
        if (operation == null || operation.getRequestBody() == null) return;
        schema.addAllOfItem(new Schema<>().$ref("#/components/schemas/" + baseName));
        String name = baseName + "." + variant;
        api.getComponents().addSchemas(name, schema);
        operation.getRequestBody().getContent().get("application/json").setSchema(new Schema<>().$ref("#/components/schemas/" + name));
    }

    private static void addExamples(OpenAPI api, String route, Map<String, JsonNode> examples, boolean scheduled) {
        var path = api.getPaths() == null ? null : api.getPaths().get(route);
        if (path == null || path.getPost() == null || path.getPost().getRequestBody() == null) return;
        var media = path.getPost().getRequestBody().getContent().get("application/json");
        if (media == null) return;
        examples.forEach((type, definition) -> {
            String token = "<activationToken from GET /api/schedule/sources>";
            var example = scheduled ? Map.of("name", type, "sourceType", type,
                    "activationToken", token, "definitionJson", definition.toString(), "triggerKind", "interval", "intervalMinutes", 60)
                    : Map.of("name", type, "sourceType", type, "activationToken", token, "definition", definition);
            var batchField = Json31.mapper().createObjectNode();
            batchField.set("definition", definition);
            var scheduleField = Json31.mapper().createObjectNode().put("definitionJson", definition.toString());
            String encoding = "`POST /api/download/batches`\n```json\n" + batchField.toPrettyString() + "\n```\n\n"
                    + "`POST /api/schedule/tasks`\n```json\n" + scheduleField.toPrettyString() + "\n```";
            media.addExamples(type, new Example().summary(type).description(encoding).value(example));
        });
    }

    // Leave independent resources intact when their references depend on an ID, anchor or external base.
    private static boolean rebaseLocalRefs(JsonNode node, String base) {
        if (!(node instanceof ObjectNode schema)) return true;
        if (schema.has("$id") || schema.has("$anchor") || schema.has("$dynamicAnchor") || schema.has("$dynamicRef")) return false;
        if (schema.has("$ref")) {
            String ref = schema.path("$ref").asText();
            if (!ref.equals("#") && !ref.startsWith("#/")) return false;
            schema.put("$ref", base + ref.substring(1));
        }
        for (String key : java.util.List.of("$defs", "definitions", "properties", "patternProperties", "dependentSchemas",
                "dependencies", "allOf", "anyOf", "oneOf", "prefixItems"))
            for (JsonNode child : schema.path(key)) if (!rebaseLocalRefs(child, base)) return false;
        for (String key : java.util.List.of("not", "if", "then", "else", "items", "contains", "additionalProperties",
                "additionalItems", "propertyNames", "unevaluatedItems", "unevaluatedProperties", "contentSchema")) {
            var child = schema.path(key);
            if (!rebaseLocalRefs(child, base)) return false;
            if (child.isArray()) for (var item : child) if (!rebaseLocalRefs(item, base)) return false;
        }
        return true;
    }

    private static JsonNode schema(String pluginId, ScheduledSourceDescriptor source) {
        if (source.inputSchemaJson() == null) return null;
        try {
            var mapper = Json31.mapper();
            var declared = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(source.inputSchemaJson());
            if (declared == null || !declared.isObject() && !declared.isBoolean()) {
                throw new IllegalArgumentException("Expected a JSON Schema object or boolean");
            }
            ObjectNode schema;
            if (declared instanceof ObjectNode object) {
                schema = object;
            } else {
                schema = mapper.createObjectNode();
                schema.putArray("allOf").add(declared);
            }
            // A root resource ID keeps fragment references within this source when the document is bundled.
            URI base = URI.create("pixivdownload:/schedule/owner-" + UriUtils.encodePathSegment(pluginId, StandardCharsets.UTF_8)
                    + "/source-" + UriUtils.encodePathSegment(source.sourceType(), StandardCharsets.UTF_8)
                    + "/v" + source.definitionVersion() + "/schema");
            JsonNode id = schema.get("$id");
            if (id != null && !id.isTextual()) throw new IllegalArgumentException("Schema resource ID must be a string");
            URI resource = id == null || id.textValue().isEmpty() ? base : base.resolve(id.textValue());
            if (resource.getRawFragment() != null && !resource.getRawFragment().isEmpty()) {
                throw new IllegalArgumentException("Schema resource ID contains a fragment");
            }
            schema.put("$id", resource.toASCIIString());
            return schema;
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            LOG.warn("Schedule source declared an invalid input schema: owner={} source={}", pluginId, source.sourceType());
            return null;
        }
    }
}
