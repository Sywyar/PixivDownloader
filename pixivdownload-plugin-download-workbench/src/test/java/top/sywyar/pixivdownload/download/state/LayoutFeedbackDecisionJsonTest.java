package top.sywyar.pixivdownload.download.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.download.response.LayoutFeedbackStateResponse;

import java.util.List;
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
    // 状态文档序列化使用与 Store 相同的 NON_NULL 配置（null 字段不输出）。
    private static final ObjectMapper DOCUMENT_MAPPER = new ObjectMapper()
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
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
                "018f35a1-7c40-8abc-8def-0123456789ab",
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
                "018f35a1-7c40-8abc-8def-0123456789ab",
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

    /* ============================================================
       状态文档 v1 / v2 字段互斥（JSON 线格式层）
    ============================================================ */

    private String stateJson(String surveyId, String status) {
        return "{\"surveyId\":\"" + surveyId
                + "\",\"status\":\"" + status + "\",\"updatedAt\":1,\"snoozedUntil\":0}";
    }

    @Test
    @DisplayName("v2 文档序列化只输出 states，绝不输出 state 字段")
    void v2DocumentJsonOmitsStateField() throws Exception {
        LayoutFeedbackStateEntry entry = new LayoutFeedbackStateEntry(
                SURVEY_ID, LayoutFeedbackDecision.SUBMITTED, 1L, 0L);
        LayoutFeedbackStateDocument document = new LayoutFeedbackStateDocument(
                2, 3L, null, Map.of(SURVEY_ID, entry), Map.of());

        String json = DOCUMENT_MAPPER.writeValueAsString(document);

        assertThat(json).contains("\"schemaVersion\":2");
        assertThat(json).contains("\"states\"");
        assertThat(json).doesNotContain("\"state\":");
    }

    @Test
    @DisplayName("反序列化 v1 无 states 字段文档：state 迁移进 states 表，大写枚举继续兼容")
    void v1DocumentWithoutStatesDeserializes() throws Exception {
        String json = "{\"schemaVersion\":1,\"revision\":5,"
                + "\"state\":" + stateJson(SURVEY_ID, "SUBMITTED") + ",\"seen\":{}}";

        LayoutFeedbackStateDocument document = MAPPER.readValue(json, LayoutFeedbackStateDocument.class);

        assertThat(document.schemaVersion()).isEqualTo(1);
        assertThat(document.revision()).isEqualTo(5);
        assertThat(document.states().get(SURVEY_ID).status())
                .isEqualTo(LayoutFeedbackDecision.SUBMITTED);
    }

    @Test
    @DisplayName("反序列化 v1 state=null 且无 states 字段：迁移为空 states")
    void v1NullStateDocumentWithoutStatesDeserializes() throws Exception {
        String json = "{\"schemaVersion\":1,\"revision\":5,\"state\":null,\"seen\":{}}";

        LayoutFeedbackStateDocument document = MAPPER.readValue(json, LayoutFeedbackStateDocument.class);

        assertThat(document.revision()).isEqualTo(5);
        assertThat(document.states()).isEmpty();
    }

    @Test
    @DisplayName("反序列化 v1 同时含 state 与 states 字段：歧义协议拒绝")
    void v1AmbiguousDocumentRejected() {
        String json = "{\"schemaVersion\":1,\"revision\":1,"
                + "\"state\":" + stateJson(SURVEY_ID, "SUBMITTED") + ",\"states\":{},\"seen\":{}}";

        assertThatThrownBy(() -> MAPPER.readValue(json, LayoutFeedbackStateDocument.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("反序列化 v2 同时含 state 与 states 字段：歧义协议拒绝")
    void v2AmbiguousDocumentRejected() {
        String json = "{\"schemaVersion\":2,\"revision\":1,"
                + "\"state\":" + stateJson(SURVEY_ID, "SUBMITTED") + ",\"states\":{},\"seen\":{}}";

        assertThatThrownBy(() -> MAPPER.readValue(json, LayoutFeedbackStateDocument.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
