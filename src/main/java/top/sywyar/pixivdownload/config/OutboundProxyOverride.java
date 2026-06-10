package top.sywyar.pixivdownload.config;

import org.apache.hc.core5.http.HttpHost;

/**
 * 线程级出站代理覆盖（{@code host:port}）。
 *
 * <p>供计划任务的「任务级单独代理」使用：{@code ScheduleExecutor} 在一轮运行的两类执行线程上
 * （调度主线程的发现 / 元数据 / 站内信检测；下载池线程上的阻塞下载）以 try/finally 包裹
 * {@link #set} / {@link #clear}，{@code RestTemplateConfig.DynamicProxyRoutePlanner} 在确定路由时
 * <b>优先</b>读取本覆盖——存在覆盖时无论全局 {@code proxy.enabled} 与否都改走覆盖代理。
 *
 * <p>覆盖只对设置它的线程生效；HttpClient 的连接池按路由（含代理）区分连接，不会串用。
 * 使用方必须保证 {@code finally} 清除，避免线程池线程残留污染后续无关请求。
 */
public final class OutboundProxyOverride {

    private static final ThreadLocal<HttpHost> OVERRIDE = new ThreadLocal<>();

    private OutboundProxyOverride() {
    }

    /**
     * 为当前线程设置代理覆盖；{@code null} / 空白 / 无法解析的值等同于不设置。
     * 解析规则与 {@link #parse} 一致（{@code host:port}，HTTP 代理）。
     */
    public static void set(String hostPort) {
        HttpHost host = parse(hostPort);
        if (host == null) {
            OVERRIDE.remove();
        } else {
            OVERRIDE.set(host);
        }
    }

    /** 清除当前线程的代理覆盖（必须在 finally 中调用）。 */
    public static void clear() {
        OVERRIDE.remove();
    }

    /** 当前线程的代理覆盖；未设置返回 {@code null}。 */
    public static HttpHost current() {
        return OVERRIDE.get();
    }

    /**
     * 把 {@code host:port} 解析为 HTTP 代理 {@link HttpHost}；
     * {@code null} / 空白 / 缺少端口 / 端口非法（不在 1-65535）返回 {@code null}。
     */
    public static HttpHost parse(String hostPort) {
        if (hostPort == null || hostPort.isBlank()) {
            return null;
        }
        String trimmed = hostPort.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) {
            return null;
        }
        String host = trimmed.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(trimmed.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (host.isBlank() || port < 1 || port > 65535) {
            return null;
        }
        return new HttpHost("http", host, port);
    }
}
