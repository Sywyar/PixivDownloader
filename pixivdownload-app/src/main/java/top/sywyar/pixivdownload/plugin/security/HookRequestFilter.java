package top.sywyar.pixivdownload.plugin.security;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;
import top.sywyar.pixivdownload.common.web.SafeRequestPath;
import top.sywyar.pixivdownload.setup.CsrfProtectionFilter;

/** 准备请求复用目标的过滤链与 MVC 匹配，最终在统一拦截器终止，永不执行目标控制器。 */
@Component
@Order(-1)
public final class HookRequestFilter extends OncePerRequestFilter {
    public static final String PREPARE_PATH = "/api/operation-hooks/prepare";
    static final String PREPARATION = HookRequestFilter.class.getName();
    public static boolean isPreparation(HttpServletRequest request) { return request.getAttribute(PREPARATION) instanceof Preparation; }
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(65536).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    record Preparation(String method, String url, Map<String, String> headers, String token, Map<String, String> proofs,
            boolean nativeSubmit, Map<String, JsonNode> json, Map<String, List<String>> parameters, boolean resolveOnly) {
        Preparation(String method, String url, Map<String, String> headers, String token, Map<String, String> proofs,
                boolean nativeSubmit, Map<String, JsonNode> json) { this(method, url, headers, token, proofs, nativeSubmit, json, Map.of(), false); }
    }
    private record Window(long minute, int count) { }
    private final Map<String, Window> windows = new HashMap<>();
    private final java.util.concurrent.Semaphore readers = new java.util.concurrent.Semaphore(16);
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    private final ObjectProvider<CsrfProtectionFilter> csrf;

