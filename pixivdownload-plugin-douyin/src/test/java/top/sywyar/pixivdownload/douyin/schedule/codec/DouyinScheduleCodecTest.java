package top.sywyar.pixivdownload.douyin.schedule.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceTypes;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDraft;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRelation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("抖音计划任务持久化编解码")
class DouyinScheduleCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DouyinScheduleCodec codec = new DouyinScheduleCodec(objectMapper);

    @Test
    @DisplayName("八类来源定义会规范化为稳定且最小的 JSON")
    void normalizesAllSourceDefinitions() throws Exception {
        Map<String, String> definitions = new LinkedHashMap<>();
        definitions.put(DouyinSourceTypes.USER,
                "{\"fetchLimit\":\"25\",\"source\":{\"userId\":\" MS4w.LjAB_test-id \"}}");
        definitions.put(DouyinSourceTypes.SEARCH,
                "{\"source\":{\"keyword\":\" 风景 摄影 \"},\"fetchLimit\":0}");
        definitions.put(DouyinSourceTypes.COLLECTION,
                "{\"source\":{\"collectionId\":\"73510001\"},\"fetchLimit\":3}");
        definitions.put(DouyinSourceTypes.MUSIC,
                "{\"source\":{\"musicId\":\"73510002\"},\"fetchLimit\":4}");
        definitions.put(DouyinSourceTypes.ACCOUNT_OWN_WORKS,
                "{\"source\":{},\"fetchLimit\":5}");
        definitions.put(DouyinSourceTypes.ACCOUNT_LIKED_WORKS,
                "{\"source\":{},\"fetchLimit\":6}");
        definitions.put(DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS,
                "{\"source\":{},\"fetchLimit\":7}");
        definitions.put(DouyinSourceTypes.ACCOUNT_FAVORITE_COLLECTION,
                "{\"source\":{\"collectionId\":\"73510003\"},\"fetchLimit\":8}");

        for (Map.Entry<String, String> entry : definitions.entrySet()) {
            var prepared = codec.prepare(draft(entry.getKey(), entry.getValue()));
            var decoded = codec.decodeDefinition(prepared, entry.getKey());

            assertThat(prepared.sourceType()).isEqualTo(entry.getKey());
            assertThat(prepared.definitionSchema()).isEqualTo(DouyinScheduleCodec.DEFINITION_SCHEMA);
            assertThat(prepared.definitionVersion()).isEqualTo(1);
            assertThat(objectMapper.readTree(prepared.definitionJson()).fieldNames())
                    .toIterable().containsExactly("source", "fetchLimit");
            assertThat(decoded.fetchLimit()).isBetween(0, 25);
        }
        assertThat(codec.decodeDefinition(
                codec.prepare(draft(DouyinSourceTypes.USER, definitions.get(DouyinSourceTypes.USER))),
                DouyinSourceTypes.USER).sourceId()).isEqualTo("MS4w.LjAB_test-id");
    }

    @Test
    @DisplayName("缺省每轮上限会规范化为一百且未知或敏感字段被拒绝")
    void defaultsFetchLimitAndRejectsUnknownOrSensitiveFields() throws Exception {
        var prepared = codec.prepare(draft(
                DouyinSourceTypes.SEARCH,
                "{\"source\":{\"keyword\":\"猫\"}}"));
        assertThat(prepared.definitionJson())
                .isEqualTo("{\"source\":{\"keyword\":\"猫\"},\"fetchLimit\":100}");

        assertThatThrownBy(() -> codec.prepare(draft(
                DouyinSourceTypes.SEARCH,
                "{\"source\":{\"keyword\":\"猫\"},\"fetchLimit\":1,\"cookie\":\"secret\"}")))
                .isInstanceOf(ScheduledExecutionException.class)
                .extracting(failure -> ((ScheduledExecutionException) failure).category())
                .isEqualTo(ScheduledFailure.Category.INVALID_DEFINITION);
        assertThatThrownBy(() -> codec.prepare(draft(
                DouyinSourceTypes.ACCOUNT_OWN_WORKS,
                "{\"source\":{\"accountKey\":\"should-not-persist\"},\"fetchLimit\":1}")))
                .isInstanceOf(ScheduledExecutionException.class);
        assertThatThrownBy(() -> codec.prepare(draft(
                DouyinSourceTypes.USER,
                "{\"source\":{\"userId\":\"https://www.douyin.com/user/x\"},\"fetchLimit\":1}")))
                .isInstanceOf(ScheduledExecutionException.class);
    }

    @Test
    @DisplayName("作品引用只保存字符串身份且来源关系不携带网址或凭证")
    void workAndRelationRoundTripRemainReconstructableAndSecretFree() throws Exception {
        var relation = codec.createRelation(
                DouyinSourceTypes.COLLECTION, "73510001", "合集标题", 7);
        var work = codec.createWork(
                "7351234567890123456", "作品标题", "作者", relation);

        assertThat(work.key().workType()).isEqualTo("douyin");
        assertThat(work.key().id()).isEqualTo("7351234567890123456");
        assertThat(work.payloadSchema()).isEqualTo(DouyinScheduleCodec.WORK_SCHEMA);
        assertThat(work.payloadJson())
                .isEqualTo("{\"workId\":\"7351234567890123456\"}")
                .doesNotContain("http", "cookie", "sessionid");
        assertThat(codec.decodeWorkId(work)).isEqualTo("7351234567890123456");
        assertThat(codec.decodeRelation(relation)).satisfies(decoded -> {
            assertThat(decoded.sourceType()).isEqualTo(DouyinSourceTypes.COLLECTION);
            assertThat(decoded.sourceId()).isEqualTo("73510001");
            assertThat(decoded.sourceTitle()).isEqualTo("合集标题");
            assertThat(decoded.sourceOrder()).isEqualTo(7);
        });
        assertThat(relation.payloadJson()).doesNotContain("http", "cookie", "sessionid");

        ScheduledWorkRelation tampered = new ScheduledWorkRelation(
                DouyinSourceTypes.COLLECTION,
                "sessionid=leaked-value",
                DouyinScheduleCodec.RELATION_SCHEMA,
                DouyinScheduleCodec.RELATION_VERSION,
                "{}");
        assertThatThrownBy(() -> codec.decodeRelation(tampered))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.category())
                                .isEqualTo(ScheduledFailure.Category.PAYLOAD_UNSUPPORTED));
    }

    @Test
    @DisplayName("有界 SHA-256 frontier 与续扫锚点可稳定往返")
    void checkpointRoundTripUsesBoundedHashedIdentities() throws Exception {
        String first = codec.identityHash("7351234567890123456");
        String second = codec.identityHash("7351234567890123457");
        var checkpoint = codec.encodeCheckpoint(
                new DouyinScheduleCodec.CheckpointState(List.of(first, second), second));
        var decoded = codec.decodeCheckpoint(checkpoint);

        assertThat(checkpoint.schema()).isEqualTo(DouyinScheduleCodec.CHECKPOINT_SCHEMA);
        assertThat(checkpoint.payloadJson())
                .doesNotContain("7351234567890123456", "7351234567890123457");
        assertThat(decoded.frontier()).containsExactly(first, second);
        assertThat(decoded.resumeAfter()).isEqualTo(second);
    }

    @Test
    @DisplayName("续传恢复标记可往返且旧检查点默认不进入恢复扫描")
    void checkpointRecoveryFlagIsBackwardCompatible() throws Exception {
        String identity = codec.identityHash("7351234567890123456");
        var recovering = codec.encodeCheckpoint(
                new DouyinScheduleCodec.CheckpointState(List.of(identity), null, true));
        var legacy = new ScheduledCheckpoint(
                DouyinScheduleCodec.CHECKPOINT_SCHEMA,
                DouyinScheduleCodec.CHECKPOINT_VERSION,
                "{\"frontier\":[\"" + identity + "\"]}");

        assertThat(codec.decodeCheckpoint(recovering).recovery()).isTrue();
        assertThat(codec.decodeCheckpoint(legacy).recovery()).isFalse();
    }

    @Test
    @DisplayName("五千个完整 SHA-256 身份可往返且检查点保持在宿主字节上限内")
    void maximumFrontierFitsCheckpointPayloadLimit() throws Exception {
        List<String> frontier = new ArrayList<>(DouyinScheduleCodec.MAX_FRONTIER_IDENTITIES);
        for (int index = 0; index < DouyinScheduleCodec.MAX_FRONTIER_IDENTITIES; index++) {
            frontier.add(codec.identityHash("work-" + index));
        }

        ScheduledCheckpoint checkpoint = codec.encodeCheckpoint(
                new DouyinScheduleCodec.CheckpointState(
                        frontier, frontier.get(frontier.size() - 1), true));

        assertThat(checkpoint.payloadJson().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(ScheduledCheckpoint.MAX_PAYLOAD_BYTES);
        assertThat(codec.decodeCheckpoint(checkpoint).frontier())
                .containsExactlyElementsOf(frontier);
    }

    private static ScheduledTaskDraft draft(String sourceType, String json) {
        return new ScheduledTaskDraft(
                1L,
                sourceType,
                DouyinScheduleCodec.DEFINITION_SCHEMA,
                DouyinScheduleCodec.DEFINITION_VERSION,
                json,
                new ScheduledTaskPresentation("任务", null, Map.of()));
    }
}
