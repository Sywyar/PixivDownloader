package top.sywyar.pixivdownload.common;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;

/**
 * Session token 提取工具方法。
 */
@UtilityClass
public class SessionUtils {

    /**
     * 从请求中提取 session token：优先当前实例的 Cookie，其次 X-Session-Token 请求头。
     */
    public static String extractToken(HttpServletRequest request) {
        String cookieName = InstanceCookies.name(request, "pixiv_session");
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (cookieName.equals(c.getName())) return c.getValue();
            }
        }
        return request.getHeader("X-Session-Token");
    }
}
