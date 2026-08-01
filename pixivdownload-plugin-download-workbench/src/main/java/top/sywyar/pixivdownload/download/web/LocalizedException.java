package top.sywyar.pixivdownload.download.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.util.Arrays;

/** 下载工作台私有的可本地化 HTTP 失败，不向宿主或共享 API 泄漏插件语义。 */
public final class LocalizedException extends RuntimeException {

    private final HttpStatusCode status;
    private final String messageCode;
    private final String defaultMessage;
    private final Object[] messageArgs;

    public LocalizedException(
            HttpStatusCode status,
            String messageCode,
            String defaultMessage,
            Object... messageArgs) {
        super(defaultMessage != null ? defaultMessage : messageCode);
        this.status = status;
        this.messageCode = messageCode;
        this.defaultMessage = defaultMessage;
        this.messageArgs = messageArgs == null ? new Object[0] : Arrays.copyOf(messageArgs, messageArgs.length);
    }

    public static LocalizedException badRequest(
            String messageCode,
            String defaultMessage,
            Object... messageArgs) {
        return new LocalizedException(HttpStatus.BAD_REQUEST, messageCode, defaultMessage, messageArgs);
    }

    public HttpStatusCode status() {
        return status;
    }

    public String messageCode() {
        return messageCode;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public Object[] messageArgs() {
        return Arrays.copyOf(messageArgs, messageArgs.length);
    }
}
