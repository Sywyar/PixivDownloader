package top.sywyar.pixivdownload.download.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import top.sywyar.pixivdownload.download.response.ErrorResponse;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.util.Locale;

/** 下载工作台控制器共用的本地化失败投影。 */
@Slf4j
public final class WorkbenchErrorResponses {

    private WorkbenchErrorResponses() {
    }

    public static ResponseEntity<ErrorResponse> localized(
            LocalizedException failure,
            MessageResolver messages,
            Locale locale) {
        String message = messages.getOrDefault(
                locale,
                failure.messageCode(),
                failure.defaultMessage(),
                failure.messageArgs());
        String logDetail = messages.getOrDefault(
                Locale.getDefault(),
                failure.messageCode(),
                failure.defaultMessage(),
                failure.messageArgs());
        log.warn(messages.getForLog("workbench.log.request.failed", logDetail));
        return ResponseEntity.status(failure.status()).body(new ErrorResponse(failure.messageCode(), message));
    }
}
