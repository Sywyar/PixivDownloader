package top.sywyar.pixivdownload.download.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.download.response.LayoutFeedbackStateResponse;

import java.util.List;

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
                3,
                LayoutFeedbackDecision.SUBMITTED,
                false,
                0L,
                List.of("pixiv-batch-landscape"));

        String json = MAPPER.writeValueAsString(response);

        assertThat(json).contains("\"status\":\"submitted\"");
        assertThat(json).doesNotContain("SUBMITTED");
        assertThat(json).doesNotContain("NEVER");
        assertThat(json).doesNotContain("SNOOZED");
    }

    @Test
    @DisplayName("LayoutFeedbackStateResponse 携带 canShow / retryAfterMs / seenLayouts，不携带服务端绝对时间")
    void responseJsonCarriesAuthoritativeViewOnly() throws Exception {
        LayoutFeedbackStateResponse response = new LayoutFeedbackStateResponse(
                true,
                true,
                "plf_" + "ab".repeat(32),
                2,
                LayoutFeedbackDecision.SNOOZED,
                false,
                1_200_000L,
                List.of("pixiv-batch-landscape", "pixiv-batch-portrait", "pixiv-batch-alt"));

        String json = MAPPER.writeValueAsString(response);

        assertThat(json).contains("\"canShow\":false");
        assertThat(json).contains("\"retryAfterMs\":1200000");
        assertThat(json).contains("\"seenLayouts\"");
        assertThat(json).contains("pixiv-batch-landscape");
        // 服务端绝对时间点一律不进入浏览器响应。
        assertThat(json).doesNotContain("serverTime");
        assertThat(json).doesNotContain("snoozedUntil");
        assertThat(json).doesNotContain("updatedAt");
        assertThat(json).doesNotContain("firstSeenAt");
        assertThat(json).doesNotContain("lastSeenAt");
    }
}
