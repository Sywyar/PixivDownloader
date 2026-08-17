package top.sywyar.pixivdownload.config;

import java.util.regex.Pattern;

/**
 * 线程局部的出站代理覆盖值（{@code host:port}）。
 *
 * <p>已激活的覆盖值可以携带 {@code null}，表示显式直连。共享执行器中的任务应使用
 * {@link #runScoped(String, Runnable)} 或 {@link #runDirectScoped(Runnable)}，确保覆盖值始终被清理。
 */
public final class OutboundProxyOverride {

    private static final ThreadLocal<RouteOverride> OVERRIDE = new ThreadLocal<>();
    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    private record RouteOverride(OutboundProxyEndpoint proxy) {
    }

    private OutboundProxyOverride() {
    }

    /**
     * 更新。
     *
     * @param hostPort 主机端口
     */
    public static void set(String hostPort) {
        OutboundProxyEndpoint endpoint = parse(hostPort);
        if (endpoint == null) {
            OVERRIDE.remove();
        } else {
            OVERRIDE.set(new RouteOverride(endpoint));
        }
    }

    /**
     * 更新直连。
     */
    public static void setDirect() {
        OVERRIDE.set(new RouteOverride(null));
    }

    /**
     * 清除。
     */
    public static void clear() {
        OVERRIDE.remove();
    }

    /**
     * 执行对应操作。
     *
     * @param hostPort 主机端口
     * @param task 任务
     */
    public static void runScoped(String hostPort, Runnable task) {
        set(hostPort);
        try {
            task.run();
        } finally {
            clear();
        }
    }

    /**
     * 执行对应操作。
     *
     * @param task 任务
     */
    public static void runDirectScoped(Runnable task) {
        setDirect();
        try {
            task.run();
        } finally {
            clear();
        }
    }

    /**
     * 判断激活状态是否满足条件。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isActive() {
        return OVERRIDE.get() != null;
    }

    /**
     * 返回当前值。
     *
     * @return 方法返回的 {@code OutboundProxyEndpoint} 实例
     */
    public static OutboundProxyEndpoint current() {
        RouteOverride override = OVERRIDE.get();
        return override == null ? null : override.proxy();
    }

    /**
     * 解析严格的 {@code host:port} 值。不接受协议、凭据、路径、内嵌空白和 IPv6 字面量；
     * 无效值或空值返回 {@code null}。
     *
     * @param hostPort 主机端口
     * @return 方法返回的 {@code OutboundProxyEndpoint} 实例
     */
    public static OutboundProxyEndpoint parse(String hostPort) {
        if (hostPort == null || hostPort.isBlank()) {
            return null;
        }
        String trimmed = hostPort.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) {
            return null;
        }
        String host = trimmed.substring(0, colon);
        if (!HOST_PATTERN.matcher(host).matches()) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(trimmed.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (port < 1 || port > 65_535) {
            return null;
        }
        return new OutboundProxyEndpoint(host, port);
    }
}
