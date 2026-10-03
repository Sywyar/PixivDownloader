package top.sywyar.pixivdownload.update;

import com.fasterxml.jackson.core.JsonProcessingException;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogHttpClient;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLException;

/** 更新检查的受控失败原因；异常详情仅供日志，界面使用原因码与 HTTP 状态。 */
final class UpdateCheckException extends IOException {
    enum Reason {
        NOT_CONFIGURED, INVALID_URL, ADDRESS_BLOCKED, DNS_FAILED, CONNECTION_FAILED,
        TIMEOUT, TLS_FAILED, MANIFEST_HTTP_ERROR, SIGNATURE_HTTP_ERROR, RESPONSE_TOO_LARGE,
        INVALID_MANIFEST, SIGNATURE_INVALID, EXPIRED, CHANNEL_MISMATCH, ROLLBACK,
        SEQUENCE_REUSED, VERSION_REPLACED, TRUST_STATE_UNAVAILABLE, NETWORK_FAILED, UNKNOWN
    }

    final Reason reason;
    final Integer httpStatus;

    UpdateCheckException(Reason reason, String detail) {
        this(reason, detail, null, null);
    }

    UpdateCheckException(Reason reason, Throwable cause) {
        this(reason, cause.getMessage(), cause, null);
    }

    private UpdateCheckException(Reason reason, String detail, Throwable cause, Integer httpStatus) {
        super(detail, cause);
        this.reason = reason;
        this.httpStatus = httpStatus;
    }

    static UpdateCheckException classify(Throwable failure, boolean signature) {
        if (failure instanceof UpdateCheckException known) return known;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            Reason reason = cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException ? Reason.TIMEOUT
                    : cause instanceof UnknownHostException ? Reason.DNS_FAILED
                    : cause instanceof SSLException ? Reason.TLS_FAILED
                    : cause instanceof ConnectException ? Reason.CONNECTION_FAILED
                    : cause instanceof JsonProcessingException ? Reason.INVALID_MANIFEST : null;
            if (reason != null) return new UpdateCheckException(reason, failure);
        }
        if (failure instanceof PluginCatalogHttpClient.HttpStatusException http) {
            return new UpdateCheckException(signature ? Reason.SIGNATURE_HTTP_ERROR : Reason.MANIFEST_HTTP_ERROR,
                    failure.getMessage(), failure, http.statusCode());
        }
        if (failure instanceof PluginCatalogException catalog) {
            return new UpdateCheckException(switch (catalog.code()) {
                case INSECURE_URL -> Reason.INVALID_URL;
                case BLOCKED_ADDRESS -> Reason.ADDRESS_BLOCKED;
                case DOWNLOAD_TOO_LARGE -> Reason.RESPONSE_TOO_LARGE;
                default -> Reason.NETWORK_FAILED;
            }, failure);
        }
        return new UpdateCheckException(Reason.UNKNOWN, failure);
    }
}
