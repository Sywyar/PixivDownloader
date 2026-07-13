package top.sywyar.pixivdownload.schedule.definition;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledCredentialText;
import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledSensitiveFieldNames;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 对进入持久化边界的定义与执行计划做宿主侧前置和最终校验。 */
public final class ScheduleTaskDefinitionValidator {

    private static final int MAX_EMBEDDED_JSON_DEPTH = 16;

    private final ObjectMapper objectMapper;

    public ScheduleTaskDefinitionValidator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public ScheduledTaskDefinition validatePrepared(
            ScheduledTaskDefinition definition,
            long expectedTaskId,
            String expectedSourceType,
            String expectedDefinitionSchema,
            int expectedDefinitionVersion) {
        Objects.requireNonNull(definition, "prepared schedule definition");
        if (definition.taskId() != expectedTaskId
                || !expectedSourceType.equals(definition.sourceType())
                || !expectedDefinitionSchema.equals(definition.definitionSchema())
                || expectedDefinitionVersion != definition.definitionVersion()) {
            throw new IllegalArgumentException("prepared schedule definition changed a host-stamped field");
        }

        String definitionJson = definition.definitionJson();
        validateUnicodeScalarText(definitionJson, "schedule definition JSON");
        if (definitionJson.getBytes(StandardCharsets.UTF_8).length
                > ScheduledTaskDefinition.MAX_DEFINITION_BYTES) {
            throw new IllegalArgumentException("schedule definition JSON exceeds size limit");
        }
        JsonNode root = parseSingleJsonValue(definitionJson, true);
        validateJsonNode(root, 0);
        ScheduledTaskPresentation presentation = validatePresentation(definition.presentation());
        return new ScheduledTaskDefinition(
                expectedTaskId,
                expectedSourceType,
                expectedDefinitionSchema,
                expectedDefinitionVersion,
                definitionJson,
                presentation);
    }

    public void validatePlan(
            ScheduledSourceDescriptor descriptor,
            ScheduledExecutionPlan plan) {
        ScheduleExecutionPlanGate.validate(descriptor, plan);
    }

    public ScheduledTaskPresentation validatePresentation(ScheduledTaskPresentation value) {
        ScheduledTaskPresentation presentation = value == null
                ? ScheduledTaskPresentation.empty()
                : value;
        validateUnicodeScalarText(presentation.title(), "schedule presentation title");
        validateUnicodeScalarText(presentation.summary(), "schedule presentation summary");
        Map<String, String> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : presentation.attributes().entrySet()) {
            validateUnicodeScalarText(entry.getKey(), "schedule presentation attribute key");
            validateUnicodeScalarText(entry.getValue(), "schedule presentation attribute value");
            attributes.put(entry.getKey(), entry.getValue());
        }
        return new ScheduledTaskPresentation(
                presentation.title(), presentation.summary(), attributes);
    }

    private JsonNode parseSingleJsonValue(String json, boolean requireObject) {
        try (JsonParser parser = objectMapper.createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = objectMapper.readTree(parser);
            if (root == null || (requireObject && !root.isObject())) {
                throw new IllegalArgumentException("schedule definition must contain one JSON object");
            }
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("schedule definition contains trailing JSON tokens");
            }
            return root;
        } catch (IOException failure) {
            throw new IllegalArgumentException("schedule definition JSON is invalid");
        }
    }

    private void validateJsonNode(JsonNode node, int embeddedDepth) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String fieldName = entry.getKey();
                validateUnicodeScalarText(fieldName, "schedule definition field name");
                if (ScheduledSensitiveFieldNames.isSensitiveFieldName(fieldName)) {
                    throw new IllegalArgumentException("schedule definition contains a credential field");
                }
                validateJsonNode(entry.getValue(), embeddedDepth);
            });
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> validateJsonNode(child, embeddedDepth));
            return;
        }
        if (!node.isTextual()) {
            return;
        }
        String text = node.textValue();
        validateUnicodeScalarText(text, "schedule definition text");
        if (ScheduledCredentialText.containsCredentialMaterial(text)) {
            throw new IllegalArgumentException("schedule definition contains credential material");
        }
        String trimmed = text.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return;
        }
        JsonNode embedded = readEmbeddedJson(trimmed);
        if (embedded == null) {
            return;
        }
        if (embedded.isObject() || embedded.isArray()) {
            if (embeddedDepth >= MAX_EMBEDDED_JSON_DEPTH) {
                throw new IllegalArgumentException("schedule definition embedded JSON is too deeply nested");
            }
            validateJsonNode(embedded, embeddedDepth + 1);
        }
    }

    private JsonNode readEmbeddedJson(String json) {
        try {
            return parseSingleJsonValue(json, false);
        } catch (IllegalArgumentException strictFailure) {
            try {
                JsonNode permissive = objectMapper.readTree(json);
                if (permissive != null && (permissive.isObject() || permissive.isArray())) {
                    throw strictFailure;
                }
            } catch (JsonProcessingException ignored) {
                // 以括号开头的普通来源文本不是完整 JSON，不按嵌入载荷解释。
            }
            return null;
        }
    }

    private static void validateUnicodeScalarText(String value, String label) {
        if (value == null) {
            return;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\0') {
                throw new IllegalArgumentException(label + " must not contain NUL");
            }
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(label + " contains an invalid surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(label + " contains an invalid surrogate");
            }
        }
    }
}
