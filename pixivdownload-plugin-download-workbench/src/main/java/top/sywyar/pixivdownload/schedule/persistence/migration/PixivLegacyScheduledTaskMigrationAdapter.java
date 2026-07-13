package top.sywyar.pixivdownload.schedule.persistence.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationAdapter;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationResult;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskSnapshot;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 把七类旧 Pixiv 计划任务无损转换为插件中性的定义、检查点、凭证计划和 pending 信封。 */
@PluginManagedBean
public final class PixivLegacyScheduledTaskMigrationAdapter implements LegacyScheduledTaskMigrationAdapter {

    public static final String REJECTION_AMBIGUOUS_WORK_TYPE = "AMBIGUOUS_LEGACY_WORK_TYPE";
    public static final String REJECTION_INVALID_DATA = "INVALID_LEGACY_PIXIV_DATA";
    public static final String LEGACY_PENDING_REASON = "LEGACY_PENDING";
    public static final String SUSPEND_CODE_OVERUSE = "PIXIV_OVERUSE";
    public static final String CREDENTIAL_REFERENCE_PREFIX = "scheduled-task:";
    public static final String CREDENTIAL_REFERENCE_SUFFIX = ":credential";

    private final ObjectMapper objectMapper;
    private final PixivSchedulePersistenceCodec codec;

    public PixivLegacyScheduledTaskMigrationAdapter(ObjectMapper objectMapper,
                                                    PixivSchedulePersistenceCodec codec) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public LegacyScheduledTaskMigrationResult migrate(LegacyScheduledTaskSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        String canonicalSourceType = PixivSchedulePersistenceCodec.legacySourceAliases().get(snapshot.sourceType());
        if (canonicalSourceType == null) {
            return rejected(REJECTION_INVALID_DATA, snapshot, "unsupported-source-type");
        }
        if ("COLLECTION".equals(snapshot.sourceType()) && !snapshot.pending().isEmpty()) {
            return rejected(REJECTION_AMBIGUOUS_WORK_TYPE, snapshot, "collection-pending-work-type-unknown");
        }

        try {
            ScheduledTaskDefinition definition = codec.createDefinition(
                    snapshot.id(), snapshot.name(), canonicalSourceType, snapshot.definitionJson());
            ScheduledCheckpoint checkpoint = snapshot.watermarkId() == null
                    ? null : codec.encodeCheckpoint(snapshot.watermarkId());
            LegacyScheduledTaskMigrationResult.Credential credential = credential(snapshot);
            List<LegacyScheduledTaskMigrationResult.PendingWork> pending = migratePending(snapshot);
            String canonicalSuspendCode = "OVERUSE_PAUSED".equals(snapshot.lastStatus())
                    ? SUSPEND_CODE_OVERUSE : null;
            return new LegacyScheduledTaskMigrationResult.Migrated(
                    definition, checkpoint, credential, pending, canonicalSuspendCode);
        } catch (IllegalArgumentException e) {
            return rejected(REJECTION_INVALID_DATA, snapshot, "invalid-legacy-value");
        }
    }

    private LegacyScheduledTaskMigrationResult.Credential credential(LegacyScheduledTaskSnapshot snapshot) {
        boolean hasAccountKey = snapshot.accountId() != null && !snapshot.accountId().isBlank();
        if (!snapshot.hasLegacySecret() && !hasAccountKey && snapshot.acknowledgedWarningTime() == null) {
            return null;
        }
        return new LegacyScheduledTaskMigrationResult.Credential(
                DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID,
                snapshot.accountId(),
                snapshot.hasLegacySecret()
                        ? CREDENTIAL_REFERENCE_PREFIX + snapshot.id() + CREDENTIAL_REFERENCE_SUFFIX
                        : null,
                codec.encodePolicyState(snapshot.acknowledgedWarningTime()),
                snapshot.createdTime());
    }

    private List<LegacyScheduledTaskMigrationResult.PendingWork> migratePending(
            LegacyScheduledTaskSnapshot snapshot) {
        if (snapshot.pending().isEmpty()) {
            return List.of();
        }
        String workType = legacyWorkType(snapshot.sourceType(), snapshot.definitionJson());
        List<LegacyScheduledTaskMigrationResult.PendingWork> migrated = new ArrayList<>(snapshot.pending().size());
        for (LegacyScheduledTaskSnapshot.PendingRow row : snapshot.pending()) {
            migrated.add(new LegacyScheduledTaskMigrationResult.PendingWork(
                    row.workId(),
                    codec.createWorkEnvelope(workType, row.workId()),
                    LEGACY_PENDING_REASON,
                    pendingReasonJson(row.reason()),
                    row.attempts(),
                    row.firstSeenTime(),
                    row.lastAttemptTime()));
        }
        return List.copyOf(migrated);
    }

    private String legacyWorkType(String sourceType, String definitionJson) {
        if ("USER_REQUEST".equals(sourceType) || "FOLLOW_LATEST".equals(sourceType)) {
            return PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST;
        }
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            String kind = root.path("kind").asText(PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST)
                    .trim().toLowerCase(Locale.ROOT);
            return PixivSchedulePersistenceCodec.WORK_TYPE_NOVEL.equals(kind)
                    ? PixivSchedulePersistenceCodec.WORK_TYPE_NOVEL
                    : PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("legacy definition JSON is invalid", e);
        }
    }

    private String pendingReasonJson(String reason) {
        ObjectNode detail = objectMapper.createObjectNode();
        if (reason != null && !reason.isBlank()) {
            detail.put("legacyReason", reason);
        }
        return write(detail);
    }

    private LegacyScheduledTaskMigrationResult.Rejected rejected(
            String code, LegacyScheduledTaskSnapshot snapshot, String reason) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("taskId", Long.toString(snapshot.id()));
        detail.put("legacySourceType", snapshot.sourceType());
        detail.put("reasonCode", reason);
        detail.put("legacyDataPreserved", true);
        detail.put("pendingCount", snapshot.pending().size());
        ArrayNode pending = detail.putArray("pending");
        for (LegacyScheduledTaskSnapshot.PendingRow row : snapshot.pending()) {
            ObjectNode item = pending.addObject();
            item.put("workId", row.workId());
            item.put("attempts", row.attempts());
            if (row.firstSeenTime() != null) {
                item.put("firstSeenTime", Long.toString(row.firstSeenTime()));
            }
            if (row.lastAttemptTime() != null) {
                item.put("lastAttemptTime", Long.toString(row.lastAttemptTime()));
            }
        }
        String json = write(detail);
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > LegacyScheduledTaskMigrationResult.MAX_SAFE_DETAIL_BYTES) {
            ObjectNode compact = objectMapper.createObjectNode();
            compact.put("taskId", Long.toString(snapshot.id()));
            compact.put("legacySourceType", snapshot.sourceType());
            compact.put("reasonCode", reason);
            compact.put("legacyDataPreserved", true);
            compact.put("pendingCount", snapshot.pending().size());
            json = write(compact);
        }
        return new LegacyScheduledTaskMigrationResult.Rejected(code, json);
    }

    private String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to encode Pixiv migration detail", e);
        }
    }

    /** 向核心 registrar 提供同一份权威 alias 快照，不重复维护映射。 */
    public static Map<String, String> legacySourceAliases() {
        return PixivSchedulePersistenceCodec.legacySourceAliases();
    }
}
