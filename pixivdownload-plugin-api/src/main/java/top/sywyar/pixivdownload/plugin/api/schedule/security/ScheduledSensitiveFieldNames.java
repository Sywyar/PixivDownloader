package top.sywyar.pixivdownload.plugin.api.schedule.security;

import java.util.Locale;
import java.util.regex.Pattern;

/** 计划任务稳定契约共用的纯 JDK 敏感字段名判定。 */
public final class ScheduledSensitiveFieldNames {

    private static final Pattern SEPARATED_SID = Pattern.compile(
            "(?i)(?:^|[._-])sid(?:[._-]?(?:value|header|guard|tt)){0,2}$");
    private static final Pattern CAMEL_CASE_SID = Pattern.compile(
            ".*S(?i:id(?:(?:value|header|guard|tt)){0,2})$");

    private ScheduledSensitiveFieldNames() {
    }

    /**
     * 判断字段名是否声明 Cookie、会话、token、凭证、口令、secret、签名或临时地址材料。
     * 分隔符与大小写不影响判定，例如 {@code refresh_token}、{@code PHPSESSID} 与
     * {@code proxy-authorization} 都会被拒绝。
     */
    public static boolean isSensitiveFieldName(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }
        String trimmed = fieldName.trim();
        String normalized = trimmed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.endsWith("count")
                || normalized.endsWith("algorithm")
                || normalized.endsWith("mode")
                || normalized.endsWith("required")
                || normalized.endsWith("present")
                || normalized.endsWith("bound")
                || normalized.endsWith("dependent")
                || normalized.endsWith("enabled")
                || normalized.endsWith("type")
                || normalized.endsWith("version")) {
            return false;
        }
        return endsWithSensitiveSemantic(normalized, "cookie")
                || endsWithSensitiveSemantic(normalized, "cookiejar")
                || endsWithSensitiveSemantic(normalized, "authorization")
                || endsWithSensitiveSemantic(normalized, "credential")
                || endsWithSensitiveSemantic(normalized, "password")
                || endsWithSensitiveSemantic(normalized, "passwd")
                || endsWithSensitiveSemantic(normalized, "secret")
                || endsWithSensitiveSemantic(normalized, "token")
                || endsWithSensitiveSemantic(normalized, "apikey")
                || endsWithSensitiveSemantic(normalized, "signature")
                || endsWithSensitiveSemantic(normalized, "session")
                || endsWithSensitiveSemantic(normalized, "sessionkey")
                || endsWithSensitiveSemantic(normalized, "sessionid")
                || endsWithSensitiveSemantic(normalized, "sessionidss")
                || endsWithSensitiveSemantic(normalized, "sessid")
                || endsWithSensitiveSemantic(normalized, "sessidss")
                || endsWithSensitiveSemantic(normalized, "signedurl")
                || endsWithSensitiveSemantic(normalized, "temporaryurl")
                || endsWithSensitiveSemantic(normalized, "auth")
                || normalized.startsWith("wordpressloggedin")
                || normalized.endsWith("rememberme")
                || normalized.equals("rtfa")
                || normalized.equals("cfclearance")
                || isSidFieldName(trimmed)
                || endsWithSensitiveSemantic(normalized, "ttwid")
                || endsWithSensitiveSemantic(normalized, "odintt")
                || endsWithSensitiveSemantic(normalized, "uidtt")
                || endsWithSensitiveSemantic(normalized, "svwebid")
                || endsWithSensitiveSemantic(normalized, "sig");
    }

    private static boolean isSidFieldName(String fieldName) {
        return SEPARATED_SID.matcher(fieldName).find()
                || CAMEL_CASE_SID.matcher(fieldName).matches();
    }

    private static boolean endsWithSensitiveSemantic(String normalized, String semantic) {
        return normalized.endsWith(semantic)
                || normalized.endsWith(semantic + "value")
                || normalized.endsWith(semantic + "header");
    }
}
