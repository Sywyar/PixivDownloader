package top.sywyar.pixivdownload.schedule.security;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 计划任务跨持久化、日志、通知和展示边界共用的凭证识别与文本脱敏器。
 *
 * <p>调用方仍应优先保存稳定错误码和受控字段；本类是异常文本等兼容路径的最后一道防线。
 */
public final class ScheduleCredentialRedactor {

    private static final Pattern COOKIE_HEADER =
            Pattern.compile("(?i)\\b(cookie\\s*[:=]\\s*)[^\\r\\n]+");
    private static final Pattern AUTHORIZATION_HEADER = Pattern.compile(
            "(?i)\\b((?:proxy-)?authorization\\s*[:=]\\s*)"
                    + "(?:[A-Za-z][A-Za-z0-9+.-]*\\s+)?[^\\s,;]+");
    private static final Pattern BEARER_VALUE =
            Pattern.compile("(?i)\\b(bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern PHPSESSID =
            Pattern.compile("(?i)\\b(PHPSESSID\\s*=\\s*)[^;\\s&]+");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)\\b((?:access[_-]?token|refresh[_-]?token|id[_-]?token|token|"
                    + "x-amz-security-token|api[_-]?key|client[_-]?secret|secret|password|passwd|"
                    + "credential|x-amz-credential|sig|signature|x-amz-signature)"
                    + "\\s*[:=]\\s*)[^\\s;&,]+");
    // 兼容既有 Pixiv Cookie 串与 URL 查询串：在专用凭证 pattern 后清理剩余 key=value 对。
    private static final Pattern KEY_VALUE_PAIR =
            Pattern.compile("(?i)(^|[;\\s?&])([A-Za-z0-9_-]+\\s*=\\s*)[^;\\s&]+");

    private ScheduleCredentialRedactor() {
    }

    /** 返回不含已知凭证形态的文本；{@code null} 原样返回。 */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String redacted = COOKIE_HEADER.matcher(text).replaceAll("[redacted]");
        redacted = AUTHORIZATION_HEADER.matcher(redacted).replaceAll("[redacted]");
        redacted = BEARER_VALUE.matcher(redacted).replaceAll("[redacted]");
        redacted = PHPSESSID.matcher(redacted).replaceAll("[redacted]");
        redacted = SENSITIVE_ASSIGNMENT.matcher(redacted).replaceAll("[redacted]");
        return KEY_VALUE_PAIR.matcher(redacted).replaceAll("$1[redacted]");
    }

    /** 判断一个不透明文本值是否带有凭证头、token、签名或 Cookie 的可识别形态。 */
    public static boolean containsCredentialMaterial(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return COOKIE_HEADER.matcher(text).find()
                || AUTHORIZATION_HEADER.matcher(text).find()
                || BEARER_VALUE.matcher(text).find()
                || PHPSESSID.matcher(text).find()
                || SENSITIVE_ASSIGNMENT.matcher(text).find();
    }

    /** 判断 JSON 字段名是否声明了凭证、secret、token、签名或会话身份。 */
    public static boolean isSensitiveFieldName(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }
        String normalized = fieldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("cookie")
                || normalized.contains("authorization")
                || normalized.contains("credential")
                || normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.endsWith("secret")
                || normalized.endsWith("token")
                || normalized.endsWith("apikey")
                || normalized.endsWith("signature")
                || normalized.endsWith("sessionid")
                || normalized.equals("signedurl")
                || normalized.equals("temporaryurl");
    }
}
