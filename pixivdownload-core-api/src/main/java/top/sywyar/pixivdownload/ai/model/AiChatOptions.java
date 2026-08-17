package top.sywyar.pixivdownload.ai.model;

/**
 * 单次对话的可选调参。与"发什么消息"（{@link AiChatMessage}）和客户端连接设置解耦。
 * <p>
 * 字段均为可空：为 {@code null} 时对应参数不写入请求体，交由服务端默认值决定。
 *
 * @param temperature 采样温度（0~2）；{@code null} 表示不指定
 * @param maxTokens   最大生成 token 数；{@code null} 表示不指定
 * @param jsonObject  是否要求模型以 JSON 对象格式输出（OpenAI {@code response_format={"type":"json_object"}}）。
 *                    便于后续功能（如翻译）拿到可直接解析的结构化结果；注意：仍需在 prompt 中明确要求返回 JSON。
 */
public record AiChatOptions(
        Double temperature,
        Integer maxTokens,
        boolean jsonObject
) {

    private static final AiChatOptions DEFAULTS = new AiChatOptions(null, null, false);

    /**
     * 无额外调参：温度 / 最大 token 跟随服务端默认，纯文本输出。
     *
     * @return 方法返回的 {@code AiChatOptions} 实例
     */
    public static AiChatOptions defaults() {
        return DEFAULTS;
    }

    /**
     * 要求 JSON 对象输出（其余参数跟随服务端默认）。
     *
     * @return 方法返回的 {@code AiChatOptions} 实例
     */
    public static AiChatOptions json() {
        return new AiChatOptions(null, null, true);
    }

    /**
     * 返回更新{@code temperature} 对应的值后的副本。
     *
     * @param value 值
     * @return 方法返回的 {@code AiChatOptions} 实例
     */
    public AiChatOptions withTemperature(Double value) {
        return new AiChatOptions(value, maxTokens, jsonObject);
    }

    /**
     * 返回更新{@code maxTokens} 对应的值后的副本。
     *
     * @param value 值
     * @return 方法返回的 {@code AiChatOptions} 实例
     */
    public AiChatOptions withMaxTokens(Integer value) {
        return new AiChatOptions(temperature, value, jsonObject);
    }
}
