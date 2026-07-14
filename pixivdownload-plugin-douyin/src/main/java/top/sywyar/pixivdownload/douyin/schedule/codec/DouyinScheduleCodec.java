package top.sywyar.pixivdownload.douyin.schedule.codec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceTypes;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledCredentialText;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDraft;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRelation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 抖音计划任务定义、检查点、作品引用与来源关系的唯一持久化编解码器。 */
public final class DouyinScheduleCodec {

    public static final String DEFINITION_SCHEMA = "douyin.schedule.definition";
    public static final int DEFINITION_VERSION = 1;
    public static final String CHECKPOINT_SCHEMA = "douyin.schedule.discovery-frontier";
    public static final int CHECKPOINT_VERSION = 1;
    public static final String WORK_SCHEMA = "douyin.schedule.work-reference";
    public static final int WORK_VERSION = 1;
    public static final String RELATION_SCHEMA = "douyin.schedule.source-relation";
    public static final int RELATION_VERSION = 1;
    public static final String WORK_TYPE = "douyin";

    public static final int DEFAULT_FETCH_LIMIT = 100;
    public static final int MAX_FETCH_LIMIT = 5_000;
    /**
     * Full SHA-256 identities retained by a checkpoint. Five thousand encoded hashes stay below
     * {@link ScheduledCheckpoint#MAX_PAYLOAD_BYTES}, including the JSON envelope and resume state.
     */
    public static final int MAX_FRONTIER_IDENTITIES = 5_000;

    private static final Set<String> SOURCE_TYPES = Set.of(
            DouyinSourceTypes.USER,
            DouyinSourceTypes.SEARCH,
            DouyinSourceTypes.COLLECTION,
            DouyinSourceTypes.MUSIC,
            DouyinSourceTypes.ACCOUNT_OWN_WORKS,
            DouyinSourceTypes.ACCOUNT_LIKED_WORKS,
            DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS,
            DouyinSourceTypes.ACCOUNT_FAVORITE_COLLECTION);
    private static final Set<String> ROOT_FIELDS = Set.of("source", "fetchLimit");
    private static final Set<String> CHECKPOINT_FIELDS = Set.of(
            "frontier", "resumeAfter", "recovery");
    private static final Set<String> WORK_FIELDS = Set.of("workId");
    private static final Set<String> RELATION_FIELDS = Set.of("sourceTitle", "sourceOrder");
    private static final Pattern HASH = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final ObjectMapper objectMapper;

    public DouyinScheduleCodec(ObjectMapper objectMapper) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public ScheduledTaskDefinition prepare(ScheduledTaskDraft draft)
            throws ScheduledExecutionException {
        if (draft == null
                || !SOURCE_TYPES.contains(draft.sourceType())
                || !DEFINITION_SCHEMA.equals(draft.definitionSchema())
                || draft.definitionVersion() != DEFINITION_VERSION) {
            throw invalidDefinition("douyin.schedule.definition-envelope-invalid");
        }
        Definition definition = decodeDefinition(draft.toDefinition(), draft.sourceType());
        return new ScheduledTaskDefinition(
                draft.taskId(), draft.sourceType(), DEFINITION_SCHEMA, DEFINITION_VERSION,
                encodeDefinition(definition), draft.presentation());
    }

