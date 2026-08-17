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

    /**
     * 载荷允许占用的最大 UTF-8 字节数。
     */
    public static final int MAX_PAYLOAD_BYTES = 65_536;
    /**
     * 关系类型允许占用的最大 UTF-8 字节数。
     */
    public static final int MAX_RELATION_TYPE_BYTES = 128;
    /**
     * 关系标识允许占用的最大 UTF-8 字节数。
     */
    public static final int MAX_RELATION_ID_BYTES = 512;
    /**
     * 载荷模式定义允许占用的最大 UTF-8 字节数。
     */
    public static final int MAX_PAYLOAD_SCHEMA_BYTES = 128;

    /**
     * 创建 {@code ScheduledWorkRelation} 实例。
     *
     * @param relationType 关系类型
     * @param relationId 关系标识
     * @param payloadSchema 载荷模式定义
     * @param payloadVersion 载荷版本
     * @param payloadJson 载荷JSON
     */
    public ScheduledWorkRelation {
        relationType = requireText(
                relationType, "relation type", MAX_RELATION_TYPE_BYTES);
        relationId = requireText(
                relationId, "relation id", MAX_RELATION_ID_BYTES);
        payloadSchema = requireText(
                payloadSchema, "relation payload schema", MAX_PAYLOAD_SCHEMA_BYTES);
        if (payloadVersion <= 0) {
            throw new IllegalArgumentException("relation payload version must be positive");
        }
        requireText(payloadJson, "relation payload", MAX_PAYLOAD_BYTES);
        if (payloadJson.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("relation payload exceeds size limit");
        }
    }

    private static String requireText(String value, String label, int maxBytes) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + " must not contain NUL");
        }
        if (normalized.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(label + " exceeds size limit");
        }
        return normalized;
    }
}
