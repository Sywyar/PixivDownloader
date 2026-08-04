package top.sywyar.pixivdownload.download.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.download.response.LayoutFeedbackStateResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 布局调查决策状态 JSON 线格式：序列化必须输出小写 wire value（{@code submitted} /
 * {@code never} / {@code snoozed}），反序列化同时接受小写与 f4d587b6 及更早版本写出的
 * 旧大写值，未知值必须拒绝。
 */
@DisplayName("布局调查决策 JSON 线格式")
class LayoutFeedbackDecisionJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SURVEY_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @Test
    @DisplayName("三个枚举序列化输出小写 wire value")
    void serializesToLowercaseWireValues() throws Exception {
        assertThat(MAPPER.writeValueAsString(LayoutFeedbackDecision.SUBMITTED))
                .isEqualTo("\"submitted\"");
        assertThat(MAPPER.writeValueAsString(LayoutFeedbackDecision.NEVER))
                .isEqualTo("\"never\"");
        assertThat(MAPPER.writeValueAsString(LayoutFeedbackDecision.SNOOZED))
                .isEqualTo("\"snoozed\"");
    }

    @Test
    @DisplayName("小写 wire value 反序列化成功")
    void deserializesLowercaseWireValues() throws Exception {
        assertThat(MAPPER.readValue("\"submitted\"", LayoutFeedbackDecision.class))
                .isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(MAPPER.readValue("\"never\"", LayoutFeedbackDecision.class))
                .isEqualTo(LayoutFeedbackDecision.NEVER);
        assertThat(MAPPER.readValue("\"snoozed\"", LayoutFeedbackDecision.class))
                .isEqualTo(LayoutFeedbackDecision.SNOOZED);
    }

    @Test
    @DisplayName("旧大写枚举名仍可读取（旧状态文件兼容）")
    void deserializesLegacyUpperCaseNames() throws Exception {
        assertThat(MAPPER.readValue("\"SUBMITTED\"", LayoutFeedbackDecision.class))
                .isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(MAPPER.readValue("\"NEVER\"", LayoutFeedbackDecision.class))
                .isEqualTo(LayoutFeedbackDecision.NEVER);
        assertThat(MAPPER.readValue("\"SNOOZED\"", LayoutFeedbackDecision.class))
                .isEqualTo(LayoutFeedbackDecision.SNOOZED);
    }

    @Test
    @DisplayName("未知状态拒绝，fromWire 仍返回 null")
    void unknownValueRejectedButFromWireStaysNullSafe() {
        assertThatThrownBy(() -> MAPPER.readValue("\"bogus\"", LayoutFeedbackDecision.class))
                .isInstanceOf(com.fasterxml.jackson.core.JacksonException.class);
        assertThat(LayoutFeedbackDecision.fromWire("bogus")).isNull();
        assertThat(LayoutFeedbackDecision.fromWire(null)).isNull();
    }

    @Test
    @DisplayName("LayoutFeedbackStateResponse 真实 JSON 输出小写 status，绝不出现大写枚举名")
    void responseJsonUsesLowercaseStatus() throws Exception {
        LayoutFeedbackStateResponse response = new LayoutFeedbackStateResponse(
                true,
                true,
                "plf_" + "ab".repeat(32),
                1_786_000_000_000L,
                3,
                new LayoutFeedbackStateEntry(SURVEY_ID, LayoutFeedbackDecision.SUBMITTED, 1, 0),
                Map.of("pixiv-batch-landscape", new LayoutFeedbackSeenEntry(1, 2)));

        String json = MAPPER.writeValueAsString(response);

        assertThat(json).contains("\"status\":\"submitted\"");
        assertThat(json).doesNotContain("SUBMITTED");
        assertThat(json).doesNotContain("NEVER");
        assertThat(json).doesNotContain("SNOOZED");
    }

    @Test
    @DisplayName("LayoutFeedbackStateResponse JSON 携带数值 serverTime，不因状态序列化而丢失")
    void responseJsonCarriesServerTime() throws Exception {
        LayoutFeedbackStateResponse response = new LayoutFeedbackStateResponse(
                true,
                true,
                "plf_" + "ab".repeat(32),
                1_786_000_000_000L,
                0,
                null,
                Map.of());

        String json = MAPPER.writeValueAsString(response);

        assertThat(json).contains("\"serverTime\":1786000000000");
    }
}