    public Definition decodeDefinition(ScheduledTaskDefinition task, String expectedSourceType)
            throws ScheduledExecutionException {
        if (task == null
                || expectedSourceType == null
                || !expectedSourceType.equals(task.sourceType())
                || !SOURCE_TYPES.contains(expectedSourceType)
                || !DEFINITION_SCHEMA.equals(task.definitionSchema())
                || task.definitionVersion() != DEFINITION_VERSION) {
            throw invalidDefinition("douyin.schedule.definition-envelope-invalid");
        }
        JsonNode root = parseObject(
                task.definitionJson(), ScheduledFailure.Category.INVALID_DEFINITION,
                "douyin.schedule.definition-json-invalid");
        requireExactFields(root, ROOT_FIELDS, true, ScheduledFailure.Category.INVALID_DEFINITION,
                "douyin.schedule.definition-fields-invalid");
        JsonNode source = root.get("source");
        if (source == null || !source.isObject()) {
            throw invalidDefinition("douyin.schedule.definition-source-invalid");
        }

        String sourceId;
        Set<String> expectedSourceFields;
        int maximumLength = 256;
        switch (expectedSourceType) {
            case DouyinSourceTypes.USER -> {
                expectedSourceFields = Set.of("userId");
                sourceId = requiredText(source.get("userId"), maximumLength,
                        "douyin.schedule.definition-user-invalid");
                requireStableId(sourceId, "douyin.schedule.definition-user-invalid");
            }
            case DouyinSourceTypes.SEARCH -> {
                expectedSourceFields = Set.of("keyword");
                sourceId = requiredText(source.get("keyword"), 200,
                        "douyin.schedule.definition-keyword-invalid");
            }
            case DouyinSourceTypes.COLLECTION,
                    DouyinSourceTypes.ACCOUNT_FAVORITE_COLLECTION -> {
                expectedSourceFields = Set.of("collectionId");
                sourceId = requiredText(source.get("collectionId"), maximumLength,
                        "douyin.schedule.definition-collection-invalid");
                requireStableId(sourceId, "douyin.schedule.definition-collection-invalid");
            }
            case DouyinSourceTypes.MUSIC -> {
                expectedSourceFields = Set.of("musicId");
                sourceId = requiredText(source.get("musicId"), maximumLength,
                        "douyin.schedule.definition-music-invalid");
                requireStableId(sourceId, "douyin.schedule.definition-music-invalid");
            }
            case DouyinSourceTypes.ACCOUNT_OWN_WORKS,
                    DouyinSourceTypes.ACCOUNT_LIKED_WORKS,
                    DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS -> {
                expectedSourceFields = Set.of();
                sourceId = null;
            }
            default -> throw invalidDefinition("douyin.schedule.definition-source-type-invalid");
        }
        requireExactFields(source, expectedSourceFields,
                ScheduledFailure.Category.INVALID_DEFINITION,
                "douyin.schedule.definition-source-fields-invalid");
        int fetchLimit = parseFetchLimit(root.get("fetchLimit"));
        return new Definition(expectedSourceType, sourceId, fetchLimit);
    }

    public ScheduledWork createWork(
            String workId,
            String title,
            String author,
            ScheduledWorkRelation relation) throws ScheduledExecutionException {
        String normalizedId = normalizedWorkId(workId);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("workId", normalizedId);
        ScheduledWorkPresentation presentation = new ScheduledWorkPresentation(
                bounded(title, 4_096), bounded(author, 1_024), null, Map.of());
        return new ScheduledWork(
                new ScheduledWorkKey(WORK_TYPE, normalizedId),
                WORK_SCHEMA, WORK_VERSION, write(payload), presentation,
                relation == null ? List.of() : List.of(relation));
    }

    public String decodeWorkId(ScheduledWork work) throws ScheduledExecutionException {
        if (work == null
                || work.key() == null
                || !WORK_TYPE.equals(work.key().workType())
                || !WORK_SCHEMA.equals(work.payloadSchema())
                || work.payloadVersion() != WORK_VERSION) {
            throw payloadUnsupported("douyin.schedule.work-envelope-invalid");
        }
        JsonNode root = parseObject(work.payloadJson(), ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                "douyin.schedule.work-json-invalid");
        requireExactFields(root, WORK_FIELDS, ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                "douyin.schedule.work-fields-invalid");
        String workId = optionalText(
                root.get("workId"), 256,
                ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                "douyin.schedule.work-id-invalid");
        if (workId == null || !isOpaqueId(workId) || !workId.equals(work.key().id())) {
            throw payloadUnsupported("douyin.schedule.work-id-invalid");
        }
        return workId;
    }

