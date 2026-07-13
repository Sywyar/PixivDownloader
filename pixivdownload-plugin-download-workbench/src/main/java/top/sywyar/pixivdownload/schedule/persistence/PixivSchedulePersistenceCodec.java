package top.sywyar.pixivdownload.schedule.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRelation;
import top.sywyar.pixivdownload.schedule.security.ScheduleCredentialRedactor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Pixiv 计划任务持久化载荷的唯一编解码入口。所有数字身份在不透明 JSON 中按字符串保存，避免
 * JavaScript 数字精度或 SQLite affinity 改写作品身份。
 */
@PluginManagedBean
public final class PixivSchedulePersistenceCodec {

    public static final String DEFINITION_SCHEMA = "pixiv.schedule.definition";
    public static final int DEFINITION_VERSION = 1;
    public static final String CHECKPOINT_SCHEMA = "pixiv.schedule.watermark";
    public static final int CHECKPOINT_VERSION = 1;
    public static final String WORK_PAYLOAD_SCHEMA = "pixiv.schedule.work-reference";
    public static final int WORK_PAYLOAD_VERSION = 1;
    public static final String CREDENTIAL_POLICY_STATE_SCHEMA = "pixiv.schedule.credential-policy-state";
    public static final int CREDENTIAL_POLICY_STATE_VERSION = 1;
    public static final String CREDENTIAL_POLICY_ID = "pixiv-cookie";
    public static final String WORK_TYPE_ILLUST = "illust";
    public static final String WORK_TYPE_NOVEL = "novel";

    private static final Map<String, String> LEGACY_SOURCE_ALIASES = Map.ofEntries(
            Map.entry("USER_NEW", "user-new"),
            Map.entry("USER_REQUEST", "user-request"),
            Map.entry("SEARCH", "search"),
            Map.entry("SERIES", "series"),
            Map.entry("MY_BOOKMARKS", "my-bookmarks"),
            Map.entry("FOLLOW_LATEST", "follow-latest"),
            Map.entry("COLLECTION", "collection"));

    private final ObjectMapper objectMapper;

    public PixivSchedulePersistenceCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** 旧枚举值到当前 canonical source type 的完整、不可变映射。 */
    public static Map<String, String> legacySourceAliases() {
        return LEGACY_SOURCE_ALIASES;
    }

    /** 为新建、编辑或旧数据迁移生成同一份正式定义信封。 */
    public ScheduledTaskDefinition createDefinition(long taskId,
                                                    String taskName,
                                                    String canonicalSourceType,
                                                    String definitionJson) {
        JsonNode root = parseObject(definitionJson, "definition JSON");
        rejectCredentialMaterial(root, "schedule definition");
        rejectCredentialText(taskName, "schedule task name");
        Map<String, String> attributes = presentationAttributes(root);
        return new ScheduledTaskDefinition(
                taskId,
                requireText(canonicalSourceType, "canonical source type"),
                DEFINITION_SCHEMA,
                DEFINITION_VERSION,
                definitionJson,
                new ScheduledTaskPresentation(taskName, null, attributes));
    }

