package top.sywyar.pixivdownload.pixivcontent;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 失败与中止日志共用的请求上下文：路由模板、路径中的数字 ID 和自处理器开始的耗时。
 *
 * <p>只取白名单字段：不含查询参数（搜索词等私有输入）、凭据或上游地址；路径值不是纯数字时记为 {@code invalid}，
 * 避免把调用方的任意文本写进日志。
 */
final class RequestDiagnostics {
    private static final String STARTED = RequestDiagnostics.class.getName() + ".started";
    private static final Pattern NUMERIC_ID = Pattern.compile("[0-9]{1,24}");

    private final String operation;
    private final String identity;
    private final long startedNanos;

    private RequestDiagnostics(String operation, String identity, long startedNanos) {
        this.operation = operation;
        this.identity = identity;
        this.startedNanos = startedNanos;
    }

    static void markStarted(HttpServletRequest request) {
        request.setAttribute(STARTED, System.nanoTime());
    }

    /** 读取当前请求；在请求线程之外（例如单元测试）返回不含上下文的快照。 */
    static RequestDiagnostics current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return of(attributes.getRequest());
        }
        return new RequestDiagnostics("unknown", "", System.nanoTime());
    }

    static RequestDiagnostics of(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Object started = request.getAttribute(STARTED);
        return new RequestDiagnostics(pattern == null ? "unknown" : pattern.toString(),
                identity(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)),
                started instanceof Long nanos ? nanos : System.nanoTime());
    }

    private static String identity(Object variables) {
        if (!(variables instanceof Map<?, ?> values) || values.isEmpty()) return "";
        var sorted = new TreeMap<String, String>();
        values.forEach((name, value) -> sorted.put(String.valueOf(name),
                value != null && NUMERIC_ID.matcher(value.toString()).matches() ? value.toString() : "invalid"));
        var text = new StringBuilder();
        sorted.forEach((name, value) -> text.append(' ').append(name).append('=').append(value));
        return text.toString();
    }

    /** {@return 形如 {@code operation=/api/... id=42 elapsedMs=120} 的日志字段} */
    String fields() {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        return "operation=" + operation + identity + " elapsedMs=" + elapsed;
    }
}