    public ScheduledWorkRelation createRelation(
            String sourceType,
            String relationId,
            String sourceTitle,
            Integer sourceOrder) throws ScheduledExecutionException {
        if (!SOURCE_TYPES.contains(sourceType)) {
            throw invalidDefinition("douyin.schedule.relation-source-type-invalid");
        }
        String normalizedRelationId = requiredText(
                relationId, 512, "douyin.schedule.relation-source-id-invalid");
        ObjectNode payload = objectMapper.createObjectNode();
        String normalizedTitle = bounded(sourceTitle, 500);
        if (normalizedTitle != null) {
            payload.put("sourceTitle", normalizedTitle);
        }
        if (sourceOrder != null && sourceOrder >= 0) {
            payload.put("sourceOrder", sourceOrder);
        }
        return new ScheduledWorkRelation(
                sourceType, normalizedRelationId, RELATION_SCHEMA, RELATION_VERSION, write(payload));
    }

    public RelationData decodeRelation(ScheduledWorkRelation relation)
            throws ScheduledExecutionException {
        if (relation == null
                || !SOURCE_TYPES.contains(relation.relationType())
                || !RELATION_SCHEMA.equals(relation.payloadSchema())
                || relation.payloadVersion() != RELATION_VERSION) {
            throw payloadUnsupported("douyin.schedule.relation-envelope-invalid");
        }
        JsonNode root = parseObject(relation.payloadJson(), ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                "douyin.schedule.relation-json-invalid");
        requireExactFields(root, RELATION_FIELDS, true,
                ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                "douyin.schedule.relation-fields-invalid");
        String title = optionalText(
                root.get("sourceTitle"), 500,
                ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                "douyin.schedule.relation-title-invalid");
        Integer order = optionalNonNegativeInteger(root.get("sourceOrder"),
                "douyin.schedule.relation-order-invalid");
        String sourceId = requiredText(
                relation.relationId(), 512,
                ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                "douyin.schedule.relation-source-id-invalid");
        return new RelationData(
                relation.relationType(), sourceId, title, order);
    }

    public CheckpointState decodeCheckpoint(ScheduledCheckpoint checkpoint)
            throws ScheduledExecutionException {
        if (checkpoint == null) {
            return CheckpointState.empty();
        }
        if (!CHECKPOINT_SCHEMA.equals(checkpoint.schema())
                || checkpoint.version() != CHECKPOINT_VERSION) {
            throw invalidDefinition("douyin.schedule.checkpoint-envelope-invalid");
        }
        JsonNode root = parseObject(checkpoint.payloadJson(), ScheduledFailure.Category.INVALID_DEFINITION,
                "douyin.schedule.checkpoint-json-invalid");
        requireExactFields(root, CHECKPOINT_FIELDS, true,
                ScheduledFailure.Category.INVALID_DEFINITION,
                "douyin.schedule.checkpoint-fields-invalid");
        JsonNode rawFrontier = root.get("frontier");
        if (rawFrontier == null || !rawFrontier.isArray()
                || rawFrontier.size() > MAX_FRONTIER_IDENTITIES) {
            throw invalidDefinition("douyin.schedule.checkpoint-frontier-invalid");
        }
        LinkedHashSet<String> frontier = new LinkedHashSet<>();
        for (JsonNode item : rawFrontier) {
            if (!item.isTextual() || !HASH.matcher(item.textValue()).matches()
                    || !frontier.add(item.textValue())) {
                throw invalidDefinition("douyin.schedule.checkpoint-frontier-invalid");
            }
        }
        String resumeAfter = optionalText(root.get("resumeAfter"), 64,
                "douyin.schedule.checkpoint-resume-invalid");
        if (resumeAfter != null && !HASH.matcher(resumeAfter).matches()) {
            throw invalidDefinition("douyin.schedule.checkpoint-resume-invalid");
        }
        JsonNode rawRecovery = root.get("recovery");
        if (rawRecovery != null && !rawRecovery.isBoolean()) {
            throw invalidDefinition("douyin.schedule.checkpoint-recovery-invalid");
        }
        boolean recovery = rawRecovery != null && rawRecovery.booleanValue();
        return new CheckpointState(List.copyOf(frontier), resumeAfter, recovery);
    }

