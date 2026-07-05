package top.sywyar.pixivdownload.ai.probe;

import top.sywyar.pixivdownload.ai.model.AiChatMessage;

import java.util.List;

/**
 * AI 连接测试（GUI「测试 AI 连接」/ {@code POST /api/gui/ai-test}）的探测请求体。
 * <p>
 * 与小说翻译请求实体一致：把「发什么消息」封装成专用<b>实体类</b>
 * （而非在调用处内联拼装 {@code List<AiChatMessage>}），统一交给 {@link top.sywyar.pixivdownload.ai.AiService#chatTest}
 * 发送。探测提示词使用固定英文版本，要求模型只回一个词，尽量少消耗 token；不需要业务变量，故无字段。
 */
public record ConnectivityProbeRequest() {

    /** 调用类型标签，供 {@link top.sywyar.pixivdownload.ai.AiService} 在日志中标识本次请求用途。 */
    public static final String CALL_TYPE = "probe.connectivity";

    private static final String SYSTEM_PROMPT = "You are a connectivity probe.";
    private static final String USER_PROMPT = "Reply with the single word: OK";

    /** 把请求转换为 OpenAI 兼容协议的对话消息序列（系统提示 + 探测输入）。 */
    public List<AiChatMessage> toMessages() {
        return List.of(
                AiChatMessage.system(SYSTEM_PROMPT),
                AiChatMessage.user(USER_PROMPT));
    }
}
