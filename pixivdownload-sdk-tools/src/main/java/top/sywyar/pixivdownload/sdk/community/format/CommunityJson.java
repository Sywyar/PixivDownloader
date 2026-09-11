package top.sywyar.pixivdownload.sdk.community.format;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** 社区合同共用严格解析及唯一 Schema 入口；不改变宿主已有 JSON 兼容策略。 */
public final class CommunityJson {
    public static final int MAX_DEPTH = 16;
    public static final int MAX_STRING_UNITS = 16_384;
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    public static final String SCHEMA_ID = "urn:pixivdownloader:community:v1";
    private static final BigDecimal SAFE = BigDecimal.valueOf(MAX_SAFE_INTEGER);
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH)
                    .maxStringLength(MAX_STRING_UNITS).maxNameLength(MAX_STRING_UNITS)
                    .maxNumberLength(1024 * 1024).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final SchemaRegistry SCHEMAS = schemas();

    private CommunityJson() { }

    /** 每类文档的整体字节预算；大报告只按固定引用进入记录。 */
    public enum Kind {
        SUBMISSION("submission", 64), PUBLISHER("publisher", 256), BINDING("binding", 64),
        ROTATION("rotation", 64), STATUS_REQUEST("statusRequest", 64), TRANSFER("transfer", 64),
        APPROVAL("approval", 64), REVIEW("review", 256), PUBLISHED("published", 64), AUDIT("audit", 256),
        DECISION("decision", 64), DIRECTORY_ROOT("directoryRoot", 256), DIRECTORY_SHARD("directoryShard", 1024);
        private final String definition;
        private final int maximumBytes;
        Kind(String definition, int kib) { this.definition = definition; this.maximumBytes = kib * 1024; }
        public int maximumBytes() { return maximumBytes; }
        public String definition() { return definition; }
    }

    /** 原始字节与解析值一起冻结，后续引用始终使用原始字节摘要。 */
    public static final class Document {
        private final Kind kind;
        private final byte[] bytes;
        private final JsonNode value;
        private Document(Kind kind, byte[] bytes, JsonNode value) {
            this.kind = kind; this.bytes = bytes.clone(); this.value = value.deepCopy();
        }
        public Kind kind() { return kind; }
        public byte[] bytes() { return bytes.clone(); }
        public JsonNode value() { return value.deepCopy(); }
        public String sha256() { return CommunityJson.sha256(bytes); }
        public <T> T as(Class<T> type) {
            try { return JSON.treeToValue(value, type); }
            catch (IOException e) { throw new ContractException("SCHEMA_INVALID", ""); }
        }
    }

    public static Document read(Kind kind, InputStream input) throws IOException {
        return parse(kind, input.readNBytes(kind.maximumBytes() + 1));
    }

    public static Document parse(Kind kind, byte[] bytes) {
        JsonNode node = strictTree(bytes, kind.maximumBytes());
        validateStructure(kind.definition(), node);
        return new Document(kind, bytes, node);
    }

    /** 独立报告的读取预算由执行器提供；共享结构不另设报告条数或文件大小门槛。 */
    public static void validateStructure(String definition, JsonNode node) {
        var errors = SCHEMAS.getSchema(SchemaLocation.of(SCHEMA_ID + "#/$defs/" + definition)).validate(node);
        if (!errors.isEmpty()) {
            var error = errors.get(0);
            String code = java.util.Set.of("maxLength", "maxItems", "maximum", "maxProperties").contains(error.getKeyword())
                    ? "LIMIT_EXCEEDED" : "SCHEMA_INVALID";
            throw new ContractException(code, error.getInstanceLocation().toString(),
                    Map.of("keyword", error.getKeyword()));
        }
    }

    /** 只解析数据编码与通用预算；结构仍由 parse 的固定 Schema 验证。 */
    public static JsonNode strictTree(byte[] bytes, int maximumBytes) {
        if (bytes.length > maximumBytes) throw limit("", maximumBytes, "bytes");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\ufeff")) throw new ContractException("SCHEMA_INVALID", "");
            JsonNode node = JSON.readTree(text);
            if (node == null || node.isMissingNode()) throw new ContractException("SCHEMA_INVALID", "");
            inspect(node, "");
            return node;
        } catch (StreamConstraintsException e) {
            throw new ContractException("LIMIT_EXCEEDED", "");
        } catch (IOException | ArithmeticException e) {
            throw new ContractException("SCHEMA_INVALID", "");
        }
    }

    /** JCS 正文只含 schemaVersion 和 payload，排除请求 ID 与所有证明。 */
    public static byte[] canonicalBody(Document request) {
        JsonNode node = request.value();
        if (!node.has("schemaVersion") || !node.has("payload")) throw new ContractException("SCHEMA_INVALID", "/payload");
        ObjectNode body = JSON.createObjectNode();
        body.set("schemaVersion", node.get("schemaVersion"));
        body.set("payload", node.get("payload"));
        try { return new JsonCanonicalizer(encode(body)).getEncodedUTF8(); }
        catch (IOException e) { throw new ContractException("SCHEMA_INVALID", "/payload"); }
    }

    public static byte[] encode(Object value) {
        try {
            JsonNode tree = JSON.valueToTree(value);
            inspect(tree, "");
            return JSON.writeValueAsString(tree).getBytes(StandardCharsets.UTF_8);
        }
        catch (IOException e) { throw new ContractException("SCHEMA_INVALID", ""); }
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static ContractException limit(String field, long maximum, String unit) {
        return new ContractException("LIMIT_EXCEEDED", field, Map.of("maximum", Long.toString(maximum), "unit", unit));
    }

    private static void inspect(JsonNode node, String field) {
        if (node.isTextual()) inspectString(node.textValue(), field);
        if (node.isNumber()) {
            BigDecimal value = node.decimalValue();
            if (value.abs().compareTo(SAFE) > 0 || value.stripTrailingZeros().scale() > 0) {
                throw new ContractException("SCHEMA_INVALID", field, Map.of("expected", "safe-integer"));
            }
        }
        if (node.isObject()) node.fields().forEachRemaining(entry -> {
            inspectString(entry.getKey(), field);
            inspect(entry.getValue(), field + "/" + entry.getKey().replace("~", "~0").replace("/", "~1"));
        });
        if (node.isArray()) for (int i = 0; i < node.size(); i++) inspect(node.get(i), field + "/" + i);
    }

    private static void inspectString(String value, String field) {
        if (value.length() > MAX_STRING_UNITS) throw limit(field, MAX_STRING_UNITS, "UTF-16");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new ContractException("SCHEMA_INVALID", field);
            } else if (Character.isLowSurrogate(c)) throw new ContractException("SCHEMA_INVALID", field);
        }
    }

    private static SchemaRegistry schemas() {
        try (InputStream input = CommunityJson.class.getResourceAsStream("/community/v1/community.schema.json")) {
            if (input == null) throw new IllegalStateException("community schema missing");
            String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
                    .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true)
                            .typeLoose(false).build())
                    .schemas(Map.of(SCHEMA_ID, schema)));
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
}
