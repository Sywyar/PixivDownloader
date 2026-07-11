package top.sywyar.pixivdownload.plugin.api.schedule.source;

import java.nio.charset.StandardCharsets;

/** 来源插件拥有 schema/version 的不透明可恢复检查点。宿主不解释其中的作品顺序。 */
public record ScheduledCheckpoint(
        String schema,
        int version,
        String payloadJson
) {

    public static final int MAX_PAYLOAD_BYTES = 262_144;

    public ScheduledCheckpoint {
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("checkpoint schema must not be blank");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("checkpoint version must be positive");
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalArgumentException("checkpoint payload must not be blank");
        }
        schema = schema.trim();
        if (payloadJson.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("checkpoint payload exceeds size limit");
        }
    }
}
