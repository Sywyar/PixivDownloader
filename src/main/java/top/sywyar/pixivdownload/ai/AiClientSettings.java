package top.sywyar.pixivdownload.ai;

/**
 * 不可变的 AI 调用参数快照。
 * <p>
 * {@link AiService} 在请求时使用此对象，从而既支持热重载（修改配置不必重启），又允许 GUI 在尚未保存配置时
 * 直接用表单值发起连通性测试（见 {@code /api/gui/ai-test}）。
 *
 * @param baseUrl  OpenAI 兼容端点基础地址（如 {@code https://api.openai.com/v1}）
 * @param apiKey   API Key；仅在请求过程中使用，绝不写日志 / 失败摘要 / 响应
 * @param model    模型名
 * @param useProxy 是否走已配置的 HTTP 代理
 */
public record AiClientSettings(
        String baseUrl,
        String apiKey,
        String model,
        boolean useProxy
) {
}
