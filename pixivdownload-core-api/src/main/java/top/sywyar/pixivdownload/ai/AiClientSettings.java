package top.sywyar.pixivdownload.ai;

/**
 * 不可变的 AI 调用参数快照。
 * <p>
 * 调用方可从当前配置或一次性输入构造该快照；契约不规定配置存储、热重载或测试入口的实现方式。
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

    @Override
    public String toString() {
        return "AiClientSettings[baseUrl=" + baseUrl
                + ", apiKey=***, model=" + model
                + ", useProxy=" + useProxy + "]";
    }
}