    public ScheduledCheckpoint encodeCheckpoint(CheckpointState state) {
        CheckpointState normalized = state == null ? CheckpointState.empty() : state;
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode frontier = root.putArray("frontier");
        normalized.frontier().stream().limit(MAX_FRONTIER_IDENTITIES).forEach(frontier::add);
        if (normalized.resumeAfter() != null) {
            root.put("resumeAfter", normalized.resumeAfter());
        }
        if (normalized.recovery()) {
            root.put("recovery", true);
        }
        return new ScheduledCheckpoint(CHECKPOINT_SCHEMA, CHECKPOINT_VERSION, write(root));
    }

    public String identityHash(String workId) throws ScheduledExecutionException {
        String normalized = normalizedWorkId(workId);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static boolean isSupportedSourceType(String sourceType) {
        return SOURCE_TYPES.contains(sourceType);
    }

    private String encodeDefinition(Definition definition) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode source = root.putObject("source");
        switch (definition.sourceType()) {
            case DouyinSourceTypes.USER -> source.put("userId", definition.sourceId());
            case DouyinSourceTypes.SEARCH -> source.put("keyword", definition.sourceId());
            case DouyinSourceTypes.COLLECTION,
                    DouyinSourceTypes.ACCOUNT_FAVORITE_COLLECTION ->
                    source.put("collectionId", definition.sourceId());
            case DouyinSourceTypes.MUSIC -> source.put("musicId", definition.sourceId());
            default -> {
                // 账号作品来源只由已探活凭证确定账号，不持久化账号或凭证材料。
            }
        }
        root.put("fetchLimit", definition.fetchLimit());
        return write(root);
    }

