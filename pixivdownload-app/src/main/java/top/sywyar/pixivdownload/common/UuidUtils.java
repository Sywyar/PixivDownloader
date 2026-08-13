package top.sywyar.pixivdownload.common;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * UUID 相关工具方法和共享常量。
 */
@UtilityClass
public class UuidUtils {

    private static final String GENERATED_UUID_ATTRIBUTE = UuidUtils.class.getName() + ".generated";

    /** 标准 UUID 格式（小写或大写 hex，8-4-4-4-12） */
    public static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * 严格解析 UUID v4：先校验 canonical 8-4-4-4-12 外形（两侧空白 / 末尾换行在 trim 后
     * 检查），再要求 {@code version()==4}、{@code variant()==2}（RFC 4122）才返回；
     * 其余一律返回 null，不抛异常。拒绝缺字符 / 多余字符 / 非标准短组 / 缺连字符 /
     * version 非 4 / variant 非 2 的输入。
     *
     * <p>用于安装身份等要求真实 v4 的契约；普通 UUID 场景继续使用 {@link #UUID_PATTERN}，
     * 不把全局模式收紧成只接受 v4。
     */
    public static UUID parseUuidV4(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim();
        if (!UUID_PATTERN.matcher(normalized).matches()) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(normalized);
            return uuid.version() == 4 && uuid.variant() == 2 ? uuid : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 仅读取已存在的 UUID（cookie 或请求头），不自动生成。
     * 返回 null 表示请求方未提供 UUID。
     */
    public static String extractExistingUuid(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("pixiv_user_id".equals(c.getName())) {
                    UUID uuid = parseUuidV4(c.getValue());
                    if (uuid != null) {
                        return uuid.toString();
                    }
                }
            }
        }
        UUID headerUuid = parseUuidV4(request.getHeader("X-User-UUID"));
        return headerUuid == null ? null : headerUuid.toString();
    }

    /**
     * 读取已存在的 UUID，或随机生成一个 UUID v4；同一请求内重复调用返回同一值。
     */
    public static String extractOrGenerateUuid(HttpServletRequest request) {
        String existing = extractExistingUuid(request);
        if (existing != null) return existing;
        Object generated = request.getAttribute(GENERATED_UUID_ATTRIBUTE);
        if (generated instanceof String value && parseUuidV4(value) != null) return value;
        String uuid = UUID.randomUUID().toString();
        request.setAttribute(GENERATED_UUID_ATTRIBUTE, uuid);
        return uuid;
    }
}
