package top.sywyar.pixivdownload.ai.controller;

/**
 * GUI "测试 AI 连接" 端点的响应。
 *
 * @param success 是否成功拿到模型回复
 * @param error   失败摘要（已脱敏 / 截断，绝不含 API Key）；成功时为 {@code null}
 * @param reply   成功时模型回复的简短摘录（已截断）；失败时为 {@code null}
 */
public record AiTestResponse(
        boolean success,
        String error,
        String reply
) {
    public static AiTestResponse ok(String reply) {
        return new AiTestResponse(true, null, reply);
    }

    public static AiTestResponse fail(String error) {
        return new AiTestResponse(false, error, null);
    }
}
