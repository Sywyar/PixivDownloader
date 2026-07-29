package top.sywyar.pixivdownload.schedule.security;

import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledSensitiveFieldNames;
import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledCredentialText;

import java.util.regex.Matcher;
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
    private static final Pattern FIELD_ASSIGNMENT = Pattern.compile(
            "(?i)(?=((?<![A-Za-z0-9_])"
                    + "(?:['\"]([^'\"\\r\\n]{1,128})['\"]|"
                    + "([A-Za-z][^\\s:='\"{}\\[\\],;&]{0,127}))"
                    + "\\s*[:=]\\s*"
                    + "(?:['\"]([^'\"\\r\\n]*)['\"]|([^\\s;&,}\\]]+))))");
    // 兼容既有 Pixiv Cookie 串与 URL 查询串：在专用凭证 pattern 后清理剩余 key=value 对。
    private static final Pattern KEY_VALUE_PAIR =
            Pattern.compile("(?i)(^|[;\\s?&])([A-Za-z0-9_-]+)\\s*=\\s*([^;\\s&]+)");

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
        redacted = redactAssignments(redacted);
        return KEY_VALUE_PAIR.matcher(redacted).replaceAll(result -> {
            String fieldName = result.group(2);
            String value = unquote(result.group(3));
            if (ScheduledSensitiveFieldNames.isSensitiveMetadataFieldName(fieldName)
                    && ScheduledSensitiveFieldNames.isSafeMetadataValue(fieldName, value)) {
                return Matcher.quoteReplacement(result.group());
            }
            return Matcher.quoteReplacement(result.group(1) + "[redacted]");
        });
    }

    private static String redactAssignments(String text) {
        Matcher matcher = FIELD_ASSIGNMENT.matcher(text);
        StringBuilder redacted = null;
        int copiedUntil = 0;
        while (matcher.find()) {
            String fieldName = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            String value = matcher.group(4) != null ? matcher.group(4) : matcher.group(5);
            boolean sensitive = ScheduledSensitiveFieldNames.isSensitiveFieldName(fieldName)
                    || (ScheduledSensitiveFieldNames.isSensitiveMetadataFieldName(fieldName)
                    && !ScheduledSensitiveFieldNames.isSafeMetadataValue(fieldName, value));
            int assignmentStart = matcher.start(1);
            int assignmentEnd = matcher.end(1);
            if (!sensitive || assignmentStart < copiedUntil) {
                continue;
            }
            if (redacted == null) {
                redacted = new StringBuilder(text.length());
            }
            redacted.append(text, copiedUntil, assignmentStart).append("[redacted]");
            copiedUntil = assignmentEnd;
        }
        return redacted == null
                ? text
                : redacted.append(text, copiedUntil, text.length()).toString();
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' || first == '"') && first == last) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /** 判断一个不透明文本值是否带有凭证头、token、签名或 Cookie 的可识别形态。 */
    public static boolean containsCredentialMaterial(String text) {
        return ScheduledCredentialText.containsCredentialMaterial(text);
    }

    /** 判断 JSON 字段名是否声明了凭证、secret、token、签名或会话身份。 */
    public static boolean isSensitiveFieldName(String fieldName) {
        return ScheduledSensitiveFieldNames.isSensitiveFieldName(fieldName);
    }

    /** 判断字段名是否是必须结合值形态校验的凭证元数据。 */
    public static boolean isSensitiveMetadataFieldName(String fieldName) {
        return ScheduledSensitiveFieldNames.isSensitiveMetadataFieldName(fieldName);
    }

    /** 判断凭证元数据值是否为严格的非敏感计数或布尔状态。 */
    public static boolean isSafeMetadataValue(String fieldName, String value) {
        return ScheduledSensitiveFieldNames.isSafeMetadataValue(fieldName, value);
    }
}
