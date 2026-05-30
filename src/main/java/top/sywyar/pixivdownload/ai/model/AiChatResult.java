package top.sywyar.pixivdownload.ai.model;

/**
 * 一次对话调用的结果（已从 OpenAI 兼容响应中抽取关键字段）。
 *
 * @param content          助手回复的文本内容（{@code choices[0].message.content}）
 * @param finishReason     结束原因（{@code stop} / {@code length} / …），可能为 {@code null}
 * @param promptTokens     提示词消耗 token 数，可能为 {@code null}
 * @param completionTokens 生成消耗 token 数，可能为 {@code null}
 * @param totalTokens      总消耗 token 数，可能为 {@code null}
 */
public record AiChatResult(
        String content,
        String finishReason,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {
}
