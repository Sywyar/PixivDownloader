package top.sywyar.pixivdownload.ai.model;

/**
 * 一条对话消息（OpenAI Chat Completions 的 {@code messages[]} 元素）。
 * <p>请求与响应共用此形态：{@code role} 取 {@link #ROLE_SYSTEM} / {@link #ROLE_USER} / {@link #ROLE_ASSISTANT}。
 *
 * @param role    角色
 * @param content 文本内容
 */
public record AiChatMessage(String role, String content) {

    /**
     * AI 消息的系统角色标识。
     */
    public static final String ROLE_SYSTEM = "system";
    /**
     * AI 消息的用户角色标识。
     */
    public static final String ROLE_USER = "user";
    /**
     * AI 消息的助手角色标识。
     */
    public static final String ROLE_ASSISTANT = "assistant";

    /**
     * 执行系统并返回结果。
     *
     * @param content 内容
     * @return 方法返回的 {@code AiChatMessage} 实例
     */
    public static AiChatMessage system(String content) {
        return new AiChatMessage(ROLE_SYSTEM, content);
    }

    /**
     * 执行用户并返回结果。
     *
     * @param content 内容
     * @return 方法返回的 {@code AiChatMessage} 实例
     */
    public static AiChatMessage user(String content) {
        return new AiChatMessage(ROLE_USER, content);
    }

    /**
     * 执行助手并返回结果。
     *
     * @param content 内容
     * @return 方法返回的 {@code AiChatMessage} 实例
     */
    public static AiChatMessage assistant(String content) {
        return new AiChatMessage(ROLE_ASSISTANT, content);
    }
}
