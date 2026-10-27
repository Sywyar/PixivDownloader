package top.sywyar.pixivdownload.plugin.web.remote;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 生成操作使用固定纯值 codec，不启用插件的 Jackson 模块、类型标签或自定义序列化器。 */
final class RemoteValues {
    static final int MAX_BYTES = 1024 * 1024;
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final BigDecimal SAFE = BigDecimal.valueOf(9_007_199_254_740_991L);

    static JsonNode parse(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new Invalid("REMOTE_BUDGET");
        try {
            JsonNode node = JSON.readTree(bytes);
            if (node == null) throw new Invalid("REMOTE_VALUE_INVALID");
            return node;
        } catch (JsonProcessingException invalid) { throw new Invalid("REMOTE_VALUE_INVALID"); }
    }

    static void validate(JsonNode schema, JsonNode value) {
        validate(schema, schema, value, new Budget(), 0);
    }

    private static void validate(JsonNode root, JsonNode schema, JsonNode value, Budget budget, int depth) {
        budget.node(depth);
        if (schema.has("anyOf")) {
            if (value.isNull()) return;
            validate(root, schema.path("anyOf").get(0), value, budget, depth + 1); return;
        }
        if (schema.has("$ref")) {
            String ref = schema.path("$ref").asText();
            if (!ref.startsWith("#/$defs/")) throw new Invalid("REMOTE_VALUE_INVALID");
            JsonNode target = root.path("$defs").get(ref.substring(8));
            if (target == null) throw new Invalid("REMOTE_VALUE_INVALID");
            validate(root, target, value, budget, depth + 1); return;
        }
        switch (schema.path("type").asText()) {
            case "boolean" -> { if (!value.isBoolean()) invalid(); }
            case "integer" -> {
                if (!value.isNumber()) invalid();
                BigDecimal number = value.decimalValue();
                if (number.compareTo(SAFE.negate()) < 0 || number.compareTo(SAFE) > 0
                        || number.stripTrailingZeros().scale() > 0
                        || number.compareTo(schema.path("minimum").decimalValue()) < 0
                        || number.compareTo(schema.path("maximum").decimalValue()) > 0) invalid();
            }
            case "string" -> {
                if (!value.isTextual()) invalid();
                String text = value.textValue(); budget.text(text);
                int size = text.codePointCount(0, text.length());
                if (size < schema.path("minLength").asInt(0) || size > schema.path("maxLength").asInt(Integer.MAX_VALUE)) invalid();
                if (schema.has("enum")) {
                    boolean found = false;
                    for (JsonNode choice : schema.path("enum")) if (choice.equals(value)) found = true;
                    if (!found) invalid();
                }
            }
            case "array" -> {
                if (!value.isArray() || value.size() < schema.path("minItems").asInt(0)
                        || value.size() > schema.path("maxItems").asInt(Integer.MAX_VALUE)) invalid();
                for (JsonNode item : value) validate(root, schema.path("items"), item, budget, depth + 1);
            }
            case "object" -> {
                if (!value.isObject()) invalid();
                for (JsonNode name : schema.path("required")) if (!value.has(name.asText())) invalid();
                var fields = value.fields();
                while (fields.hasNext()) {
                    var field = fields.next(); budget.text(field.getKey());
                    JsonNode property = schema.has("properties") ? schema.path("properties").get(field.getKey()) : schema.get("additionalProperties");
                    if (property == null || !property.isObject()) invalid();
                    validate(root, property, field.getValue(), budget, depth + 1);
                }
            }
            default -> invalid();
        }
    }

