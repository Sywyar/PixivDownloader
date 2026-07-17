package top.sywyar.pixivdownload.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;
import top.sywyar.pixivdownload.web.LocalRequestTrust;

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
        return LocalRequestTrust.isLocalAddress(remoteAddr);
    }

    public static boolean isLocalRequest(HttpServletRequest request) {
        return request != null && LocalRequestTrust.isLocalRequest(
                request.getRemoteAddr(),
                request.getHeader("Host"),
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getHeader("Forwarded"));
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
        return request != null && LocalRequestTrust.isTrustedLocalRequest(
                request.getRemoteAddr(),
                request.getHeader("Host"),
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getHeader("Forwarded"),
                request.getHeader("Origin"));
    }

    /**
     * 当请求未携带 {@code Origin}，或 {@code Origin} 的 host 是本地回环时返回 {@code true}。
     */
    public static boolean originIsLocalOrAbsent(HttpServletRequest request) {
        return request != null && LocalRequestTrust.originIsLocalOrAbsent(request.getHeader("Origin"));
    }
}
