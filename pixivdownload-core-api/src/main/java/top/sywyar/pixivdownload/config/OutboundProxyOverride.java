package top.sywyar.pixivdownload.config;

import java.util.regex.Pattern;

/**
 * Thread-local outbound proxy override ({@code host:port}).
 *
 * <p>An active override may carry {@code null} to represent explicit direct routing. Shared executor tasks should
 * use {@link #runScoped(String, Runnable)} or {@link #runDirectScoped(Runnable)} so the override is always cleared.
 */
public final class OutboundProxyOverride {

    private static final ThreadLocal<RouteOverride> OVERRIDE = new ThreadLocal<>();
    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    private record RouteOverride(OutboundProxyEndpoint proxy) {
    }

    private OutboundProxyOverride() {
    }

    public static void set(String hostPort) {
        OutboundProxyEndpoint endpoint = parse(hostPort);
        if (endpoint == null) {
            OVERRIDE.remove();
        } else {
            OVERRIDE.set(new RouteOverride(endpoint));
        }
    }

    public static void setDirect() {
        OVERRIDE.set(new RouteOverride(null));
    }

    public static void clear() {
        OVERRIDE.remove();
    }

    public static void runScoped(String hostPort, Runnable task) {
        set(hostPort);
        try {
            task.run();
        } finally {
            clear();
        }
    }

    public static void runDirectScoped(Runnable task) {
        setDirect();
        try {
            task.run();
        } finally {
            clear();
        }
    }

    public static boolean isActive() {
        return OVERRIDE.get() != null;
    }

    public static OutboundProxyEndpoint current() {
        RouteOverride override = OVERRIDE.get();
        return override == null ? null : override.proxy();
    }

    /**
     * Parses a strict {@code host:port} value. Schemes, credentials, paths, embedded whitespace and IPv6 literals
     * are rejected; invalid or blank values return {@code null}.
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
