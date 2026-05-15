package top.sywyar.pixivdownload.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;

import java.net.URI;

/**
 * 网络相关工具方法。
 */
@UtilityClass
public class NetworkUtils {

    /**
     * 判断给定的远程地址是否为本地回环地址。
     * 支持 IPv4 和 IPv6 格式。
     */
    public static boolean isLocalAddress(String remoteAddr) {
        return "127.0.0.1".equals(remoteAddr)
            || "localhost".equalsIgnoreCase(remoteAddr)
            || "0:0:0:0:0:0:0:1".equals(remoteAddr)
            || "::1".equals(remoteAddr)
            || "::ffff:127.0.0.1".equals(remoteAddr);
    }

    public static boolean isLocalRequest(HttpServletRequest request) {
        if (request == null || !isLocalAddress(request.getRemoteAddr())) {
            return false;
        }
        return hostHeaderIsLocal(request.getHeader("Host"))
                && forwardedHeaderIsLocal(request.getHeader("X-Forwarded-For"))
                && forwardedHeaderIsLocal(request.getHeader("X-Real-IP"))
                && standardForwardedHeaderIsLocal(request.getHeader("Forwarded"));
    }

    /**
     * 在 {@link #isLocalRequest} 之外叠加 {@code Origin} 头校验，专用于
     * 那些不依赖 cookie / session 而只靠"本机访问"建立信任的端点
     * （如 {@code /api/gui/**}、{@code /api/setup/init}、{@code /api/migration/**}）。
     *
     * <p>动机：浏览器从 {@code evil.com} 上的 JS 调用 {@code fetch('http://localhost:6999/...')}
     * 时，TCP 来源仍是本机，{@link #isLocalRequest} 会通过；这等同于 CSRF。
     * 浏览器在跨站请求中**总会**附带真实的 {@code Origin} 头，
     * 据此可在副作用发生前拦截。
     *
     * <p>非浏览器客户端（curl、GUI 的 Java HTTP 客户端等）通常不附 {@code Origin}，
     * 与缺省 {@code Origin} 一同放行。
     */
    public static boolean isTrustedLocalRequest(HttpServletRequest request) {
        return isLocalRequest(request) && originIsLocalOrAbsent(request);
    }

    /**
     * 当请求未携带 {@code Origin}，或 {@code Origin} 的 host 是本地回环时返回 {@code true}。
     */
    public static boolean originIsLocalOrAbsent(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            // 非浏览器客户端通常不附 Origin —— 放行
            return true;
        }
        if ("null".equalsIgnoreCase(origin)) {
            // sandboxed iframe / file:// / 跨域 redirect 等场景；本项目无合法用例 —— 拒绝
            return false;
        }
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            return host != null && isLocalAddress(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean hostHeaderIsLocal(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return true;
        }
        return isLocalAddress(normalizeHostAddress(headerValue));
    }

    private static boolean forwardedHeaderIsLocal(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return true;
        }
        for (String part : headerValue.split(",")) {
            if (!isLocalAddress(normalizeForwardedAddress(part))) {
                return false;
            }
        }
        return true;
    }

    private static boolean standardForwardedHeaderIsLocal(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return true;
        }
        for (String element : headerValue.split(",")) {
            String forwardedFor = null;
            for (String pair : element.split(";")) {
                int equals = pair.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                String name = pair.substring(0, equals).trim();
                if ("for".equalsIgnoreCase(name)) {
                    forwardedFor = pair.substring(equals + 1).trim();
                    break;
                }
            }
            if (forwardedFor != null && !isLocalAddress(normalizeForwardedAddress(forwardedFor))) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeForwardedAddress(String value) {
        if (value == null) {
            return "";
        }
        String address = value.trim();
        if (address.length() >= 2 && address.startsWith("\"") && address.endsWith("\"")) {
            address = address.substring(1, address.length() - 1).trim();
        }
        if (address.startsWith("[") && address.contains("]")) {
            return address.substring(1, address.indexOf(']'));
        }
        int colon = address.lastIndexOf(':');
        if (colon > 0 && address.indexOf(':') == colon) {
            String port = address.substring(colon + 1);
            if (port.chars().allMatch(Character::isDigit)) {
                return address.substring(0, colon);
            }
        }
        return address;
    }

    private static String normalizeHostAddress(String value) {
        String address = normalizeForwardedAddress(value);
        if (address.endsWith(".")) {
            address = address.substring(0, address.length() - 1);
        }
        return address;
    }
}
