package top.sywyar.pixivdownload.douyin.schedule.security;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 抖音 owner 在计划任务持久化与展示边界使用的来源专属凭据文本判定。 */
public final class DouyinScheduledCredentialText {

    private static final Set<String> CREDENTIAL_SEMANTICS = Set.of(
            "ttwid",
            "odintt",
            "uidtt",
            "svwebid",
            "sessionidss",
            "sidguard",
            "sidtt");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_.-])['\"]?"
                    + "([A-Za-z][A-Za-z0-9_.-]*)['\"]?\\s*[:=]");

    private DouyinScheduledCredentialText() {
    }

    /** 判断字段名是否表达抖音来源专属的 Cookie 凭据。 */
    public static boolean isSensitiveFieldName(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }
        String normalized = fieldName.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        return CREDENTIAL_SEMANTICS.stream().anyMatch(normalized::contains);
    }

    /** 判断自由文本是否带有抖音来源专属字段的赋值形态。 */
    public static boolean containsCredentialMaterial(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        Matcher matcher = ASSIGNMENT.matcher(text);
        while (matcher.find()) {
            if (isSensitiveFieldName(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }
}
