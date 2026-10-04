package top.sywyar.pixivdownload.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.PathContainer;

import java.util.Locale;
import java.util.Optional;

/**
 * 为安全过滤器与路由声明提供和 MVC 一致的逐段解码路径。
 * 字面矩阵参数按段移除；会改变路径结构的编码与点段拒绝进入字符串路由匹配。
 */
public final class SafeRequestPath {

    private SafeRequestPath() {
    }

    public static Optional<String> resolve(HttpServletRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank() || containsUnsafeEncoding(uri)) {
            return Optional.empty();
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty()) {
            if (!uri.startsWith(contextPath)
                    || (uri.length() > contextPath.length() && uri.charAt(contextPath.length()) != '/')) {
                return Optional.empty();
            }
            uri = uri.substring(contextPath.length());
        }
        if (uri.isEmpty()) {
            uri = "/";
        }
        if (uri.charAt(0) != '/' || uri.contains("//") || uri.indexOf('\\') >= 0 || uri.indexOf('\0') >= 0
                || uri.indexOf('?') >= 0 || uri.indexOf('#') >= 0) {
            return Optional.empty();
        }
        try {
            StringBuilder normalized = new StringBuilder(uri.length());
            for (PathContainer.Element element : PathContainer.parsePath(uri).elements()) {
                if (element instanceof PathContainer.PathSegment segment) {
                    String value = segment.valueToMatch();
                    if (value.isEmpty() || value.equals(".") || value.equals("..")
                            || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                            || value.chars().anyMatch(Character::isISOControl)) {
                        return Optional.empty();
                    }
                    normalized.append(value);
                } else {
                    normalized.append(element.value());
                }
            }
            return Optional.of(normalized.toString());
        } catch (IllegalArgumentException malformedEncoding) {
            return Optional.empty();
        }
    }

    private static boolean containsUnsafeEncoding(String uri) {
        return uri.toLowerCase(Locale.ROOT).contains("%3b");
    }

}
