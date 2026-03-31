package top.sywyar.pixivdownload.common;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;
import top.sywyar.pixivdownload.quota.UserQuotaService;

import java.util.regex.Pattern;

/**
 * UUID 相关工具方法和共享常量。
 */
@UtilityClass
public class UuidUtils {

    /** 标准 UUID 格式（小写或大写 hex，8-4-4-4-12） */
    public static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * 仅读取已存在的 UUID（cookie 或请求头），不自动生成。
     * 返回 null 表示请求方未提供 UUID。
     */
    public static String extractExistingUuid(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("pixiv_user_id".equals(c.getName()) && c.getValue() != null
                        && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        String headerUuid = request.getHeader("X-User-UUID");
        if (headerUuid != null && !headerUuid.isBlank() && UUID_PATTERN.matcher(headerUuid).matches()) {
            return headerUuid;
        }
        return null;
    }

    /**
     * 读取已存在的 UUID，或基于 IP+UA 指纹生成一个新的。
     */
    public static String extractOrGenerateUuid(HttpServletRequest request) {
        String existing = extractExistingUuid(request);
        if (existing != null) return existing;
        return UserQuotaService.generateUuidFromFingerprint(
                request.getRemoteAddr(), request.getHeader("User-Agent"));
    }
}
