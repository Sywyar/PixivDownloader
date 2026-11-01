package top.sywyar.pixivdownload.captcha;

import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import top.sywyar.pixivdownload.i18n.MessageResolver;

/** 只记录受控原因和配置键，不记录凭据、证明、挑战或远端正文。 */
final class CaptchaDiagnostics {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(CaptchaDiagnostics.class);
    private final MessageResolver messages;

    CaptchaDiagnostics(MessageResolver messages) { this.messages = messages; }

    IllegalStateException unavailable(String policy, String reason, String field) {
        LOG.warn(messages.getForLog("log.unavailable", MDC.get("operationVerificationId"), policy, reason, field));
        return new IllegalStateException(reason);
    }

    void rejected(String policy, String reason) {
        LOG.info(messages.getForLog("log.rejected", MDC.get("operationVerificationId"), policy, reason));
    }

    void keyInitialized() { LOG.info(messages.getForLog("log.key-initialized")); }
}
