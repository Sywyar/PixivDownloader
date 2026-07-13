package top.sywyar.pixivdownload.plugin.api.schedule.security;

import java.util.regex.Pattern;

/**
 * 计划任务稳定契约共用的纯 JDK 凭证文本判定。它只识别明确的请求头、Cookie、token、secret 与签名形态，
 * 供插件值对象在进入宿主持久化、队列或展示边界前拒绝敏感材料。
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
    private static final Pattern PHPSESSID =
            Pattern.compile("(?i)\\b(PHPSESSID\\s*=\\s*)[^;\\s&]+");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)\\b((?:[A-Za-z0-9_-]*(?:token|secret|password|passwd|credential|signature)"
                    + "(?:[_-]?(?:value|header))?|"
                    + "[A-Za-z0-9_-]*(?:cookie|authorization)(?:[_-]?(?:value|header))?|"
                    + "[A-Za-z0-9_.-]*(?:session|sessionid|sessid)(?:[_-]?ss)?"
                    + "(?:[_-]?value)?|"
                    + "[A-Za-z0-9_.-]*session[_-]?key(?:[_-]?value)?|"
                    + "(?:[A-Za-z0-9_.-]*[._-])?sid(?:[_-]?value)?|"
                    + "sid[_-]?(?:guard|tt)|ttwid|odin[_-]?tt|uid[_-]?tt|s[_-]?v[_-]?web[_-]?id|"
                    + "api[_-]?key(?:[_-]?value)?|"
                    + "(?:signed|temporary)[_-]?url(?:[_-]?value)?|"
                    + "[A-Za-z0-9_.-]*auth|wordpress[_-]logged[_-]in[_-][A-Za-z0-9]+|"
                    + "[A-Za-z0-9_.-]*remember[_-]me|rtfa|cf[_-]clearance|sig)"
                    + "\\s*[:=]\\s*)[^\\s;&,]+");

    private ScheduledCredentialText() {
    }

    /** 判断不透明文本是否带有可识别的凭证头、Cookie、token、secret 或签名形态。 */
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
}
