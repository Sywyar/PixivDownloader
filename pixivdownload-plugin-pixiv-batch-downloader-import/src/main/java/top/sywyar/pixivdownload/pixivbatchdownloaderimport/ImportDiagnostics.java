package top.sywyar.pixivdownload.pixivbatchdownloaderimport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.util.Set;

/** 只记录受控身份、错误码和异常位置，不输出请求正文、凭据或异常消息。 */
public final class ImportDiagnostics {
    private static final Logger LOG = LoggerFactory.getLogger(ImportDiagnostics.class);
    private static final Set<String> FILE_CODES = Set.of("IMPORT_BUSY", "IMPORT_OWNER_UNAVAILABLE",
            "INVALID_WORK_TITLE", "INVALID_SOURCE_ROOT", "INVALID_SOURCE_PATH", "IMPORT_SIZE_LIMIT",
            "SOURCE_CHANGED", "UNSUPPORTED_FILE_FORMAT");
    private final MessageResolver messages;

    public ImportDiagnostics(MessageResolver messages) { this.messages = messages; }

    public void event(Level level, String key, Object... arguments) {
        if (LOG.isEnabledForLevel(level)) LOG.atLevel(level).log(messages.getForLog(key, arguments));
    }

    public void failure(Level level, String stage, String trace, String work, String code, Throwable failure) {
        event(level, "log.failure", stage, trace, work, code, detail(failure));
    }

    static String detail(Throwable failure) {
        String detail = "-";
        if (failure != null) {
            detail = failure.getClass().getName();
            if (failure instanceof PixivAjaxException pixiv)
                detail += " failure=" + pixiv.failure() + " http=" + pixiv.statusCode();
            else if (FILE_CODES.contains(failure.getMessage() == null ? "" : failure.getMessage()))
                detail += " reason=" + failure.getMessage();
            StackTraceElement[] frames = failure.getStackTrace();
            if (frames.length > 0) detail += " at " + frames[0];
        }
        return detail;
    }
}
