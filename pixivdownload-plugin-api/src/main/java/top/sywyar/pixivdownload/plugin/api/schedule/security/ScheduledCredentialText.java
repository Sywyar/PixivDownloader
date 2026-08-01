package top.sywyar.pixivdownload.plugin.api.schedule.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计划任务稳定契约共用的纯 JDK 凭证文本判定。它只识别明确的请求头、Cookie、token、secret 与签名形态，
 * 供插件值对象在进入宿主持久化、队列或展示边界前拒绝敏感材料；来源 owner 仍须补充自己的专属字段赋值形态。
 */
public final class ScheduledCredentialText {

    private static final Pattern COOKIE_HEADER =
            Pattern.compile("(?i)\\b(cookie\\s*[:=]\\s*)[^\\r\\n]+");
    private static final Pattern AUTHORIZATION_HEADER = Pattern.compile(
            "(?i)\\b((?:proxy-)?authorization\\s*[:=]\\s*)"
                    + "(?:[A-Za-z][A-Za-z0-9+.-]*\\s+)?[^\\s,;]+");
    private static final Pattern BEARER_VALUE =
            Pattern.compile(
                    "(?i)\\b(bearer\\s+)"
                            + "(?=[A-Za-z0-9._~+/=-]{6,}(?:[,;\\s]|$))"
                            + "(?:(?=[A-Za-z0-9._~+/=-]*[0-9._~+/=-])"
                            + "[A-Za-z0-9._~+/=-]+|[A-Za-z0-9]{20,})");
    private static final Pattern FIELD_ASSIGNMENT = Pattern.compile(
            "(?i)(?=(?<![A-Za-z0-9_])"
                    + "(?:['\"]([^'\"\\r\\n]{1,128})['\"]|"
                    + "([A-Za-z][^\\s:='\"{}\\[\\],;&]{0,127}))"
                    + "\\s*[:=]\\s*"
                    + "(?:['\"]([^'\"\\r\\n]*)['\"]|([^\\s;&,}\\]]+)))");

    private ScheduledCredentialText() {
    }

    /** 判断不透明文本是否带有可识别的凭证头、Cookie、token、secret 或签名形态。 */
    public static boolean containsCredentialMaterial(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (COOKIE_HEADER.matcher(text).find()
                || AUTHORIZATION_HEADER.matcher(text).find()
                || BEARER_VALUE.matcher(text).find()) {
            return true;
        }
        Matcher matcher = FIELD_ASSIGNMENT.matcher(text);
        while (matcher.find()) {
            String fieldName = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String value = matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            if (ScheduledSensitiveFieldNames.isSensitiveFieldName(fieldName)
                    || (ScheduledSensitiveFieldNames.isSensitiveMetadataFieldName(fieldName)
                    && !ScheduledSensitiveFieldNames.isSafeMetadataValue(fieldName, value))) {
                return true;
            }
        }
        return false;
    }
}
