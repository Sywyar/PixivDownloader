package top.sywyar.pixivdownload.ai.model;

/**
 * 一条对话消息（OpenAI Chat Completions 的 {@code messages[]} 元素）。
 * <p>请求与响应共用此形态：{@code role} 取 {@link #ROLE_SYSTEM} / {@link #ROLE_USER} / {@link #ROLE_ASSISTANT}。
 *
 * @param role    角色
 * @param content 文本内容
 */
public record AiChatMessage(String role, String content) {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public static AiChatMessage system(String content) {
        return new AiChatMessage(ROLE_SYSTEM, content);
    }

    public static AiChatMessage user(String content) {
        return new AiChatMessage(ROLE_USER, content);
    }

    public static AiChatMessage assistant(String content) {
        return new AiChatMessage(ROLE_ASSISTANT, content);
    }
}