    public HookRequestFilter(@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mappings,
            ObjectProvider<CsrfProtectionFilter> csrf) { this.mappings = mappings; this.csrf = csrf; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!PREPARE_PATH.equals(SafeRequestPath.resolve(request).orElse(""))) {
            chain.doFilter(new top.sywyar.pixivdownload.common.web.BoundedBodyRequest(request), new HookResponse(response)); return;
        }
        if (!"POST".equals(request.getMethod())) { HttpOperationHooks.reject(response, "METHOD", 405, Map.of()); return; }
        if (!csrf.getObject().allowsWrite(request)) { HttpOperationHooks.reject(response, "FORBIDDEN", 403, Map.of()); return; }
        if (!admit(request.getRemoteAddr()) || !readers.tryAcquire()) { HttpOperationHooks.reject(response, "CAPACITY", 429, Map.of()); return; }
        HttpServletRequest target;
        try {
            if (request.getContentType() == null || !org.springframework.http.MediaType.APPLICATION_JSON.isCompatibleWith(
                    org.springframework.http.MediaType.parseMediaType(request.getContentType()))) throw new IllegalArgumentException();
            byte[] bytes = request.getInputStream().readNBytes(524289);
            if (bytes.length > 524288) { HttpOperationHooks.reject(response, "INPUT_TOO_LARGE", 413, Map.of()); return; }
            Preparation preparation = JSON.readValue(bytes, Preparation.class);
            target = target(request, preparation);
            ServletRequestPathUtils.parseAndCache(target);
            var selected = mappings.getObject().getHandler(target);
            if (selected == null || !(selected.getHandler() instanceof HandlerMethod)) throw new IllegalArgumentException();
            target.setAttribute(PREPARATION, preparation);
        } catch (Exception invalid) {
            HttpOperationHooks.reject(response, "TARGET_INVALID", 400, Map.of()); return;
        } finally { readers.release(); }
        chain.doFilter(target, new HookResponse(response));
    }

    private synchronized boolean admit(String address) {
        long minute = System.currentTimeMillis() / 60_000;
        windows.values().removeIf(window -> window.minute() != minute);
        var window = windows.get(address);
        if (window == null && windows.size() >= 4096 || window != null && window.count() >= 60) return false;
        windows.put(address, new Window(minute, window == null ? 1 : window.count() + 1)); return true;
    }

    private static HttpServletRequest target(HttpServletRequest original, Preparation preparation) {
        if (preparation.method() == null || !Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS").contains(preparation.method())
                || preparation.url() == null || preparation.url().length() > 8192 || !preparation.url().startsWith("/")
                || preparation.url().startsWith("//") || preparation.token() != null && preparation.token().length() > 128
                || preparation.json() != null && (preparation.json().size() > 256 || preparation.json().keySet().stream().anyMatch(key -> key.length() > 256)))
            throw new IllegalArgumentException();
        if ((preparation.token() == null || preparation.token().isEmpty()) && preparation.proofs() != null && !preparation.proofs().isEmpty())
            throw new IllegalArgumentException();
        URI uri = URI.create(preparation.url());
        if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null) throw new IllegalArgumentException();
        var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        if (preparation.headers() != null) for (var entry : preparation.headers().entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            // 认证、代理身份与来源只继承真实请求，不能由准备信封覆盖。
            if (!(name.equals("accept") || name.equals("content-type") || name.startsWith("x-"))
                    || name.startsWith("x-forwarded-") || name.startsWith("x-pixiv-") || name.startsWith("x-real-")
                    || name.contains("token") || name.contains("key") || name.contains("auth") || name.contains("cookie")
                    || !name.matches("[a-z0-9-]{1,96}") || entry.getValue() == null || entry.getValue().length() > 1024
                    || entry.getValue().chars().anyMatch(Character::isISOControl) || headers.size() >= 16) throw new IllegalArgumentException();
            headers.put(name, entry.getValue());
        }
        var parameters = new LinkedHashMap<String, List<String>>();
        if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&", -1)) {
            if (parameters.values().stream().mapToInt(List::size).sum() >= 128) throw new IllegalArgumentException();
            String[] pair = part.split("=", 2);
            parameters.computeIfAbsent(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), ignored -> new ArrayList<>())
                    .add(URLDecoder.decode(pair.length == 1 ? "" : pair[1], StandardCharsets.UTF_8));
        }
        if (preparation.parameters() != null && !preparation.parameters().isEmpty()) {
            String type = headers.getOrDefault("content-type", "").split(";", 2)[0].trim();
            if (!Set.of("application/x-www-form-urlencoded", "multipart/form-data").contains(type)) throw new IllegalArgumentException();
            for (var entry : preparation.parameters().entrySet()) {
                if (entry.getKey().length() > 256 || entry.getValue() == null || entry.getValue().isEmpty()
                        || entry.getValue().stream().anyMatch(value -> value == null || value.length() > 4096)
                        || parameters.values().stream().mapToInt(List::size).sum() + entry.getValue().size() > 128) throw new IllegalArgumentException();
                parameters.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).addAll(entry.getValue());
            }
        }
        var request = new HttpServletRequestWrapper(original) {
            @Override public String getMethod() { return preparation.method(); }
            @Override public String getRequestURI() { return original.getContextPath() + uri.getRawPath(); }
            @Override public String getServletPath() { return uri.getRawPath(); }
            @Override public String getPathInfo() { return null; }
            @Override public String getQueryString() { return uri.getRawQuery(); }
            @Override public StringBuffer getRequestURL() { return new StringBuffer(original.getRequestURL().toString()
                    .substring(0, original.getRequestURL().length() - original.getRequestURI().length()) + getRequestURI()); }
            @Override public String getContentType() { return headers.get("content-type"); }
            @Override public long getContentLengthLong() { return getContentType() == null ? 0 : 1; }
            @Override public int getContentLength() { return (int) getContentLengthLong(); }
            @Override public String getHeader(String name) {
                if (name.equalsIgnoreCase("Content-Length")) return Long.toString(getContentLengthLong());
                if (name.equalsIgnoreCase("Content-Type")) return getContentType();
                return headers.containsKey(name) ? headers.get(name) : super.getHeader(name);
            }
            @Override public Enumeration<String> getHeaders(String name) {
                if (name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Content-Type")) {
                    String value = getHeader(name); return Collections.enumeration(value == null ? List.of() : List.of(value));
                }
                return headers.containsKey(name) ? Collections.enumeration(List.of(headers.get(name))) : super.getHeaders(name);
            }
            @Override public String getParameter(String name) { var values = parameters.get(name); return values == null ? null : values.get(0); }
            @Override public String[] getParameterValues(String name) { var values = parameters.get(name); return values == null ? null : values.toArray(String[]::new); }
            @Override public Map<String, String[]> getParameterMap() {
                var values = new LinkedHashMap<String, String[]>(); parameters.forEach((name, items) -> values.put(name, items.toArray(String[]::new)));
                return Collections.unmodifiableMap(values);
            }
            @Override public Enumeration<String> getParameterNames() { return Collections.enumeration(parameters.keySet()); }
        };
        if (SafeRequestPath.resolve(request).filter(path -> !path.equals(PREPARE_PATH)).isEmpty()) throw new IllegalArgumentException();
        return request;
    }

}
