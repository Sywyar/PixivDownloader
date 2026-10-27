package top.sywyar.pixivdownload.plugin.web.remote;

import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("远程 JSON 边界的无损整数、Unicode、预算及显式空值")
class RemoteValuesTest {
    public record Input(long number, String text, List<Integer> values, Map<String, String> labels, String note) {}
    private static final String SCHEMA = """
            {"$ref":"#/$defs/Input","$defs":{"Input":{"type":"object","additionalProperties":false,
             "required":["number","text","values","labels"],"properties":{
              "number":{"type":"integer","minimum":-9007199254740991,"maximum":9007199254740991},
              "text":{"type":"string","maxLength":2},
              "values":{"type":"array","items":{"type":"integer","minimum":-2147483648,"maximum":2147483647}},
              "labels":{"type":"object","additionalProperties":{"type":"string"}},
              "note":{"anyOf":[{"type":"string"},{"type":"null"}]}}}}}
            """;
    private static final String VALID = """
            {"number":9007199254740991,"text":"😀","values":[1],"labels":{"private key":"value"}}
            """;

    private static com.fasterxml.jackson.databind.JsonNode parse(String value) throws Exception {
        return RemoteValues.parse(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test @DisplayName("安全整数无损往返，缺失的可空属性解码为 null，码点长度跨语言一致")
    void losslessRoundTrip() throws Exception {
        var schema = parse(SCHEMA); var input = parse(VALID);
        RemoteValues.validate(schema, input);
        var value = (Input) RemoteValues.decode(input, Input.class);
        assertThat(value.number()).isEqualTo(9_007_199_254_740_991L);
        assertThat(value.note()).isNull(); assertThat(value.text()).isEqualTo("😀");
        var output = RemoteValues.encode(value, Input.class);
        RemoteValues.validate(schema, output);
        assertThat(output.path("number").longValue()).isEqualTo(value.number());
        assertThat(output.get("note").isNull()).isTrue();
        RemoteValues.validate(schema, parse(VALID.replace("9007199254740991", "1e0")));
    }

    @Test @DisplayName("未知字段、缺失、非法整数与孤立代理项不泄露输入或敏感 Map 键")
    void rejectsMalformedValues() throws Exception {
        var schema = parse(SCHEMA);
        for (String value : List.of(VALID.replace("9007199254740991", "9007199254740992"),
                VALID.replace("9007199254740991", "1.1"), VALID.replace("9007199254740991", "1e999999"),
                VALID.replace("\"😀\"", "null"), VALID.replace("😀", "abc"),
                VALID.replace("\"number\"", "\"unknown-private-field\""), "{}",
                VALID.replace("😀", "\\ud800"))) {
            assertThatThrownBy(() -> RemoteValues.validate(schema, parse(value)))
                    .isInstanceOf(RemoteValues.Invalid.class).hasMessage("REMOTE_VALUE_INVALID").hasNoCause()
                    .satisfies(error -> assertThat(error.getSuppressed()).isEmpty());
        }
        for (String value : List.of("{\"private key\":1,\"private key\":2}", "{bad secret}", "true false")) {
            assertThatThrownBy(() -> parse(value)).hasMessage("REMOTE_VALUE_INVALID").hasNoCause();
        }
    }

    @Test @DisplayName("请求字节、值节点和输出累计文本均有界")
    void budgets() throws Exception {
        byte[] bytes = new byte[RemoteValues.MAX_BYTES + 1];
        assertThatThrownBy(() -> RemoteValues.parse(new ByteArrayInputStream(bytes))).hasMessage("REMOTE_BUDGET");
        var schema = parse(SCHEMA); var input = (com.fasterxml.jackson.databind.node.ObjectNode) parse(VALID);
        var values = input.putArray("values"); for (int i = 0; i < 16385; i++) values.add(i);
        assertThatThrownBy(() -> RemoteValues.validate(schema, input)).hasMessage("REMOTE_BUDGET");
        var large = new Input(1, "x", List.of(), Map.of("a","x".repeat(600000),"b","x".repeat(600000)), null);
        assertThatThrownBy(() -> RemoteValues.encode(large, Input.class)).hasMessage("REMOTE_BUDGET");
    }
}