    /** 把旧 long 水位线编码为插件拥有的版本化 checkpoint；数字值始终以 JSON 字符串保存。 */
    public ScheduledCheckpoint encodeCheckpoint(long watermarkId) {
        if (watermarkId < 0) {
            throw new IllegalArgumentException("watermark id must not be negative");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("watermarkId", Long.toString(watermarkId));
        return new ScheduledCheckpoint(CHECKPOINT_SCHEMA, CHECKPOINT_VERSION, write(payload));
    }

    /** 严格回读当前 Pixiv checkpoint，拒绝数值 JSON、错误 schema/version 与越界 long。 */
    public long decodeCheckpoint(ScheduledCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (!CHECKPOINT_SCHEMA.equals(checkpoint.schema()) || checkpoint.version() != CHECKPOINT_VERSION) {
            throw new IllegalArgumentException("unsupported Pixiv checkpoint schema or version");
        }
        JsonNode root = parseObject(checkpoint.payloadJson(), "checkpoint payload");
        return parseLongText(root.get("watermarkId"), "watermarkId", true);
    }

    /** 创建可跨 pending、重启和 reload 的 Pixiv 作品引用信封。 */
    public ScheduledWork createWorkEnvelope(String workType, String workId) {
        String canonicalWorkType = requireWorkType(workType);
        String opaqueId = requireText(workId, "work id");
        parseLongText(objectMapper.getNodeFactory().textNode(opaqueId), "workId", false);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("workId", opaqueId);
        return new ScheduledWork(
                new ScheduledWorkKey(canonicalWorkType, opaqueId),
                WORK_PAYLOAD_SCHEMA,
                WORK_PAYLOAD_VERSION,
                write(payload),
                ScheduledWorkPresentation.empty(),
                List.of());
    }

    /** 严格回读 Pixiv 作品引用并校验 key 与 payload 身份一致。 */
    public String decodeWorkId(ScheduledWork work) {
        Objects.requireNonNull(work, "work");
        requireWorkType(work.key().workType());
        if (!WORK_PAYLOAD_SCHEMA.equals(work.payloadSchema()) || work.payloadVersion() != WORK_PAYLOAD_VERSION) {
            throw new IllegalArgumentException("unsupported Pixiv work payload schema or version");
        }
        JsonNode root = parseObject(work.payloadJson(), "work payload");
        rejectCredentialMaterial(root, "schedule work payload");
        if (root.size() != 1 || !root.has("workId")) {
            throw new IllegalArgumentException("Pixiv work payload contains unsupported fields");
        }
        JsonNode value = root.get("workId");
        parseLongText(value, "workId", false);
        String decoded = value.textValue();
        if (!work.key().id().equals(decoded)) {
            throw new IllegalArgumentException("Pixiv work key and payload id differ");
        }
        return decoded;
    }

    /** 把 API work envelope 完整投影为核心 pending 行，保留 presentation 与 relations。 */
    public ScheduledPendingWork toPendingWork(long taskId,
                                              ScheduledWork work,
                                              String reasonCode,
                                              String reasonDetailJson,
                                              int attempts,
                                              Long firstSeenTime,
                                              Long lastAttemptTime) {
        if (taskId <= 0) {
            throw new IllegalArgumentException("task id must be positive");
        }
        Objects.requireNonNull(work, "work");
        decodeWorkId(work);
        rejectCredentialText(reasonCode, "pending reason code");
        String relationsJson = writeSafeValue(work.relations(), "schedule work relations");
        String presentationJson = writeSafeValue(work.presentation(), "schedule work presentation");
        if (reasonDetailJson != null && !reasonDetailJson.isBlank()) {
            rejectCredentialMaterial(parseJson(reasonDetailJson, "pending reason detail"),
                    "pending reason detail");
        }
        return new ScheduledPendingWork(
                taskId,
                work.key().workType(),
                work.key().id(),
                work.payloadSchema(),
                work.payloadVersion(),
                work.payloadJson(),
                relationsJson,
                presentationJson,
                reasonCode,
                reasonDetailJson,
                attempts,
                firstSeenTime,
                lastAttemptTime);
    }

    /** 从核心 pending 行恢复完整 API work envelope，并重新执行 Pixiv schema 与身份一致性校验。 */
    public ScheduledWork fromPendingWork(ScheduledPendingWork pending) {
        Objects.requireNonNull(pending, "pending");
        if (pending.taskId() <= 0) {
            throw new IllegalArgumentException("pending task id must be positive");
        }
        if (pending.reasonDetailJson() != null && !pending.reasonDetailJson().isBlank()) {
            rejectCredentialMaterial(parseJson(pending.reasonDetailJson(), "pending reason detail"),
                    "pending reason detail");
        }
        rejectCredentialText(pending.reasonCode(), "pending reason code");
        String pendingPresentation = pending.presentationJson();
        if (pendingPresentation != null && !pendingPresentation.isBlank()) {
            rejectCredentialMaterial(parseJson(pendingPresentation, "pending presentation"),
                    "pending presentation");
        }
        ScheduledWorkPresentation presentation = pendingPresentation == null
                || pendingPresentation.isBlank()
                ? ScheduledWorkPresentation.empty()
                : readValue(pendingPresentation, ScheduledWorkPresentation.class, "pending presentation");
        List<ScheduledWorkRelation> relations = readRelations(pending.relationsJson());
        ScheduledWork work = new ScheduledWork(
                new ScheduledWorkKey(pending.workType(), pending.workId()),
                pending.payloadSchema(),
                pending.payloadVersion(),
                pending.payloadJson(),
                presentation,
                relations);
        decodeWorkId(work);
        return work;
    }

    /** 编码 Pixiv credential policy 的耐久状态；警告时间同样按字符串保存。 */
    public String encodePolicyState(Long acknowledgedWarningTime) {
        ObjectNode state = objectMapper.createObjectNode();
        state.put("schema", CREDENTIAL_POLICY_STATE_SCHEMA);
        state.put("version", CREDENTIAL_POLICY_STATE_VERSION);
        if (acknowledgedWarningTime != null) {
            if (acknowledgedWarningTime < 0) {
                throw new IllegalArgumentException("acknowledged warning time must not be negative");
            }
            state.put("acknowledgedWarningTime", Long.toString(acknowledgedWarningTime));
        }
        return write(state);
    }

    /** 严格回读 Pixiv credential policy 状态；未记录警告时间时返回 {@code null}。 */
    public Long decodeAcknowledgedWarningTime(String policyStateJson) {
        JsonNode root = parseObject(policyStateJson, "credential policy state");
        if (root.isEmpty()) {
            return null;
        }
        if (!CREDENTIAL_POLICY_STATE_SCHEMA.equals(root.path("schema").asText())
                || !root.path("version").isIntegralNumber()
                || root.path("version").asInt() != CREDENTIAL_POLICY_STATE_VERSION) {
            throw new IllegalArgumentException("unsupported Pixiv credential policy state schema or version");
        }
        JsonNode value = root.get("acknowledgedWarningTime");
        return value == null || value.isNull() ? null : parseLongText(value, "acknowledgedWarningTime", true);
    }

    /** 在保留同 schema 未知字段的前提下更新警告时间，供凭证状态 CAS 写回。 */
    public String withAcknowledgedWarningTime(String currentPolicyStateJson, Long acknowledgedWarningTime) {
        JsonNode current = parseObject(currentPolicyStateJson, "credential policy state");
        if (!current.isEmpty()) {
            decodeAcknowledgedWarningTime(currentPolicyStateJson);
        }
        ObjectNode updated = current.deepCopy();
        updated.put("schema", CREDENTIAL_POLICY_STATE_SCHEMA);
        updated.put("version", CREDENTIAL_POLICY_STATE_VERSION);
        if (acknowledgedWarningTime == null) {
            updated.remove("acknowledgedWarningTime");
        } else {
            if (acknowledgedWarningTime < 0) {
                throw new IllegalArgumentException("acknowledged warning time must not be negative");
            }
            updated.put("acknowledgedWarningTime", Long.toString(acknowledgedWarningTime));
        }
        return write(updated);
    }

    private Map<String, String> presentationAttributes(JsonNode root) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        String kind = root.path("kind").asText(WORK_TYPE_ILLUST).trim().toLowerCase(Locale.ROOT);
        put(attributes, "kind", kind);
        JsonNode source = root.path("source");
        for (String field : List.of("userId", "word", "order", "mode", "sMode", "maxPages",
                "seriesId", "rest", "collectionId")) {
            JsonNode value = source.get(field);
            if (value != null && value.isValueNode() && !value.isNull()) {
                put(attributes, "source." + field, value.asText());
            }
        }
        JsonNode fetchLimit = root.get("fetchLimit");
        if (fetchLimit != null && fetchLimit.isValueNode() && !fetchLimit.isNull()) {
            put(attributes, "fetchLimit", fetchLimit.asText());
        }
        return Map.copyOf(attributes);
    }