    static Object decode(JsonNode value, Type type) throws ReflectiveOperationException {
        if (value == null || value.isNull()) return null;
        if (type instanceof ParameterizedType parameterized) {
            if (parameterized.getRawType() == List.class) {
                List<Object> result = new ArrayList<>();
                for (JsonNode item : value) result.add(decode(item, parameterized.getActualTypeArguments()[0]));
                return Collections.unmodifiableList(result);
            }
            if (parameterized.getRawType() == Map.class) {
                Map<String, Object> result = new LinkedHashMap<>();
                var fields = value.fields();
                while (fields.hasNext()) { var field = fields.next(); result.put(field.getKey(), decode(field.getValue(), parameterized.getActualTypeArguments()[1])); }
                return Collections.unmodifiableMap(result);
            }
        }
        if (!(type instanceof Class<?> raw)) throw new Invalid("REMOTE_VALUE_INVALID");
        if (raw == String.class) return value.textValue();
        if (raw == boolean.class || raw == Boolean.class) return value.booleanValue();
        if (raw == byte.class || raw == Byte.class) return value.decimalValue().byteValueExact();
        if (raw == short.class || raw == Short.class) return value.decimalValue().shortValueExact();
        if (raw == int.class || raw == Integer.class) return value.decimalValue().intValueExact();
        if (raw == long.class || raw == Long.class) return value.decimalValue().longValueExact();
        if (raw.isEnum()) {
            for (Object constant : raw.getEnumConstants()) if (((Enum<?>) constant).name().equals(value.textValue())) return constant;
            throw new Invalid("REMOTE_VALUE_INVALID");
        }
        if (!raw.isRecord()) throw new Invalid("REMOTE_VALUE_INVALID");
        var fields = raw.getRecordComponents();
        Object[] arguments = new Object[fields.length]; Class<?>[] types = new Class<?>[fields.length];
        for (int i = 0; i < fields.length; i++) {
            types[i] = fields[i].getType();
            arguments[i] = decode(value.get(fields[i].getName()), fields[i].getGenericType());
        }
        return raw.getConstructor(types).newInstance(arguments);
    }

    static JsonNode encode(Object value, Type type) throws ReflectiveOperationException {
        return encode(value, type, new Budget(), 0);
    }

    private static JsonNode encode(Object value, Type type, Budget budget, int depth) throws ReflectiveOperationException {
        budget.node(depth);
        if (value == null) return NullNode.instance;
        if (type instanceof ParameterizedType generic) {
            if (generic.getRawType() == List.class) {
                var result = JSON.createArrayNode();
                for (Object item : (List<?>) value) result.add(encode(item, generic.getActualTypeArguments()[0], budget, depth + 1));
                return result;
            }
            if (generic.getRawType() == Map.class) {
                var result = JSON.createObjectNode();
                for (var item : ((Map<?, ?>) value).entrySet()) {
                    String key = (String) item.getKey(); budget.text(key);
                    result.set(key, encode(item.getValue(), generic.getActualTypeArguments()[1], budget, depth + 1));
                }
                return result;
            }
        }
        if (!(type instanceof Class<?> raw)) throw new Invalid("REMOTE_VALUE_INVALID");
        if (raw == String.class) { budget.text((String) value); return TextNode.valueOf((String) value); }
        if (raw == boolean.class || raw == Boolean.class) return BooleanNode.valueOf((Boolean) value);
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            return LongNode.valueOf(((Number) value).longValue());
        if (raw.isEnum()) return TextNode.valueOf(((Enum<?>) value).name());
        if (!raw.isRecord()) throw new Invalid("REMOTE_VALUE_INVALID");
        var result = JSON.createObjectNode();
        for (var field : raw.getRecordComponents()) {
            budget.text(field.getName());
            result.set(field.getName(), encode(field.getAccessor().invoke(value), field.getGenericType(), budget, depth + 1));
        }
        return result;
    }

    static final class Invalid extends RuntimeException {
        final String code;
        Invalid(String code) { super(code, null, false, false); this.code = code; }
    }
    private static void invalid() { throw new Invalid("REMOTE_VALUE_INVALID"); }

    private static final class Budget {
        int nodes; int bytes;
        void node(int depth) { if (++nodes > 16384 || depth > 32) throw new Invalid("REMOTE_BUDGET"); }
        void text(String value) {
            if (value.length() > MAX_BYTES) throw new Invalid("REMOTE_BUDGET");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) invalid();
                } else if (Character.isLowSurrogate(c)) invalid();
            }
            bytes += value.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_BYTES) throw new Invalid("REMOTE_BUDGET");
        }
    }
}
