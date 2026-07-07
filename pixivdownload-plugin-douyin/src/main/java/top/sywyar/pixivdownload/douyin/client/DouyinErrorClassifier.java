package top.sywyar.pixivdownload.douyin.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class DouyinErrorClassifier {

    private DouyinErrorClassifier() {
    }

    public static boolean looksLikeLoginOrRiskPage(byte[] body) {
        if (body == null || body.length == 0) {
            return false;
        }
        return looksLikeLoginOrRiskText(new String(body, StandardCharsets.UTF_8));
    }

    public static boolean looksLikeLoginOrRiskText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("验证码")
                || normalized.contains("验证")
                || normalized.contains("captcha")
                || normalized.contains("verify")
                || normalized.contains("waf-jschallenge")
                || normalized.contains("ttgcaptcha")
                || normalized.contains("verifycenter")
                || normalized.contains("rc-verifycenter")
                || normalized.contains("bdms.")
                || normalized.contains("sec.douyin.com")
                || normalized.contains("login")
                || normalized.contains("请先登录")
                || normalized.contains("登录后")
                || normalized.contains("风险")
                || normalized.contains("风控");
    }

    public static DouyinClientErrorCode classifyJsonStatus(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return null;
        }
        int status = root.path("status_code").asInt(0);
        String message = firstText(root, "status_msg", "message", "prompts", "log_pb");
        if (status == 0 && (message == null || !looksLikeLoginOrRiskText(message))) {
            return null;
        }
        if (status == 2483 || containsAny(message, "cookie", "login", "登录", "请先登录")) {
            return DouyinClientErrorCode.COOKIE_EXPIRED;
        }
        if (containsAny(message, "verify", "captcha", "验证", "风险", "风控")) {
            return DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE;
        }
        if (containsAny(message, "a_bogus", "x-bogus", "signature", "签名")) {
            return DouyinClientErrorCode.SIGNATURE_REQUIRED;
        }
        return status == 0 ? null : DouyinClientErrorCode.UNSUPPORTED_CONTENT;
    }

    private static String firstText(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode value = root.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (normalized.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
