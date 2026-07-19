package top.sywyar.pixivdownload.core.work.service;

import top.sywyar.pixivdownload.core.work.model.WorkType;

/**
 * 把上游作品响应捕获到核心 sidecar 与共享可重建投影的稳定端口。
 *
 * <p>调用方只传递原始 UTF-8 JSON 文本；介质私有记录、内容与持久化模型不属于此契约。
 */
public interface WorkMetadataCapture {

    /**
     * 捕获一份作品原始响应。
     *
     * @param type             核心作品类型
     * @param workId           作品 ID
     * @param workJson         作品主体的原始 JSON 文本
     * @param supplementalJson 同次请求链路捕获的补充 JSON；没有时为 {@code null}
     * @param source           捕获来源
     */
    void capture(
            WorkType type,
            long workId,
            String workJson,
            String supplementalJson,
            String source
    );

    default void capture(WorkType type, long workId, String workJson, String source) {
        capture(type, workId, workJson, null, source);
    }

    default void captureForwarded(WorkType type, long workId, String workJson) {
        capture(type, workId, workJson, null, "forward");
    }
}
