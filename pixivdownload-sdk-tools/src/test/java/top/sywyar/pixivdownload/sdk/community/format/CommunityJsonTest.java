package top.sywyar.pixivdownload.sdk.community.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("社区 JSON 编码与结构合同")
class CommunityJsonTest {
    private static final String BINDING = """
            {"schemaVersion":1,"pluginId":"example-minimal",
             "owner":{"accountId":"123456789012345678901234567890","accountType":"User","publisherId":"official"},
             "effectiveRequestId":null,"updatedAt":"2026-01-02T03:04:05Z"}
            """;

    @Test
    @DisplayName("JCS 使用 RFC 标准的 UTF16 排序并保留数组顺序和数字序列化")
    void canonicalizesStandardBoundaries() throws Exception {
        // RFC 8785 3.2.3 的同组属性码点；值只保留测试所需的序号。
        String input = "{\"€\":5,\"\\r\":1,\"דּ\":7,\"1\":2,\"😀\":6,\"\\u0080\":3,\"ö\":4}";
        String expected = "{\"\\r\":1,\"1\":2,\"\u0080\":3,\"ö\":4,\"€\":5,\"😀\":6,\"דּ\":7}";
        assertThat(new org.erdtman.jcs.JsonCanonicalizer(input).getEncodedUTF8())
                .isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
        assertThat(new org.erdtman.jcs.JsonCanonicalizer("[333333333.33333329,1E30,4.50,2e-3,1e-27]").getEncodedString())
                .isEqualTo("[333333333.3333333,1e+30,4.5,0.002,1e-27]");
        // 社区文档只接受安全整数；标准 JCS 数字支持不放宽外层合同。
        assertThatThrownBy(() -> tree("[1E30]")).isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("每类 Schema 都有真实结构样本，未知字段和缺失版本不能通过")
    void validatesEveryDocumentStructure() throws Exception {
        for (var kind : CommunityJson.Kind.values()) {
            String path = kind == CommunityJson.Kind.SUBMISSION ? "submission.json" : "structure/" + kind.definition() + ".json";
            try (var input = getClass().getResourceAsStream("/community/v1/vectors/" + path)) {
                byte[] bytes = java.util.Objects.requireNonNull(input, path).readAllBytes();
                var parsed = CommunityJson.parse(kind, bytes);
                var unknown = (com.fasterxml.jackson.databind.node.ObjectNode) parsed.value();
                unknown.put("unknown", true);
                assertThatThrownBy(() -> CommunityJson.parse(kind, CommunityJson.encode(unknown)))
                        .as(kind.name()).isInstanceOf(ContractException.class);
                var missing = (com.fasterxml.jackson.databind.node.ObjectNode) parsed.value();
                missing.remove("schemaVersion");
                assertThatThrownBy(() -> CommunityJson.parse(kind, CommunityJson.encode(missing)))
                        .as(kind.name()).isInstanceOf(ContractException.class);
                byte[] exact = Arrays.copyOf(bytes, kind.maximumBytes());
                Arrays.fill(exact, bytes.length, exact.length, (byte) ' ');
                assertThatCode(() -> CommunityJson.parse(kind, exact)).as(kind.name()).doesNotThrowAnyException();
                assertLimit(() -> CommunityJson.parse(kind, Arrays.copyOf(exact, exact.length + 1)));
            }
        }
    }

    @Test
    @DisplayName("JCS 排序且保留 Unicode，排除 ID 与证明但绑定正文变化")
    void canonicalizesOnlyOperationBody() throws Exception {
        try (var input = getClass().getResourceAsStream("/community/v1/vectors/structure/rotation.json")) {
            var document = CommunityJson.parse(CommunityJson.Kind.ROTATION, java.util.Objects.requireNonNull(input).readAllBytes());
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) document.value();
            ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("payload")).put("explanation", "测试😀");
            var first = CommunityJson.parse(CommunityJson.Kind.ROTATION, CommunityJson.encode(node));
            byte[] canonical = CommunityJson.canonicalBody(first);
            assertThat(new String(canonical, StandardCharsets.UTF_8)).startsWith("{\"payload\":{\"explanation\":\"测试😀\",")
                    .endsWith(",\"schemaVersion\":1}").doesNotContain("proofs", "requestId");
            node.put("requestId", "cd".repeat(32));
            ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("proofs")).set("oldKey", node.get("proofs").get("newKey"));
            var second = CommunityJson.parse(CommunityJson.Kind.ROTATION, CommunityJson.encode(node));
            assertThat(CommunityJson.canonicalBody(second)).isEqualTo(canonical);
            assertThat(second.sha256()).isNotEqualTo(first.sha256());
            ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("payload")).put("explanation", "Changed");
            assertThat(CommunityJson.canonicalBody(CommunityJson.parse(CommunityJson.Kind.ROTATION, CommunityJson.encode(node))))
                    .isNotEqualTo(canonical);
        }
    }

    @Test
    @DisplayName("真实 Schema 启用格式校验并区分缺失空值和错误类型")
    void validatesSchemaAndFormats() {
        assertThatCode(() -> binding(BINDING)).doesNotThrowAnyException();
        for (String invalid : new String[]{BINDING.replace("2026-01-02", "2026-02-30"),
                BINDING.replace("03:04:05Z", "03:04:05.1Z"), BINDING.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                BINDING.replace("\"effectiveRequestId\":null,", ""), BINDING.replace("null", "\"\""),
                BINDING.replace("\"pluginId\"", "\"unknown\""), BINDING.replace("\"User\"", "\"user\""),
                BINDING.replace("example-minimal", "example-minimal\\n"),
                BINDING.replace("official", "official\\u00a0"), BINDING.replace("official", "\\u2000official"),
                BINDING.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                BINDING.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"unexpected\":true")}) {
            assertThatThrownBy(() -> binding(invalid)).isInstanceOf(ContractException.class);
        }
    }

    @Test
    @DisplayName("拒绝重复键尾随值非法 UTF8 BOM 和非成对代理字符")
    void rejectsAmbiguousEncoding() {
        for (String input : new String[]{"{\"a\":1,\"a\":2}", "{\"a\":{\"b\":1,\"b\":2}}",
                "{} {}", "{} null", "{}x", "\ufeff{}", "", "\"\\ud800\"", "\"\\udc00\"",
                "{\"\\ud800\":0}", "NaN", "1.5", "9007199254740992", "-9007199254740992"}) {
            assertThatThrownBy(() -> tree(input)).isInstanceOf(ContractException.class);
        }
        assertThatThrownBy(() -> CommunityJson.strictTree(new byte[]{(byte) 0xc0, (byte) 0x80}, 100))
                .isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> CommunityJson.encode(java.util.Map.of("text", "\ud800")))
                .isInstanceOf(ContractException.class);
        for (String valid : new String[]{"9007199254740991", "-9007199254740991", "1.0", "1e0", "\"\\ud83d\\ude00\""}) {
            assertThatCode(() -> tree(valid)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("深度和 UTF16 字符串预算在真实解析边界生效")
    void enforcesDepthAndStringBudgets() {
        assertThatCode(() -> tree("[".repeat(16) + "0" + "]".repeat(16))).doesNotThrowAnyException();
        assertLimit(() -> tree("[".repeat(17) + "0" + "]".repeat(17)));
        assertThatCode(() -> tree("\"" + "😀".repeat(8192) + "\"")).doesNotThrowAnyException();
        assertLimit(() -> tree("\"" + "😀".repeat(8192) + "x\""));
        assertThatCode(() -> tree("{\"" + "x".repeat(16384) + "\":0}")).doesNotThrowAnyException();
        assertLimit(() -> tree("{\"" + "x".repeat(16385) + "\":0}"));
    }

    @Test
    @DisplayName("每份文档的字节读取只消费上限加一并保留原始摘要")
    void boundsActualReadsAndFreezesBytes() throws Exception {
        int maximum = CommunityJson.Kind.BINDING.maximumBytes();
        byte[] source = BINDING.getBytes(StandardCharsets.UTF_8);
        byte[] exact = Arrays.copyOf(source, maximum);
        Arrays.fill(exact, source.length, exact.length, (byte) ' ');
        var document = CommunityJson.parse(CommunityJson.Kind.BINDING, exact);
        assertThat(document.sha256()).isEqualTo(CommunityJson.sha256(exact));
        document.bytes()[0] = 0;
        ((com.fasterxml.jackson.databind.node.ObjectNode) document.value()).put("schemaVersion", 9);
        assertThat(document.bytes()[0]).isEqualTo((byte) '{');
        assertThat(document.value().get("schemaVersion").intValue()).isEqualTo(1);
        ByteArrayInputStream oversized = new ByteArrayInputStream(new byte[maximum * 2]);
        assertLimit(() -> CommunityJson.read(CommunityJson.Kind.BINDING, oversized));
        assertThat(oversized.available()).isEqualTo(maximum - 1);
        assertLimit(() -> CommunityJson.parse(CommunityJson.Kind.BINDING, Arrays.copyOf(exact, maximum + 1)));
    }

    private static void binding(String value) { CommunityJson.parse(CommunityJson.Kind.BINDING, value.getBytes(StandardCharsets.UTF_8)); }
    private static void tree(String value) { CommunityJson.strictTree(value.getBytes(StandardCharsets.UTF_8), 1024 * 1024); }
    private static void assertLimit(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ContractException.class, e -> assertThat(e.code()).isEqualTo("LIMIT_EXCEEDED"));
    }
}
