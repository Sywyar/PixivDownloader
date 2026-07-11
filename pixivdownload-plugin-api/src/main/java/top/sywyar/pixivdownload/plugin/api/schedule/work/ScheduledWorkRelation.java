package top.sywyar.pixivdownload.plugin.api.schedule.work;

import java.nio.charset.StandardCharsets;

/**
 * 一件作品由哪个作者、搜索、合集、音乐或账号列表发现的插件中性关系。宿主可在重复发现时按
 * {@code relationType + relationId} 合并，目标作品执行器负责幂等写入插件历史。
 */
public record ScheduledWorkRelation(
        String relationType,
        String relationId,
        String payloadSchema,
        int payloadVersion,
        String payloadJson
) {

    public static final int MAX_PAYLOAD_BYTES = 65_536;

    public ScheduledWorkRelation {
        relationType = requireText(relationType, "relation type");
        relationId = requireText(relationId, "relation id");
        payloadSchema = requireText(payloadSchema, "relation payload schema");
        if (payloadVersion <= 0) {
            throw new IllegalArgumentException("relation payload version must be positive");
        }
        requireText(payloadJson, "relation payload");
        if (payloadJson.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("relation payload exceeds size limit");
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }
}
