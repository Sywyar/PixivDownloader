package top.sywyar.pixivdownload.schedule.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRelation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("通用计划作品 pending 编解码")
class ScheduleWorkPersistenceCodecTest {

    private final ScheduleWorkPersistenceCodec codec =
            new ScheduleWorkPersistenceCodec(new ObjectMapper());

    @Test
    @DisplayName("不透明 ID 按字符串原样往返")
    void roundTripsOpaqueStringIds() {
        List<String> ids = List.of(
                "001",
                "1",
                "550e8400-e29b-41d4-a716-446655440000",
                "922337203685477580812345678901234567890",
                "author_work-2026_07_12");

        for (String id : ids) {
            ScheduledWork original = work("example.work", id, "{\"opaque\":\"" + id + "\"}");
            ScheduledPendingWork pending = codec.toPendingWork(
                    7L, original, "RETRYABLE", "{\"attempt\":\"first\"}",
                    0, 100L, null);

            assertThat(pending.workId()).isEqualTo(id);
            assertThat(codec.fromPendingWork(pending)).isEqualTo(original);
        }
    }

    @Test
    @DisplayName("相同 ID 的不同作品类型保持独立复合身份")
    void keepsSameIdDistinctAcrossWorkTypes() {
        ScheduledPendingWork image = codec.toPendingWork(
                9L, work("example.image", "001", "{\"id\":\"001\"}"),
                "RETRYABLE", null, 0, 10L, null);
        ScheduledPendingWork video = codec.toPendingWork(
                9L, work("example.video", "001", "{\"id\":\"001\"}"),
                "RETRYABLE", null, 0, 10L, null);

        assertThat(image.workId()).isEqualTo(video.workId());
        assertThat(image.workType()).isNotEqualTo(video.workType());
        assertThat(codec.fromPendingWork(image).key())
                .isEqualTo(new ScheduledWorkKey("example.image", "001"));
        assertThat(codec.fromPendingWork(video).key())
                .isEqualTo(new ScheduledWorkKey("example.video", "001"));
    }

    @Test
    @DisplayName("payload 原文与 presentation relations 完整往返")
    void roundTripsPayloadPresentationAndRelations() {
        String payload = " { \"source\" : \"author-7\", \"items\" : [1, 2] } ";
        ScheduledWorkPresentation presentation = new ScheduledWorkPresentation(
                "原始标题", "author-7", "asset:thumb-1",
                Map.of("rating", "safe", "kind", "video"));
        List<ScheduledWorkRelation> relations = List.of(
                new ScheduledWorkRelation(
                        "author", "author-7", "example.author-relation", 1,
                        "{\"followed\":false}"),
                new ScheduledWorkRelation(
                        "collection", "collection-9", "example.collection-relation", 2,
                        "{\"position\":\"003\"}"));
        ScheduledWork original = new ScheduledWork(
                new ScheduledWorkKey("example.video", "video-001"),
                "example.video-reference", 3, payload, presentation, relations);

        ScheduledPendingWork pending = codec.toPendingWork(
                11L, original, "REMOTE_RETRY", "{\"category\":\"network\"}",
                2, 123L, 456L);

        assertThat(pending.payloadJson()).isEqualTo(payload);
        assertThat(pending.attempts()).isEqualTo(2);
        assertThat(pending.firstSeenTime()).isEqualTo(123L);
        assertThat(pending.lastAttemptTime()).isEqualTo(456L);
        assertThat(codec.fromPendingWork(pending)).isEqualTo(original);
    }

