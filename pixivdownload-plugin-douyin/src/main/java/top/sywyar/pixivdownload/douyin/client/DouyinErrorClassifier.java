package top.sywyar.pixivdownload.douyin.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

public final class DouyinErrorClassifier {

    private static final Set<Integer> RATE_LIMIT_STATUS_CODES = Set.of(
            7,
            429,
            50_001,
            2_190_001,
            2_190_020,
            28_003_017,
            28_003_018);

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

    public static boolean looksLikeSignatureText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("a_bogus")
                || normalized.contains("x-bogus")
                || normalized.contains("signature")
                || normalized.contains("签名");
    }

    public static DouyinClientErrorCode classifyHttpStatus(int status, byte[] body) {
        if (status == 401) {
            return DouyinClientErrorCode.COOKIE_EXPIRED;
        }
        if (status == 403) {
            if (looksLikeLoginOrRiskPage(body)) {
                return DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE;
            }
            String text = body == null || body.length == 0
                    ? null : new String(body, StandardCharsets.UTF_8);
            return looksLikeExplicitHttpPermissionText(text)
                    ? DouyinClientErrorCode.PERMISSION_DENIED
                    : DouyinClientErrorCode.HTTP_FORBIDDEN;
        }
        if (status == 404) {
            return DouyinClientErrorCode.UPSTREAM_NOT_FOUND;
        }
        if (status == 429) {
            return DouyinClientErrorCode.RATE_LIMITED;
        }
        if (status >= 500) {
            return DouyinClientErrorCode.UPSTREAM_SERVER_ERROR;
        }
        if (status >= 400) {
            return DouyinClientErrorCode.UPSTREAM_CLIENT_ERROR;
        }
        return null;
    }

    public static DouyinClientErrorCode classifyJsonStatus(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return null;
        }
        int status = root.path("status_code").asInt(0);
        String message = statusText(root,
                "status_msg", "message", "prompts", "log_pb", "search_nil_info");
        if (status == 0 && !looksLikeLoginOrRiskText(message) && looksLikePermissionText(message)) {
            return DouyinClientErrorCode.PERMISSION_DENIED;
        }
        if (status == 0 && (message == null || !looksLikeLoginOrRiskText(message))) {
            return null;
        }
        if (status == 2483) {
            return DouyinClientErrorCode.COOKIE_EXPIRED;
        }
        if (containsAny(message, "verify", "captcha", "验证码", "验证")) {
            return DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE;
        }
        if (looksLikeSignatureText(message)) {
            return DouyinClientErrorCode.SIGNATURE_REQUIRED;
        }
        if (containsAny(message, "region", "country", "地区", "区域", "当前区域")) {
            return DouyinClientErrorCode.REGION_RESTRICTED;
        }
        if (looksLikePermissionText(message)) {
            return DouyinClientErrorCode.PERMISSION_DENIED;
        }
        if (isRateLimited(status, message)) {
            return DouyinClientErrorCode.RATE_LIMITED;
        }
        if (containsAny(message, "cookie", "login", "登录", "请先登录")) {
            return DouyinClientErrorCode.COOKIE_EXPIRED;
        }
        if (containsAny(message, "风险", "风控")) {
            return DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE;
        }
        return status == 0 ? null : DouyinClientErrorCode.UPSTREAM_CLIENT_ERROR;
    }

    private static boolean looksLikePermissionText(String message) {
        return containsAny(message,
                "permission", "private", "not allowed", "hidden",
                "visible only to me", "only visible to me",
                "无权限", "私密", "不可见", "隐藏", "仅自己可见", "仅本人可见");
    }

    private static boolean looksLikeExplicitHttpPermissionText(String message) {
        return containsAny(message,
                "permission denied", "not allowed to view",
                "list is private", "private list", "list is hidden",
                "liked works are hidden", "has hidden their liked", "has hidden the list",
                "visible only to me", "only visible to me",
                "无权限", "私密", "不可见", "已隐藏", "仅自己可见", "仅本人可见");
    }

    private static boolean isRateLimited(int status, String message) {
        return RATE_LIMIT_STATUS_CODES.contains(status)
                || containsAny(message,
                "rate limit", "rate-limit", "rate_limit",
                "too many request", "too many attempt",
                "too frequent", "frequent request", "request frequency",
                "quota exceeded", "quota exhausted",
                "访问频繁", "请求频繁", "操作频繁", "过于频繁", "太频繁", "频率过高",
                "请求过快", "请求太快", "请求过多", "次数过多", "限流", "频控",
                "配额已用完", "额度不足");
    }

    private static String statusText(JsonNode root, String... fields) {
        StringBuilder combined = new StringBuilder();
        for (String field : fields) {
            appendText(root.path(field), combined);
        }
        return combined.isEmpty() ? null : combined.toString();
    }

    private static void appendText(JsonNode node, StringBuilder combined) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            if (!text.isEmpty()) {
                if (!combined.isEmpty()) {
                    combined.append(' ');
                }
                combined.append(text);
            }
            return;
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                appendText(child, combined);
            }
        }
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
