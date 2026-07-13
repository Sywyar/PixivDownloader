package top.sywyar.pixivdownload.schedule.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRelation;
import top.sywyar.pixivdownload.schedule.security.ScheduleCredentialRedactor;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * 插件中性的计划作品持久化编解码器。宿主只投影稳定 API 信封，不解释作品类型、payload schema、
 * payload version 或插件拥有的 JSON 字段。
 *
 * <p>作品 key 与 payload JSON 原文直接写入 pending，确保不透明 ID、数字字符串格式和插件 JSON 表达
 * 不被重写。presentation 与 relations 是稳定 API 值对象，按 JSON 保存并在恢复时重新经过各自构造器校验。
 * 所有可持久化文本都执行凭证材料扫描，避免 Cookie、Authorization、token、签名或临时 URL 进入 pending。
 */
public final class ScheduleWorkPersistenceCodec {

    private final ObjectMapper objectMapper;

    public ScheduleWorkPersistenceCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** 校验插件拥有的不透明 checkpoint 仍是单一 JSON，且不含凭证材料。 */
    public void validateCheckpoint(ScheduledCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        rejectCredentialText(checkpoint.schema(), "schedule checkpoint schema");
        validateOpaqueJson(checkpoint.payloadJson(), "schedule checkpoint payload");
    }

    /** 校验来源插件刚提交的 work，确保进入队列前已满足 pending 的同一安全边界。 */
    public void validateWork(ScheduledWork work) {
        Objects.requireNonNull(work, "work");
        validateIdentity(work.key());
        rejectCredentialText(work.payloadSchema(), "schedule work payload schema");
        validateOpaqueJson(work.payloadJson(), "schedule work payload");
        writeSafeValue(work.presentation(), "schedule work presentation");
        writeSafeValue(work.relations(), "schedule work relations");
    }

    /** 把一件稳定 API 作品信封完整投影为核心 pending 行。 */
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
        validateWork(work);
        rejectCredentialText(reasonCode, "pending reason code");
        if (reasonDetailJson != null && !reasonDetailJson.isBlank()) {
            validateOpaqueJson(reasonDetailJson, "pending reason detail");
        }

        String presentationJson = writeSafeValue(
                work.presentation(), "schedule work presentation");
        String relationsJson = writeSafeValue(
                work.relations(), "schedule work relations");
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

    /**
     * 从核心 pending 行恢复完整稳定 API 作品信封。payload 只校验 JSON 与安全边界，不解释插件 schema。
     */
    public ScheduledWork fromPendingWork(ScheduledPendingWork pending) {
        Objects.requireNonNull(pending, "pending");
        if (pending.taskId() <= 0) {
            throw new IllegalArgumentException("pending task id must be positive");
        }
        rejectCredentialText(pending.reasonCode(), "pending reason code");
        if (pending.reasonDetailJson() != null && !pending.reasonDetailJson().isBlank()) {
            validateOpaqueJson(pending.reasonDetailJson(), "pending reason detail");
        }
        rejectCredentialText(pending.payloadSchema(), "schedule work payload schema");
        validateOpaqueJson(pending.payloadJson(), "schedule work payload");

        ScheduledWorkPresentation presentation = readPresentation(pending.presentationJson());
        List<ScheduledWorkRelation> relations = readRelations(pending.relationsJson());
        ScheduledWorkKey key = new ScheduledWorkKey(pending.workType(), pending.workId());
        validateIdentity(key);
        return new ScheduledWork(
                key,
                pending.payloadSchema(),
                pending.payloadVersion(),
                pending.payloadJson(),
                presentation,
                relations);
    }

    private ScheduledWorkPresentation readPresentation(String json) {
        if (json == null || json.isBlank()) {
            return ScheduledWorkPresentation.empty();
        }
        JsonNode root = parseSingleJson(json, "pending presentation");
        if (!root.isObject()) {
            throw new IllegalArgumentException("pending presentation must be a JSON object");
        }
        rejectCredentialMaterial(root, "pending presentation");
        return readValue(json, ScheduledWorkPresentation.class, "pending presentation");
    }

    private List<ScheduledWorkRelation> readRelations(String json) {
        String value = json == null || json.isBlank() ? "[]" : json;
        JsonNode root = parseSingleJson(value, "pending relations");
        if (!root.isArray()) {
            throw new IllegalArgumentException("pending relations must be a JSON array");
        }
        rejectCredentialMaterial(root, "pending relations");
        try {
            return objectMapper.readValue(value, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ScheduledWorkRelation.class));
        } catch (JsonProcessingException e) {
            rethrowApiValidation(e);
            throw new IllegalArgumentException("pending relations are invalid JSON", e);
        }
    }

    private void validateIdentity(ScheduledWorkKey key) {
        Objects.requireNonNull(key, "work key");
        rejectCredentialText(key.workType(), "schedule work type");
        rejectCredentialText(key.id(), "schedule work id");
    }

    private void validateOpaqueJson(String json, String label) {
        JsonNode root = parseSingleJson(json, label);
        if (root.isNull()) {
            throw new IllegalArgumentException(label + " must not be JSON null");
        }
        rejectCredentialMaterial(root, label);
    }

    private JsonNode parseSingleJson(String json, String label) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        try (JsonParser parser = objectMapper.createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = objectMapper.readTree(parser);
            if (root == null) {
                throw new IllegalArgumentException(label + " must not be empty");
            }
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException(label + " must contain exactly one JSON value");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(label + " is invalid JSON", e);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + label, e);
        }
    }

    private String writeSafeValue(Object value, String label) {
        String json;
        try {
            json = objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(label + " cannot be encoded as JSON", e);
        }
        JsonNode root = parseSingleJson(json, label);
        rejectCredentialMaterial(root, label);
        return json;
    }

    private <T> T readValue(String json, Class<T> type, String label) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            rethrowApiValidation(e);
            throw new IllegalArgumentException(label + " is invalid JSON", e);
        }
    }

    private static void rethrowApiValidation(JsonProcessingException exception) {
        Throwable cause = exception.getCause();
        while (cause != null) {
            if (cause instanceof IllegalArgumentException validationFailure) {
                throw validationFailure;
            }
            cause = cause.getCause();
        }
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
        if (!node.isTextual()) {
            return;
        }
        String text = node.textValue();
        rejectCredentialText(text, label);
        String nestedJson = text.trim();
        if (!nestedJson.startsWith("{") && !nestedJson.startsWith("[")) {
            return;
        }
        JsonNode nested = readEmbeddedJson(nestedJson, label);
        if (nested != null) {
            rejectCredentialMaterial(nested, label);
        }
    }

    private JsonNode readEmbeddedJson(String json, String label) {
        try {
            return parseSingleJson(json, label);
        } catch (IllegalArgumentException strictFailure) {
            try {
                JsonNode permissive = objectMapper.readTree(json);
                if (permissive != null && (permissive.isObject() || permissive.isArray())) {
                    throw strictFailure;
                }
            } catch (JsonProcessingException ignored) {
                // 以花括号开头的普通展示文本不是完整 JSON，不按嵌套载荷解释。
            }
            return null;
        }
    }

    private static void rejectCredentialText(String value, String label) {
        if (ScheduleCredentialRedactor.containsCredentialMaterial(value)) {
            throw new IllegalArgumentException(label + " contains forbidden credential material");
        }
    }
}
