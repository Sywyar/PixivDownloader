package top.sywyar.pixivdownload.i18n;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.util.Arrays;

@Getter
public class LocalizedException extends RuntimeException {

    private final HttpStatusCode status;
    private final String messageCode;
    private final String defaultMessage;
    private final Object[] messageArgs;

    public LocalizedException(HttpStatusCode status, String messageCode, String defaultMessage, Object... messageArgs) {
        super(defaultMessage != null ? defaultMessage : messageCode);
        this.status = status;
        this.messageCode = messageCode;
        this.defaultMessage = defaultMessage;
        this.messageArgs = messageArgs == null ? new Object[0] : Arrays.copyOf(messageArgs, messageArgs.length);
    }

    public static LocalizedException badRequest(String messageCode, String defaultMessage, Object... messageArgs) {
        return new LocalizedException(HttpStatus.BAD_REQUEST, messageCode, defaultMessage, messageArgs);
    }
}