    @Test
    @DisplayName("恢复时拒绝非法 JSON 形状和尾随 JSON")
    void rejectsInvalidJsonShapes() {
        ScheduledPendingWork invalidPayload = pending("not-json", "{}", "[]");
        ScheduledPendingWork invalidPresentation = pending("{}", "[]", "[]");
        ScheduledPendingWork invalidRelations = pending("{}", "{}", "{}");
        ScheduledPendingWork trailingPayload = pending("{} {}", "{}", "[]");

        assertThatThrownBy(() -> codec.fromPendingWork(invalidPayload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");
        assertThatThrownBy(() -> codec.fromPendingWork(invalidPresentation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("presentation must be a JSON object");
        assertThatThrownBy(() -> codec.fromPendingWork(invalidRelations))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relations must be a JSON array");
        assertThatThrownBy(() -> codec.fromPendingWork(trailingPayload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one JSON value");
    }

    @Test
    @DisplayName("恢复时重新执行稳定 API 的 ID payload 和 relation 上限")
    void reappliesApiConstructorLimitsWhenRestoring() {
        String oversizedId = "x".repeat(ScheduledWorkKey.MAX_ID_BYTES + 1);
        String oversizedPayload = "\"" + "x".repeat(ScheduledWork.MAX_PAYLOAD_BYTES + 1) + "\"";
        String oversizedRelationPayload = "x".repeat(ScheduledWorkRelation.MAX_PAYLOAD_BYTES + 1);
        String relations = "[{\"relationType\":\"author\",\"relationId\":\"a\","
                + "\"payloadSchema\":\"example.relation\",\"payloadVersion\":1,"
                + "\"payloadJson\":\"" + oversizedRelationPayload + "\"}]";

        assertThatThrownBy(() -> codec.fromPendingWork(new ScheduledPendingWork(
                1L, "example.work", oversizedId, "example.payload", 1,
                "{}", "[]", "{}", "RETRYABLE", null, 0, 1L, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("work id exceeds size limit");
        assertThatThrownBy(() -> codec.fromPendingWork(pending(oversizedPayload, "{}", "[]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("work payload exceeds size limit");
        assertThatThrownBy(() -> codec.fromPendingWork(pending("{}", "{}", relations)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relation payload exceeds size limit");
    }

    @Test
    @DisplayName("写入和恢复都拒绝 payload presentation relations 与原因中的凭证材料")
    void rejectsCredentialMaterialAtEveryPersistenceBoundary() {
        ScheduledWork secretPayload = work(
                "example.work", "id-1", "{\"apiToken\":\"top-secret\"}");
        ScheduledWork phpSessionPayload = work(
                "example.work", "id-phpsessid", "{\"PHPSESSID\":\"12345_opaque-session-value\"}");
        ScheduledWork connectSidPayload = work(
                "example.work", "id-connect-sid", "{\"connect.sid\":\"opaque-value\"}");
        ScheduledWork secretSchema = new ScheduledWork(
                new ScheduledWorkKey("example.work", "id-schema"),
                "token=top-secret", 1, "{}", ScheduledWorkPresentation.empty(), List.of());
        ScheduledWork secretRelation = new ScheduledWork(
                new ScheduledWorkKey("example.work", "id-3"),
                "example.payload", 1, "{}", ScheduledWorkPresentation.empty(),
                List.of(new ScheduledWorkRelation(
                        "collection", "c-1", "example.relation", 1,
                        "{\"temporaryUrl\":\"https://example.invalid/file\"}")));

        assertThatThrownBy(() -> codec.toPendingWork(
                1L, secretPayload, "RETRYABLE", null, 0, 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential material");
        assertThatThrownBy(() -> codec.toPendingWork(
                1L, phpSessionPayload, "RETRYABLE", null, 0, 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential material");
        assertThatThrownBy(() -> codec.toPendingWork(
                1L, connectSidPayload, "RETRYABLE", null, 0, 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential material");
        assertThatThrownBy(() -> codec.toPendingWork(
                1L, secretSchema, "RETRYABLE", null, 0, 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential material");
        assertThatThrownBy(() -> new ScheduledWorkPresentation(
                "Authorization: Bearer abc.def", null, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential material");
        assertThatThrownBy(() -> codec.toPendingWork(
                1L, secretRelation, "RETRYABLE", null, 0, 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential material");
        assertThatThrownBy(() -> codec.toPendingWork(
                1L, work("example.work", "id-4", "{}"),
                "RETRYABLE", "{\"password\":\"secret\"}", 0, 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential material");

        assertThatThrownBy(() -> codec.fromPendingWork(pending(
                "{\"nested\":\"{\\\"cookie\\\":\\\"PHPSESSID=abc\\\"}\"}",
                "{}", "[]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential material");
        assertThatThrownBy(() -> codec.fromPendingWork(pending(
                "{\"PHPSESSID\":\"12345_opaque-session-value\"}", "{}", "[]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential material");
        assertThatThrownBy(() -> codec.fromPendingWork(pending(
                "{}", "{\"title\":\"Authorization: Bearer abc.def\","
                        + "\"author\":null,\"thumbnailReference\":null,\"attributes\":{}}",
                "[]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential material");
    }

    @Test
    @DisplayName("作品、检查点与内嵌 JSON 都拒绝重复键绕过凭证扫描")
    void rejectsDuplicateKeysAtEveryOpaqueJsonBoundary() {
        ScheduledWork duplicatePayload = work(
                "example.work", "id-duplicate",
                "{\"note\":\"PHPSESSID=secret\",\"note\":\"safe\"}");
        ScheduledCheckpoint duplicateCheckpoint = new ScheduledCheckpoint(
                "example.checkpoint", 1,
                "{\"note\":\"PHPSESSID=secret\",\"note\":\"safe\"}");
        ScheduledWork embeddedDuplicate = work(
                "example.work", "id-embedded",
                "{\"nested\":\"{\\\"cookie\\\":\\\"hidden\\\","
                        + "\\\"cookie\\\":\\\"safe\\\"}\"}");

        assertThatThrownBy(() -> codec.toPendingWork(
                1L, duplicatePayload, "RETRYABLE", null, 0, 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid JSON");
        assertThatThrownBy(() -> codec.validateCheckpoint(duplicateCheckpoint))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid JSON");
        assertThatThrownBy(() -> codec.toPendingWork(
                1L, embeddedDuplicate, "RETRYABLE", null, 0, 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid JSON");
    }

    private static ScheduledWork work(String workType, String id, String payloadJson) {
        return new ScheduledWork(
                new ScheduledWorkKey(workType, id),
                "example.payload", 1, payloadJson,
                ScheduledWorkPresentation.empty(), List.of());
    }

    private static ScheduledPendingWork pending(
            String payloadJson, String presentationJson, String relationsJson) {
        return new ScheduledPendingWork(
                1L, "example.work", "001", "example.payload", 1,
                payloadJson, relationsJson, presentationJson,
                "RETRYABLE", null, 0, 1L, null);
    }
}
