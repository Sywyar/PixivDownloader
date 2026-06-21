package top.sywyar.pixivdownload.common;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * UUID 相关工具方法和共享常量。
 */
@Slf4j
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
        return generateUuidFromFingerprint(
                request.getRemoteAddr(), request.getHeader("User-Agent"));
    }

    /**
     * 基于 IP + User-Agent 生成稳定 UUID（相同输入始终得到相同 UUID）。
     */
    public static String generateUuidFromFingerprint(String ip, String userAgent) {
        try {
            String input = (ip != null ? ip : "") + "|" + (userAgent != null ? userAgent : "");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return UUID.nameUUIDFromBytes(hash).toString();
        } catch (Exception e) {
            log.warn("Failed to generate UUID fingerprint, falling back to random UUID", e);
            return UUID.randomUUID().toString();
        }
    }
}
