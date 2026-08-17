package top.sywyar.pixivdownload.ai;

/**
 * 可控的 AI 能力调用异常。异常消息必须已经脱敏。
 */
public class AiClientException extends Exception {
    /**
     * 创建 {@code AiClientException} 实例。
     *
     * @param message 消息
     */
    public AiClientException(String message) {
        super(message);
    }

    /**
     * 创建 {@code AiClientException} 实例。
     *
     * @param message 消息
     * @param cause {@code cause} 对应的值
     */
    public AiClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
