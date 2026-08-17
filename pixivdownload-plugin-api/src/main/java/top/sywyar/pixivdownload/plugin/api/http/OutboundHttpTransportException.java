package top.sywyar.pixivdownload.plugin.api.http;

/** 网络或传输失败。HTTP 错误状态由 {@link OutboundHttpResponse} 表示。 */
public class OutboundHttpTransportException extends RuntimeException {

    /**
     * 使用说明消息创建异常。
     *
     * @param message 已脱敏的失败说明
     */
    public OutboundHttpTransportException(String message) {
        super(message);
    }

    /**
     * 使用说明消息和原始原因创建异常。
     *
     * @param message 已脱敏的失败说明
     * @param cause 原始传输异常
     */
    public OutboundHttpTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