    private JsonNode parseObject(
            String json,
            ScheduledFailure.Category category,
            String code) throws ScheduledExecutionException {
        try (JsonParser parser = objectMapper.createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = objectMapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw new ScheduledExecutionException(category, code);
            }
            return root;
        } catch (JsonProcessingException ignored) {
            throw new ScheduledExecutionException(category, code);
        } catch (IOException ignored) {
            throw new ScheduledExecutionException(category, code);
        }
    }

    private static void requireExactFields(
            JsonNode object,
            Set<String> allowed,
            ScheduledFailure.Category category,
            String code) throws ScheduledExecutionException {
        requireExactFields(object, allowed, false, category, code);
    }

    private static void requireExactFields(
            JsonNode object,
            Set<String> allowed,
            boolean optional,
            ScheduledFailure.Category category,
            String code) throws ScheduledExecutionException {
        Set<String> actual = new HashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!allowed.containsAll(actual) || !optional && !actual.equals(allowed)) {
            throw new ScheduledExecutionException(category, code);
        }
    }

    private static int parseFetchLimit(JsonNode value) throws ScheduledExecutionException {
        if (value == null || value.isNull()) {
            return DEFAULT_FETCH_LIMIT;
        }
        Integer parsed = null;
        if (value.isIntegralNumber() && value.canConvertToInt()) {
            parsed = value.intValue();
        } else if (value.isTextual() && value.textValue().trim().matches("[0-9]+")) {
            try {
                parsed = Integer.valueOf(value.textValue().trim());
            } catch (NumberFormatException ignored) {
                // 统一落入下面的稳定定义错误。
            }
        }
        if (parsed == null || parsed < 0 || parsed > MAX_FETCH_LIMIT) {
            throw invalidDefinition("douyin.schedule.definition-fetch-limit-invalid");
        }
        return parsed;
    }

    private static String normalizedWorkId(String value) throws ScheduledExecutionException {
        String normalized = value == null ? "" : value.trim();
        if (!isOpaqueId(normalized)) {
            throw payloadUnsupported("douyin.schedule.work-id-invalid");
        }
        return normalized;
    }

    private static void requireStableId(String value, String code)
            throws ScheduledExecutionException {
        if (!isOpaqueId(value)) {
            throw invalidDefinition(code);
        }
    }

    private static boolean isOpaqueId(String value) {
        if (value == null || value.isBlank() || value.length() > 256
                || value.contains("://") || value.indexOf('\0') >= 0
                || ScheduledCredentialText.containsCredentialMaterial(value)) {
            return false;
        }
        return value.codePoints().noneMatch(Character::isISOControl);
    }

    private static String requiredText(String value, int maxLength, String code)
            throws ScheduledExecutionException {
        return requiredText(
                value, maxLength, ScheduledFailure.Category.INVALID_DEFINITION, code);
    }

    private static String requiredText(
            String value,
            int maxLength,
            ScheduledFailure.Category category,
            String code) throws ScheduledExecutionException {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty() || normalized.length() > maxLength
                || normalized.indexOf('\0') >= 0
                || ScheduledCredentialText.containsCredentialMaterial(normalized)
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new ScheduledExecutionException(category, code);
        }
        return normalized;
    }

    private static String requiredText(JsonNode value, int maxLength, String code)
            throws ScheduledExecutionException {
        String normalized = optionalText(value, maxLength, code);
        if (normalized == null) {
            throw invalidDefinition(code);
        }
        return normalized;
    }

    private static String optionalText(JsonNode value, int maxLength, String code)
            throws ScheduledExecutionException {
        return optionalText(value, maxLength, ScheduledFailure.Category.INVALID_DEFINITION, code);
    }

    private static String optionalText(
            JsonNode value,
            int maxLength,
            ScheduledFailure.Category category,
            String code) throws ScheduledExecutionException {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new ScheduledExecutionException(category, code);
        }
        String normalized = value.textValue().trim();
        if (normalized.isEmpty() || normalized.length() > maxLength
                || normalized.indexOf('\0') >= 0
                || ScheduledCredentialText.containsCredentialMaterial(normalized)
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new ScheduledExecutionException(category, code);
        }
        return normalized;
    }

    private static Integer optionalNonNegativeInteger(JsonNode value, String code)
            throws ScheduledExecutionException {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw payloadUnsupported(code);
        }
        return value.intValue();
    }

    private String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("failed to encode Douyin schedule payload", impossible);
        }
    }

    private static String bounded(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replace("\0", "");
        if (ScheduledCredentialText.containsCredentialMaterial(normalized)) {
            return null;
        }
        return normalized.length() <= maximumLength
                ? normalized : normalized.substring(0, maximumLength);
    }

    private static ScheduledExecutionException invalidDefinition(String code) {
        return new ScheduledExecutionException(ScheduledFailure.Category.INVALID_DEFINITION, code);
    }

    private static ScheduledExecutionException payloadUnsupported(String code) {
        return new ScheduledExecutionException(ScheduledFailure.Category.PAYLOAD_UNSUPPORTED, code);
    }

    public record Definition(String sourceType, String sourceId, int fetchLimit) {
    }

    public record CheckpointState(List<String> frontier, String resumeAfter, boolean recovery) {

        public CheckpointState(List<String> frontier, String resumeAfter) {
            this(frontier, resumeAfter, false);
        }

        public CheckpointState {
            frontier = frontier == null ? List.of() : List.copyOf(frontier);
            if (frontier.size() > MAX_FRONTIER_IDENTITIES) {
                frontier = List.copyOf(frontier.subList(0, MAX_FRONTIER_IDENTITIES));
            }
            resumeAfter = resumeAfter == null || resumeAfter.isBlank()
                    ? null : resumeAfter.trim();
        }

        public static CheckpointState empty() {
            return new CheckpointState(List.of(), null, false);
        }
    }

    public record RelationData(
            String sourceType,
            String sourceId,
            String sourceTitle,
            Integer sourceOrder) {
    }
}
