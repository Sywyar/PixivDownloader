package top.sywyar.pixivdownload.plugin.api.schedule.work;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 可跨 pending、重启与插件 reload 的中性作品信封。载荷只能保存长期可重建信息，禁止 Cookie、Authorization、
 * 临时媒体 URL、一次性 token、客户端对象、异常或插件实例。
 */
public record ScheduledWork(
        ScheduledWorkKey key,
        String payloadSchema,
        int payloadVersion,
        String payloadJson,
        ScheduledWorkPresentation presentation,
        List<ScheduledWorkRelation> relations
) {

    /**
     * 载荷允许占用的最大 UTF-8 字节数。
     */
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;
    /**
     * 允许的最大关系列表。
     */
    public static final int MAX_RELATIONS = 128;

    /**
     * 创建 {@code ScheduledWork} 实例。
     *
     * @param key 键
     * @param payloadSchema 载荷模式定义
     * @param payloadVersion 载荷版本
     * @param payloadJson 载荷JSON
     * @param presentation 展示信息
     * @param relations 关系列表
     */
    public ScheduledWork {
        if (key == null) {
            throw new IllegalArgumentException("work key must not be null");
        }
        if (payloadSchema == null || payloadSchema.isBlank()) {
            throw new IllegalArgumentException("work payload schema must not be blank");
        }
        if (payloadVersion <= 0) {
            throw new IllegalArgumentException("work payload version must be positive");
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalArgumentException("work payload must not be blank");
        }
        if (payloadJson.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("work payload exceeds size limit");
        }
        payloadSchema = payloadSchema.trim();
        presentation = presentation == null ? ScheduledWorkPresentation.empty() : presentation;
        relations = relations == null ? List.of() : List.copyOf(relations);
        if (relations.size() > MAX_RELATIONS) {
            throw new IllegalArgumentException("work relations exceed count limit");
        }
    }
}