    private static void put(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }

    private JsonNode parseObject(String json, String label) {
        JsonNode root = parseJson(json, label);
        if (!root.isObject()) {
            throw new IllegalArgumentException(label + " must be a JSON object");
        }
        return root;
    }

    private JsonNode parseJson(String json, String label) {
        try {
            JsonNode root = objectMapper.readTree(requireText(json, label));
            if (root == null) {
                throw new IllegalArgumentException(label + " must not be JSON null");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(label + " is invalid JSON", e);
        }
    }

    private String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to encode Pixiv schedule payload", e);
        }
    }

    private String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to encode Pixiv pending metadata", e);
        }
    }

    private String writeSafeValue(Object value, String label) {
        String json = writeValue(value);
        rejectCredentialMaterial(parseJson(json, label), label);
        return json;
    }

    private void rejectCredentialMaterial(JsonNode node, String label) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (ScheduleCredentialRedactor.isSensitiveFieldName(entry.getKey())) {
                    throw new IllegalArgumentException(label + " contains forbidden credential material");
                }
                rejectCredentialMaterial(entry.getValue(), label);
            });
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> rejectCredentialMaterial(item, label));
            return;
        }
        if (node.isTextual()
                && ScheduleCredentialRedactor.containsCredentialMaterial(node.textValue())) {
            throw new IllegalArgumentException(label + " contains forbidden credential material");
        }
        if (node.isTextual()) {
            String nestedJson = node.textValue().trim();
            if (nestedJson.startsWith("{") || nestedJson.startsWith("[")) {
                try {
                    JsonNode nested = objectMapper.readTree(nestedJson);
                    if (nested != null && (nested.isObject() || nested.isArray())) {
                        rejectCredentialMaterial(nested, label);
                    }
                } catch (JsonProcessingException ignored) {
                    // 普通展示文本可以以花括号开头；只有完整嵌套 JSON 才继续递归检查。
                }
            }
        }
    }

    private static void rejectCredentialText(String value, String label) {
        if (ScheduleCredentialRedactor.containsCredentialMaterial(value)) {
            throw new IllegalArgumentException(label + " contains forbidden credential material");
        }
    }

    private <T> T readValue(String json, Class<T> type, String label) {
        try {
            return objectMapper.readValue(requireText(json, label), type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(label + " is invalid JSON", e);
        }
    }

    private List<ScheduledWorkRelation> readRelations(String json) {
        String value = json == null || json.isBlank() ? "[]" : json;
        rejectCredentialMaterial(parseJson(value, "pending relations"), "pending relations");
        try {
            return objectMapper.readValue(value, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ScheduledWorkRelation.class));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("pending relations are invalid JSON", e);
        }
    }

    private static long parseLongText(JsonNode value, String field, boolean allowZero) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be a decimal string");
        }
        String text = value.textValue();
        try {
            long parsed = Long.parseLong(text);
            if (parsed < 0 || (!allowZero && parsed == 0) || !Long.toString(parsed).equals(text)) {
                throw new IllegalArgumentException(field + " must be a canonical non-negative long string");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " is outside signed long range", e);
        }
    }

    private static String requireWorkType(String workType) {
        String value = requireText(workType, "work type");
        if (!WORK_TYPE_ILLUST.equals(value) && !WORK_TYPE_NOVEL.equals(value)) {
            throw new IllegalArgumentException("unsupported Pixiv work type: " + value);
        }
        return value;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }
}
