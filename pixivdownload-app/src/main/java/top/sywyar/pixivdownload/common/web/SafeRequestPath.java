package top.sywyar.pixivdownload.common.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;
import java.util.Optional;

/**
 * Produces the application-relative request path used by security filters and route matching.
 * Literal matrix parameters are removed per path segment, matching Spring MVC's routing view.
 * Encoded semicolons and malformed application paths are rejected because their container decoding
 * semantics are not sufficiently stable for an authorization decision.
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
        if (uri.charAt(0) != '/' || uri.indexOf('\\') >= 0 || uri.indexOf('\0') >= 0
                || uri.indexOf('?') >= 0 || uri.indexOf('#') >= 0) {
            return Optional.empty();
        }
        return Optional.of(removeMatrixParameters(uri));
    }

    private static boolean containsUnsafeEncoding(String uri) {
        return uri.toLowerCase(Locale.ROOT).contains("%3b");
    }

    private static String removeMatrixParameters(String uri) {
        StringBuilder normalized = new StringBuilder(uri.length());
        boolean inParameters = false;
        for (int i = 0; i < uri.length(); i++) {
            char current = uri.charAt(i);
            if (current == ';') {
                inParameters = true;
            } else if (current == '/') {
                inParameters = false;
                normalized.append(current);
            } else if (!inParameters) {
                normalized.append(current);
            }
        }
        return normalized.length() == 0 ? "/" : normalized.toString();
    }
}
