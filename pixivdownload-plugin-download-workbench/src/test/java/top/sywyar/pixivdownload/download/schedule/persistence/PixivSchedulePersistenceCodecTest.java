package top.sywyar.pixivdownload.download.schedule.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Pixiv 计划任务持久化编解码")
class PixivSchedulePersistenceCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PixivSchedulePersistenceCodec codec;

    @BeforeEach
    void setUp() {
        codec = new PixivSchedulePersistenceCodec(objectMapper);
    }

    @Test
    @DisplayName("定义信封固定正式 schema 并生成安全展示快照")
    void createsDefinitionAndPresentation() {
        String json = """
                {"kind":"novel","source":{"word":"猫","order":"date_d","maxPages":-1},"fetchLimit":25}
                """;

        ScheduledTaskDefinition definition = codec.createDefinition(0, "猫小说", "search", json);

        assertThat(definition.taskId()).isZero();
        assertThat(definition.sourceType()).isEqualTo("search");
        assertThat(definition.definitionSchema()).isEqualTo(PixivSchedulePersistenceCodec.DEFINITION_SCHEMA);
        assertThat(definition.definitionVersion()).isEqualTo(1);
        assertThat(definition.definitionJson()).isEqualTo(json);
        assertThat(definition.presentation().title()).isEqualTo("猫小说");
        assertThat(definition.presentation().summary()).isNull();
        assertThat(definition.presentation().attributes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "kind", "novel",
                "source.word", "猫",
                "source.order", "date_d",
                "source.maxPages", "-1",
                "fetchLimit", "25"));
    }

    @Test
    @DisplayName("定义递归拒绝凭证字段和值且错误不回显 secret")
    void definitionRejectsCredentialMaterial() {
        List<String> unsafeDefinitions = List.of(
                "{\"kind\":\"illust\",\"source\":{\"cookie\":\"PHPSESSID=definition-secret\"}}",
                "{\"kind\":\"illust\",\"source\":{\"note\":\"Authorization: Bearer definition-secret\"}}",
                "{\"kind\":\"illust\",\"source\":{\"note\":\"Proxy-Authorization: Basic definition-secret\"}}",
                "{\"kind\":\"illust\",\"source\":{\"access_token\":\"definition-secret\"}}",
                "{\"kind\":\"illust\",\"source\":{\"tokenCount\":\"definition-secret\"}}",
                "{\"kind\":\"illust\",\"source\":{\"cookiePresent\":\"definition-secret\"}}",
                "{\"kind\":\"illust\",\"source\":{\"tokenCount!\":\"definition-secret\"}}",
                "{\"kind\":\"illust\",\"source\":{\"token-co-unt\":\"definition-secret\"}}",
                "{\"kind\":\"illust\",\"source\":{\"tokenCountValue\":\"definition-secret\"}}",
                "{\"kind\":\"illust\",\"source\":{\"cookiePresentValue\":\"definition-secret\"}}",
                "{\"kind\":\"illust\",\"source\":{\"sidCountHeader\":\"definition-secret\"}}",
                "{\"kind\":\"illust\",\"source\":{\"tokenPresentCount\":\"definition-secret\"}}",
                "{\"kind\":\"illust\",\"source\":{\"cookieEnabledVersion\":\"definition-secret\"}}",
                "{\"kind\":\"illust\",\"source\":{\"sidCountPresent\":\"definition-secret\"}}",
                "{\"kind\":\"illust\",\"source\":{\"note\":\"tokenCount=definition-secret\"}}",
                "{\"kind\":\"illust\",\"source\":{\"url\":\"https://example.test/a?X-Amz-Signature=definition-secret\"}}",
                "{\"kind\":\"illust\",\"source\":{\"url\":\"https://example.test/a?X-Amz-Credential=definition-secret\"}}");

        for (String json : unsafeDefinitions) {
            assertThatThrownBy(() -> codec.createDefinition(0, "任务", "user-new", json))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("schedule definition contains forbidden credential material")
                    .hasMessageNotContaining("definition-secret");
        }
        assertThatThrownBy(() -> codec.createDefinition(
                0, "Authorization: Basic definition-secret", "user-new",
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"1\"}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("schedule task name contains forbidden credential material")
                .hasMessageNotContaining("definition-secret");
    }

    @Test
    @DisplayName("定义与内嵌 JSON 都拒绝重复键绕过凭证扫描")
    void rejectsDuplicateKeysAtEveryPixivJsonBoundary() {
        String duplicateDefinition = "{\"kind\":\"illust\",\"note\":"
                + "\"PHPSESSID=secret\",\"note\":\"safe\"}";
        String trailingDefinition = "{} {}";
        String embeddedDuplicate = "{\"message\":\"{\\\"cookie\\\":\\\"hidden\\\","
                + "\\\"cookie\\\":\\\"safe\\\"}\"}";

        assertThatThrownBy(() -> codec.createDefinition(
                0, "任务", "user-new", duplicateDefinition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid JSON");
        assertThatThrownBy(() -> codec.createDefinition(
                0, "任务", "user-new", trailingDefinition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one JSON value");
        assertThatThrownBy(() -> codec.createDefinition(
                0, "任务", "user-new", embeddedDuplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid JSON");
    }

    @Test
    @DisplayName("long 最大值水位线按 JSON 字符串精确保真")
    void checkpointUsesDecimalString() throws Exception {
        ScheduledCheckpoint checkpoint = codec.encodeCheckpoint(Long.MAX_VALUE);

        assertThat(checkpoint.schema()).isEqualTo(PixivSchedulePersistenceCodec.CHECKPOINT_SCHEMA);
        assertThat(checkpoint.version()).isEqualTo(1);
        assertThat(objectMapper.readTree(checkpoint.payloadJson()).path("watermarkId").isTextual()).isTrue();
        assertThat(objectMapper.readTree(checkpoint.payloadJson()).path("watermarkId").asText())
                .isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(codec.decodeCheckpoint(checkpoint)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("检查点严格拒绝数值 JSON 与错误 schema")
    void checkpointRejectsNonCanonicalPayload() {
        ScheduledCheckpoint numeric = new ScheduledCheckpoint(
                PixivSchedulePersistenceCodec.CHECKPOINT_SCHEMA, 1, "{\"watermarkId\":42}");
        ScheduledCheckpoint foreign = new ScheduledCheckpoint("foreign", 1, "{\"watermarkId\":\"42\"}");

        assertThatThrownBy(() -> codec.decodeCheckpoint(numeric))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decimal string");
        assertThatThrownBy(() -> codec.decodeCheckpoint(foreign))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema or version");
    }

    @Test
    @DisplayName("作品信封把 workId 保存为字符串并严格核对 key")
    void workEnvelopePreservesStringIdentity() throws Exception {
        ScheduledWork work = codec.createWorkEnvelope("novel", Long.toString(Long.MAX_VALUE));

        assertThat(work.key().workType()).isEqualTo("novel");
        assertThat(work.key().id()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(objectMapper.readTree(work.payloadJson()).path("workId").isTextual()).isTrue();
        assertThat(codec.decodeWorkId(work)).isEqualTo(Long.toString(Long.MAX_VALUE));

        ScheduledWork mismatched = new ScheduledWork(
                work.key(), work.payloadSchema(), work.payloadVersion(), "{\"workId\":\"7\"}",
                work.presentation(), work.relations());
        assertThatThrownBy(() -> codec.decodeWorkId(mismatched))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differ");
    }

    @Test
    @DisplayName("作品载荷解码拒绝凭证材料及伪装的元数据字段")
    void workPayloadRejectsCredentialMaterial() {
        ScheduledWork base = codec.createWorkEnvelope("illust", "7");
        ScheduledWork unsafePayload = new ScheduledWork(
                base.key(), base.payloadSchema(), base.payloadVersion(),
                "{\"workId\":\"7\",\"note\":\"Bearer pending-secret\"}",
                base.presentation(), base.relations());
        assertThatThrownBy(() -> codec.decodeWorkId(unsafePayload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("schedule work payload contains forbidden credential material")
                .hasMessageNotContaining("pending-secret");
        for (String fieldName : List.of(
                "tokenCount",
                "cookiePresent",
                "tokenCount!",
                "token-co-unt",
                "tokenCountValue",
                "cookiePresentValue",
                "sidCountHeader",
                "tokenPresentCount",
                "cookieEnabledVersion",
                "sidCountPresent")) {
            ScheduledWork disguisedPayload = new ScheduledWork(
                    base.key(), base.payloadSchema(), base.payloadVersion(),
                    "{\"workId\":\"7\",\"" + fieldName + "\":\"pending-secret\"}",
                    base.presentation(), base.relations());
            assertThatThrownBy(() -> codec.decodeWorkId(disguisedPayload))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("schedule work payload contains forbidden credential material")
                    .hasMessageNotContaining("pending-secret");
        }
    }

    @Test
    @DisplayName("凭证策略状态无损保存已确认警告时间")
    void policyStatePreservesAcknowledgedWarningTime() throws Exception {
        String state = codec.encodePolicyState(Long.MAX_VALUE);

        assertThat(objectMapper.readTree(state).path("schema").asText())
                .isEqualTo(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_STATE_SCHEMA);
        assertThat(objectMapper.readTree(state).path("version").asInt()).isEqualTo(1);
        assertThat(objectMapper.readTree(state).path("acknowledgedWarningTime").isTextual()).isTrue();
        assertThat(codec.decodeAcknowledgedWarningTime(state)).isEqualTo(Long.MAX_VALUE);
        assertThat(codec.decodeAcknowledgedWarningTime(codec.encodePolicyState(null))).isNull();
        assertThat(codec.decodeAcknowledgedWarningTime("{}")).isNull();
    }

    @Test
    @DisplayName("策略状态 CAS 更新保留同版本未知字段")
    void policyStateUpdatePreservesUnknownFields() throws Exception {
        String current = """
                {"schema":"pixiv.schedule.credential-policy-state","version":1,"future":"kept"}
                """;

        String updated = codec.withAcknowledgedWarningTime(current, 55L);

        assertThat(codec.decodeAcknowledgedWarningTime(updated)).isEqualTo(55L);
        assertThat(objectMapper.readTree(updated).path("future").asText()).isEqualTo("kept");
    }
}
