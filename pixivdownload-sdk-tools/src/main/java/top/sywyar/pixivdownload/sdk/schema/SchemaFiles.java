package top.sywyar.pixivdownload.sdk.schema;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** SDK 本地样本校验只消费工具生成的自包含 Schema；引用不会触发网络或其它文件读取。 */
public final class SchemaFiles {
    static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
    static final int MAX_BYTES = 1024 * 1024;
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private SchemaFiles() { }

    /** 校验独立 JSON 样本；无效数据抛出稳定错误码，不包含样本正文。 */
    public static void check(Path schema, Path data) throws IOException {
        JsonNode definition = read(schema);
        if (!DIALECT.equals(definition.path("$schema").asText())) throw new IOException("SDK_SCHEMA_DIALECT");
        new Shape(definition).check();
        var validator = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(definition);
        var failures = validator.validate(read(data));
        if (!failures.isEmpty()) throw new IOException("SDK_SCHEMA_DATA_INVALID");
    }

    /** 只认生成器的结构，nullable 的唯一非 null 分支不会形成组合式搜索。 */
    private static final class Shape {
        private final JsonNode root;
        private final JsonNode definitions;
        private int nodes;

        Shape(JsonNode root) { this.root = root; definitions = root.path("$defs"); }

        void check() throws IOException {
            keys(root, Set.of("$schema", "$ref", "$defs"));
            if (root.size() != 3 || !definitions.isObject() || definitions.size() > 512) throw invalid();
            reference(root.path("$ref"));
            for (JsonNode record : definitions) {
                charge();
                keys(record, Set.of("type", "properties", "additionalProperties", "required"));
                if (record.size() != 4 || !record.path("type").asText().equals("object")
                        || !record.path("properties").isObject() || !record.path("required").isArray()
                        || !record.path("additionalProperties").isBoolean() || record.path("additionalProperties").booleanValue()) throw invalid();
                Set<String> required = new HashSet<>();
                for (JsonNode name : record.path("required")) {
                    charge();
                    if (!name.isTextual() || !record.path("properties").has(name.textValue()) || !required.add(name.textValue())) throw invalid();
                }
                for (JsonNode property : record.path("properties")) value(property, true);
            }
        }

        private void value(JsonNode schema, boolean nullable) throws IOException {
            charge();
            if (!schema.isObject()) throw invalid();
            if (schema.has("anyOf")) {
                keys(schema, Set.of("anyOf"));
                JsonNode alternatives = schema.path("anyOf");
                if (!nullable || !alternatives.isArray() || alternatives.size() != 2
                        || !alternatives.get(1).isObject() || alternatives.get(1).size() != 1
                        || !alternatives.get(1).path("type").asText().equals("null")) throw invalid();
                value(alternatives.get(0), false);
                return;
            }
            if (schema.has("$ref")) {
                keys(schema, Set.of("$ref"));
                reference(schema.get("$ref"));
                return;
            }
            switch (schema.path("type").asText()) {
                case "string" -> {
                    keys(schema, Set.of("type", "enum", "minLength", "maxLength"));
                    bounds(schema, "minLength", "maxLength");
                    if (schema.has("enum")) {
                        if (!schema.get("enum").isArray() || schema.get("enum").isEmpty()) throw invalid();
                        for (JsonNode item : schema.get("enum")) { charge(); if (!item.isTextual()) throw invalid(); }
                    }
                }
                case "integer" -> {
                    keys(schema, Set.of("type", "minimum", "maximum"));
                    if (!schema.path("minimum").isIntegralNumber() || !schema.path("maximum").isIntegralNumber()
                            || !schema.path("minimum").canConvertToLong() || !schema.path("maximum").canConvertToLong()
                            || schema.path("minimum").bigIntegerValue().compareTo(schema.path("maximum").bigIntegerValue()) > 0) throw invalid();
                }
                case "boolean" -> keys(schema, Set.of("type"));
                case "array" -> {
                    keys(schema, Set.of("type", "items", "minItems", "maxItems"));
                    bounds(schema, "minItems", "maxItems");
                    value(schema.path("items"), true);
                }
                case "object" -> {
                    keys(schema, Set.of("type", "additionalProperties"));
                    value(schema.path("additionalProperties"), true);
                }
                default -> throw invalid();
            }
        }

        private void reference(JsonNode value) throws IOException {
            if (!value.isTextual() || !value.asText().startsWith("#/$defs/")) throw new IOException("SDK_SCHEMA_REFERENCE");
            String name = value.asText().substring("#/$defs/".length());
            if (name.isEmpty() || name.contains("/") || name.contains("~") || name.contains("%") || !definitions.has(name)) {
                throw new IOException("SDK_SCHEMA_REFERENCE");
            }
        }

        private void charge() throws IOException { if (++nodes > 16_384) throw new IOException("SDK_SCHEMA_NODE_LIMIT"); }
        private static void keys(JsonNode node, Set<String> allowed) throws IOException {
            if (!node.isObject()) throw invalid();
            var names = node.fieldNames();
            while (names.hasNext()) if (!allowed.contains(names.next())) throw invalid();
        }
        private static void bounds(JsonNode node, String minimum, String maximum) throws IOException {
            for (String key : new String[]{minimum, maximum}) {
                if (node.has(key) && (!node.get(key).isIntegralNumber() || !node.get(key).canConvertToInt() || node.get(key).intValue() < 0)) throw invalid();
            }
            if (node.has(minimum) && node.has(maximum) && node.get(minimum).intValue() > node.get(maximum).intValue()) throw invalid();
        }
        private static IOException invalid() { return new IOException("SDK_SCHEMA_DOCUMENT_INVALID"); }
    }

    static JsonNode read(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("SDK_SCHEMA_FILE");
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("SDK_SCHEMA_FILE_LIMIT");
            JsonNode value;
            try {
                value = JSON.readTree(bytes);
            } catch (JsonProcessingException failure) {
                var location = failure.getLocation();
                String position = location == null ? "" : ": line=" + Math.max(0, location.getLineNr())
                        + ", column=" + Math.max(0, location.getColumnNr());
                // 原始异常可能包含非法 token、重复字段名或样本正文，不能进入 CLI 或异常链。
                throw new IOException("SDK_SCHEMA_JSON_INVALID" + position);
            }
            if (value == null) throw new IOException("SDK_SCHEMA_DOCUMENT_INVALID");
            return value;
        }
    }

    static byte[] encode(JsonNode schema) throws IOException {
        new Shape(schema).check();
        byte[] bytes = (JSON.writerWithDefaultPrettyPrinter().writeValueAsString(schema) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("SDK_SCHEMA_FILE_LIMIT");
        // 输出须满足与 schema-check 相同的 JSON 深度预算，避免生成无法消费的文件。
        JSON.readTree(bytes);
        return bytes;
    }
}
